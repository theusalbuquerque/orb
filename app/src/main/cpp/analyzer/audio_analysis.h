#pragma once
#include <string>
#include <vector>
namespace bitchord::smart {
struct EnergyPoint{double time=0,energy=0;}; struct MixCuePoint{double time=0,score=0;std::string type;};
struct AnalysisResult{
 double duration=0,bpm=0,beat_interval=0,first_beat=0,beat_confidence=0,key_confidence=0,audible_start_time=0,pickup_time=0,intro_end_time=0,outro_start_time=0,content_end_time=0,mix_in_time=0,mix_out_time=0,vocal_probability=0;
 std::string key; std::vector<double> downbeats,phrase_boundaries,vocal_activity_mask; std::vector<EnergyPoint> energy_curve,low_energy_curve; std::vector<MixCuePoint> mix_in_candidates,mix_out_candidates;
};
AnalysisResult AnalyzeAudio(const std::vector<float>& samples,double sample_rate,double duration_seconds);
}
