package com.music.orb.data.sources

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.music.orb.BuildConfig
import com.music.orb.data.TrackLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.util.UUID

@Serializable
data class SourceConfig(
    val id: String = UUID.randomUUID().toString(),
    val kind: SourceKind,
    val label: String = "",
    val baseUrl: String = "",
    val enabled: Boolean = true,
) {
    val displayName: String
        get() = label.ifBlank {
            baseUrl.takeIf { it.isNotBlank() }
                ?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                ?: kind.label
        }
    val isComplete: Boolean get() = !kind.needsServer || baseUrl.isNotBlank()
}

/** Source registry following the fixed BitChord v1.5 order. */
object SourceRegistry {
    private const val TAG = "BitChord"
    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val configs = MutableStateFlow<List<SourceConfig>>(emptyList())
    private var instances: Map<String, MusicSource> = emptyMap()

    fun init(context: Context) {
        prefs = runCatching {
            EncryptedSharedPreferences.create(
                context,
                "bitchord_sources",
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            TrackLog.w(TAG, "EncryptedSharedPreferences unavailable for sources: ${it.message}")
            context.getSharedPreferences("bitchord_sources_plain", Context.MODE_PRIVATE)
        }

        val stored = prefs.getString(KEY_SOURCES, null)?.let(::decodeStored).orEmpty()
        var seeded = stored + BUILT_IN_KINDS
            .filter { kind -> stored.none { it.kind == kind } }
            .map { SourceConfig(kind = it, enabled = true) }

        val envUrl = BuildConfig.MODULE_INDEX_URL.trim()
        if (envUrl.isNotEmpty()) {
            val existing = seeded.firstOrNull { it.kind == SourceKind.MODULE }
            seeded = when {
                existing == null -> seeded + SourceConfig(
                    kind = SourceKind.MODULE,
                    label = ENV_MODULE_LABEL,
                    baseUrl = envUrl,
                    enabled = true,
                )
                existing.baseUrl != envUrl || existing.label.isBlank() -> seeded.map {
                    if (it.id == existing.id) it.copy(
                        baseUrl = envUrl,
                        label = it.label.ifBlank { ENV_MODULE_LABEL },
                    ) else it
                }
                else -> seeded
            }
        }

        val after = seeded.map {
            if (it.kind == SourceKind.YOUTUBE && !it.enabled) it.copy(enabled = true) else it
        }
        publish(after, persist = after != stored)
    }

    private fun decodeStored(raw: String): List<SourceConfig> {
        val elements = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrElse { return emptyList() }
        return elements.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(SourceConfig.serializer(), element) }
                .onFailure { TrackLog.w(TAG, "dropping unreadable stored source: ${it.message}") }
                .getOrNull()
        }
    }

    fun active(): List<MusicSource> = configs.value
        .filter { it.enabled && it.isComplete }
        .sortedBy { it.kind.ordinal }
        .mapNotNull { instances[it.id] }

    fun instance(configId: String): MusicSource? = instances[configId]
    fun config(configId: String): SourceConfig? = configs.value.firstOrNull { it.id == configId }
    fun add(config: SourceConfig) = publish(configs.value + config)
    fun update(config: SourceConfig) = publish(configs.value.map { if (it.id == config.id) config else it })

    fun remove(configId: String) {
        val target = config(configId) ?: return
        if (target.kind in BUILT_IN_KINDS) return
        publish(configs.value.filterNot { it.id == configId })
    }

    fun setEnabled(configId: String, enabled: Boolean) {
        if (!enabled && config(configId)?.kind == SourceKind.YOUTUBE) return
        publish(configs.value.map { if (it.id == configId) it.copy(enabled = enabled) else it })
    }

    fun setModuleEnabled(enabled: Boolean) {
        configs.value.firstOrNull { it.kind == SourceKind.MODULE }?.let { setEnabled(it.id, enabled) }
    }

    fun customModule(): SourceConfig? = configs.value.firstOrNull { it.kind == SourceKind.CUSTOM_MODULE }

    fun setCustomModule(url: String, label: String = "") {
        val trimmed = url.trim()
        val without = configs.value.filterNot { it.kind == SourceKind.CUSTOM_MODULE }
        publish(
            if (trimmed.isEmpty()) without
            else without + SourceConfig(
                kind = SourceKind.CUSTOM_MODULE,
                label = label.trim(),
                baseUrl = trimmed,
                enabled = true,
            )
        )
    }

    private fun publish(next: List<SourceConfig>, persist: Boolean = true) {
        configs.value = next
        val previous = instances
        val rebuilt = next.associate { config ->
            val existing = previous[config.id]?.takeIf { it.configuredBy(config) }
            config.id to (existing ?: build(config))
        }
        previous.forEach { (id, source) ->
            if (rebuilt[id] !== source) (source as? AddonSource)?.release()
        }
        instances = rebuilt
        if (persist && ::prefs.isInitialized) {
            prefs.edit().putString(
                KEY_SOURCES,
                json.encodeToString(ListSerializer(SourceConfig.serializer()), next),
            ).apply()
        }
    }

    suspend fun probeCandidate(config: SourceConfig): SourceHealth {
        val source = build(config)
        return try {
            source.health()
        } finally {
            (source as? AddonSource)?.release()
        }
    }

    private fun build(config: SourceConfig): MusicSource = when (config.kind) {
        SourceKind.TIDAL -> TidalSource(config)
        SourceKind.ADDON -> AddonSource(config)
        SourceKind.CUSTOM_MODULE, SourceKind.MODULE -> ModuleSource(config)
        SourceKind.JIOSAAVN -> JioSaavnSource(config)
        SourceKind.YOUTUBE -> YouTubeSource(config)
    }

    private fun MusicSource.configuredBy(config: SourceConfig): Boolean =
        this is ConfigBacked && this.config == config

    internal interface ConfigBacked { val config: SourceConfig }

    fun trackKey(configId: String, trackId: String) = "$PREFIX$configId$SEPARATOR$trackId"

    fun parseTrackKey(key: String): Pair<String, String>? {
        if (!key.startsWith(PREFIX)) return null
        val body = key.removePrefix(PREFIX)
        val cut = body.indexOf(SEPARATOR)
        if (cut <= 0) return null
        return body.substring(0, cut) to body.substring(cut + SEPARATOR.length)
    }

    fun trackUri(configId: String, trackId: String): String = Uri.Builder()
        .scheme("orb")
        .authority("source")
        .appendQueryParameter("s", configId)
        .appendQueryParameter("t", trackId)
        .build().toString()

    private val BUILT_IN_KINDS = listOf(SourceKind.TIDAL, SourceKind.JIOSAAVN, SourceKind.YOUTUBE)
    private const val ENV_MODULE_LABEL = "Ricky's Addon"
    private const val KEY_SOURCES = "sources"
    private const val PREFIX = "src:"
    private const val SEPARATOR = "::"
}
