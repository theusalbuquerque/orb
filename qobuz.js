/*
 * Orb Qobuz Lossless module
 *
 * Contract expected by Orb Play / ModuleManager:
 *   searchTracks(query, limit, context)
 *   getTrackStreamUrl(trackId, preferredQuality, context)
 *
 * Qobuz credentials stay on the FastAPI server. This JS contains no token.
 */

var API_BASE = "https://orb-4mrh.onrender.com";

function setting(context, key, fallback) {
    try {
        var value = context && context.settings && context.settings[key]
            ? context.settings[key].value
            : null;
        return value || fallback;
    } catch (e) {
        return fallback;
    }
}

async function readJson(response, label) {
    if (!response || !response.ok) {
        var status = response ? response.status : "no response";
        throw new Error(label + " failed: HTTP " + status);
    }
    return response.json();
}

async function searchTracks(query, limit, context) {
    var safeLimit = Number(limit || 15);

    if (!safeLimit || safeLimit < 1) {
        safeLimit = 15;
    }

    if (safeLimit > 50) {
        safeLimit = 50;
    }

    var url = API_BASE
        + "/api/search?query="
        + encodeURIComponent(String(query || ""))
        + "&limit="
        + encodeURIComponent(String(safeLimit));

    var response = await fetch(url, {
        method: "GET",
        headers: {
            "Accept": "application/json"
        }
    });

    var data = await readJson(response, "Qobuz search");

    return {
        tracks: Array.isArray(data.tracks)
            ? data.tracks
            : [],
        total: Number(data.total || 0)
    };
}

async function getTrackStreamUrl(trackId, preferredQuality, context) {
    var contextQuality = setting(
        context,
        "quality",
        ""
    );

    var quality = String(
        contextQuality
        || preferredQuality
        || "LOSSLESS"
    ).toUpperCase();

    /*
     * Quando o SourceResolver pede Lossless,
     * ele envia a qualidade desejada para cá.
     *
     * O backend recebe essa qualidade e tenta
     * retornar o stream correspondente.
     */
    var url = API_BASE
        + "/api/stream?track_id="
        + encodeURIComponent(String(trackId || ""))
        + "&quality="
        + encodeURIComponent(quality);

    var response = await fetch(url, {
        method: "GET",
        headers: {
            "Accept": "application/json"
        }
    });

    var data = await readJson(
        response,
        "Qobuz stream"
    );

    return {
        streamUrl: data.streamUrl || "",
        track: data.track || {
            id: String(trackId || ""),
            audioQuality: quality,
            mimeType: null,
            bitDepth: null,
            sampleRate: null,
            audioModes: null
        }
    };
}

module.exports = {
    searchTracks: searchTracks,
    getTrackStreamUrl: getTrackStreamUrl
};