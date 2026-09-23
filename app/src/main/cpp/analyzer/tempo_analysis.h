#pragma once
#include <vector>
namespace bitchord::smart { struct TempoResult{double bpm=0,interval=0,first=0,confidence=0;}; TempoResult AnalyzeTempo(const std::vector<double>& onset,double frame_seconds); }
