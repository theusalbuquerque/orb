package com.music.orb.data.settings

enum class TrackAnalysisState { WAITING, ANALYSING, ANALYSED, REFINING, FAILED }
data class SmartAnalysis(
    val current: TrackAnalysisState = TrackAnalysisState.WAITING,
    val next: TrackAnalysisState = TrackAnalysisState.WAITING,
)
data class TransitionWindow(val start: Float, val end: Float)
