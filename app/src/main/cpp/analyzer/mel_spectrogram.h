#pragma once
#include <cstddef>
#include <vector>
namespace bitchord::smart {
constexpr double kBeatSpectrogramSampleRate=22050.0;
constexpr size_t kBeatSpectrogramFft=1024;
constexpr size_t kBeatSpectrogramHop=441;
constexpr size_t kBeatSpectrogramMels=128;
struct BeatSpectrogram { std::vector<float> values; size_t frames=0; };
BeatSpectrogram ComputeBeatSpectrogram(const std::vector<float>& samples,double sample_rate);
}
