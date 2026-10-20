package com.music.orb.data.spotify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SpotifyShelfState(
    val playlists: List<SpotifyPlaylist> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

/** Session-scoped cache shared by Home and Library; scrolling cannot cancel a request. */
object SpotifyLibraryShelfStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(SpotifyShelfState())
    val state = _state.asStateFlow()
    private var request: Job? = null
    private var generation = 0

    fun reset() {
        generation++
        request?.cancel()
        request = null
        _state.value = SpotifyShelfState()
    }

    fun load(force: Boolean = false) {
        if (!SpotifySession.connected.value || _state.value.loading) return
        if (!force && (_state.value.loaded || _state.value.failed)) return
        val currentGeneration = generation
        _state.value = _state.value.copy(loading = true, failed = false)
        request = scope.launch {
            try {
                val playlists = SpotifyLibrary.playlists()
                if (generation == currentGeneration) {
                    _state.value = SpotifyShelfState(playlists = playlists, loaded = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == currentGeneration) {
                    _state.value = _state.value.copy(loading = false, failed = true)
                    android.util.Log.w("OrbSpotifyLibrary", "Library load failed: ${failure.javaClass.simpleName}")
                }
            }
        }
    }
}
