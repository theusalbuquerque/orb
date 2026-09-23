package com.music.orb.data.sources.sflx

import org.json.JSONArray
import org.json.JSONObject

data class SflxPermissions(
    val network: List<String> = emptyList(),
    val storage: Boolean = false,
    val file: Boolean = false,
    val allowHttp: Boolean = false,
)

data class SflxQualityOption(
    val id: String,
    val label: String = id,
)

data class SflxSignedSessionEndpoints(
    val bootstrap: String = "/bootstrap",
    val challenge: String = "/challenge",
    val exchange: String = "/session/exchange",
    val refresh: String = "",
)

data class SflxSignedSessionConfig(
    val namespace: String,
    val baseUrl: String,
    val appVersion: String = "ext-1.0",
    val platform: String = "extension",
    val callbackUrl: String = "spotiflac://session-grant",
    val schemeLabel: String = "SPOTIFLAC-HMAC-V1",
    val headerPrefix: String = "X-Sig-",
    val timeWindowSeconds: Int = 300,
    val endpoints: SflxSignedSessionEndpoints = SflxSignedSessionEndpoints(),
)

data class SflxManifest(
    val name: String,
    val displayName: String,
    val version: String,
    val description: String,
    val types: Set<String>,
    val permissions: SflxPermissions,
    val qualityOptions: List<SflxQualityOption>,
    val requiredRuntimeFeatures: List<String>,
    val signedSession: SflxSignedSessionConfig?,
) {
    val isDownloadProvider: Boolean get() = "download_provider" in types
    val isMetadataProvider: Boolean get() = "metadata_provider" in types

    companion object {
        private val validName = Regex("^[a-z0-9][a-z0-9._-]{0,127}$")

        fun parse(raw: String): SflxManifest {
            val json = JSONObject(raw)
            val name = json.optString("name").trim()
            require(validName.matches(name)) { "Invalid SFLX extension id" }

            val version = json.optString("version").trim()
            require(version.matches(Regex("^\\d+(?:\\.\\d+){1,3}(?:[-+][A-Za-z0-9._-]+)?$"))) {
                "Invalid SFLX version"
            }

            val types = json.optJSONArray("type").strings().toSet()
            require(types.isNotEmpty()) { "SFLX manifest has no provider type" }

            val permissionsObject = json.optJSONObject("permissions") ?: JSONObject()
            val permissions = SflxPermissions(
                network = permissionsObject.optJSONArray("network").strings(),
                storage = permissionsObject.optBoolean("storage", false),
                file = permissionsObject.optBoolean("file", false),
                allowHttp = permissionsObject.optBoolean("allowHttp", false),
            )

            val qualityOptions = buildList {
                val rawOptions = json.optJSONArray("qualityOptions") ?: JSONArray()
                for (index in 0 until rawOptions.length()) {
                    when (val value = rawOptions.opt(index)) {
                        is String -> value.trim().takeIf { it.isNotEmpty() }?.let { add(SflxQualityOption(it)) }
                        is JSONObject -> {
                            val id = value.optString("id").trim()
                            if (id.isNotEmpty()) {
                                add(SflxQualityOption(id, value.optString("label", id).ifBlank { id }))
                            }
                        }
                    }
                }
            }

            val signedSession = json.optJSONObject("signedSession")?.let { signed ->
                val namespace = signed.optString("namespace").trim()
                val baseUrl = signed.optString("baseUrl").trim()
                if (namespace.isBlank() || baseUrl.isBlank()) {
                    null
                } else {
                    val endpoints = signed.optJSONObject("endpoints") ?: JSONObject()
                    SflxSignedSessionConfig(
                        namespace = namespace,
                        baseUrl = baseUrl,
                        appVersion = signed.optString("appVersion", "ext-1.0").ifBlank { "ext-1.0" },
                        platform = signed.optString("platform", "extension").ifBlank { "extension" },
                        callbackUrl = signed.optString("callbackUrl", "spotiflac://session-grant")
                            .ifBlank { "spotiflac://session-grant" },
                        schemeLabel = signed.optString("schemeLabel", "SPOTIFLAC-HMAC-V1")
                            .ifBlank { "SPOTIFLAC-HMAC-V1" },
                        headerPrefix = signed.optString("headerPrefix", "X-Sig-").ifBlank { "X-Sig-" },
                        timeWindowSeconds = signed.optInt("timeWindowSeconds", 300).coerceAtLeast(1),
                        endpoints = SflxSignedSessionEndpoints(
                            bootstrap = endpoints.optString("bootstrap", "/bootstrap").ifBlank { "/bootstrap" },
                            challenge = endpoints.optString("challenge", "/challenge").ifBlank { "/challenge" },
                            exchange = endpoints.optString("exchange", "/session/exchange").ifBlank { "/session/exchange" },
                            refresh = endpoints.optString("refresh", ""),
                        ),
                    )
                }
            }

            return SflxManifest(
                name = name,
                displayName = json.optString("displayName", name).ifBlank { name },
                version = version,
                description = json.optString("description"),
                types = types,
                permissions = permissions,
                qualityOptions = qualityOptions,
                requiredRuntimeFeatures = json.optJSONArray("requiredRuntimeFeatures").strings(),
                signedSession = signedSession,
            )
        }
    }
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
        }
    }
}
