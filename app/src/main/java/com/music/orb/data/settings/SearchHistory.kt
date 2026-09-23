package com.music.orb.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One recent search plus optional artwork for the media it resolved to. */
data class SearchHistoryEntry(
    val query: String,
    val thumbnailUrl: String? = null,
)

/**
 * What's been searched for lately, kept on this device only.
 *
 * The visible history is deliberately short: Explore is also a discovery page,
 * so recent searches must not push the recommendation grid too far down.
 *
 * Older Orb builds stored this preference as a JSON array of strings. The
 * decoder below accepts both that legacy form and the richer object form, so an
 * update preserves the user's existing history instead of resetting it.
 */
object SearchHistory {

    private const val MAX_ENTRIES = 6

    private lateinit var prefs: SharedPreferences
    private val json = Json

    private val _recent = MutableStateFlow<List<SearchHistoryEntry>>(emptyList())

    /** Most recent first. */
    val recent: StateFlow<List<SearchHistoryEntry>> = _recent.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("bitchord_settings", Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_HISTORY, null) ?: "[]"
        _recent.value = decode(raw).take(MAX_ENTRIES)
    }

    /**
     * Records [query], or moves it back to the top if it's already there.
     *
     * A later result click can add [thumbnailUrl] to an entry that was first
     * created by pressing Search. Re-recording without artwork never erases an
     * image we already know for that query.
     */
    fun record(query: String, thumbnailUrl: String? = null) {
        val term = query.trim()
        if (term.isEmpty()) return
        val previous = _recent.value.firstOrNull { it.query.equals(term, ignoreCase = true) }
        val entry = SearchHistoryEntry(
            query = term,
            thumbnailUrl = thumbnailUrl ?: previous?.thumbnailUrl,
        )
        val deduped = _recent.value.filterNot { it.query.equals(term, ignoreCase = true) }
        save((listOf(entry) + deduped).take(MAX_ENTRIES))
    }

    fun remove(query: String) {
        save(_recent.value.filterNot { it.query.equals(query, ignoreCase = true) })
    }

    fun clear() = save(emptyList())

    private fun decode(raw: String): List<SearchHistoryEntry> = runCatching {
        json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { SearchHistoryEntry(query = it) }

                is JsonObject -> {
                    val query = element["query"]?.jsonPrimitive?.contentOrNull
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: return@mapNotNull null
                    SearchHistoryEntry(
                        query = query,
                        thumbnailUrl = element["thumbnailUrl"]?.jsonPrimitive?.contentOrNull,
                    )
                }

                else -> null
            }
        }
    }.getOrDefault(emptyList())

    private fun save(value: List<SearchHistoryEntry>) {
        _recent.value = value
        val encoded = buildJsonArray {
            value.forEach { entry ->
                add(
                    buildJsonObject {
                        put("query", entry.query)
                        entry.thumbnailUrl?.let { put("thumbnailUrl", it) }
                    },
                )
            }
        }.toString()
        prefs.edit().putString(KEY_HISTORY, encoded).apply()
    }

    private const val KEY_HISTORY = "search_history"
}
