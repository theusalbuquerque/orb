#pragma once
#include <cstddef>
#include <vector>
namespace bitchord::smart {
constexpr double kVocalSpectrogramSampleRate=44100.0;
constexpr size_t kVocalSpectrogramFft=4096;
constexpr size_t kVocalSpectrogramHop=1024;
constexpr size_t kVocalSpectrogramBins=2049;
struct VocalSpectrogram { std::vector<float> values; size_t frames=0; };
VocalSpectrogram ComputeVocalSpectrogram(const std::vector<std::vector<float>>& channels,double sample_rate);

/**
 * Reconstructs the Open-Unmix vocal target back to stereo PCM using the
 * mixture phase. target_values uses the model layout [2, bins, model_frames].
 * Only usable_frames carry real audio; the rest of the fixed-width model
 * tensor is padding.
 */
std::vector<std::vector<float>> ReconstructVocalStem(
    const std::vector<std::vector<float>>& channels,
    double sample_rate,
    const float* target_values,
    size_t model_frames,
    size_t usable_frames);
}
