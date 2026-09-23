#include "vocal_spectrogram.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <vector>

namespace bitchord::smart {
namespace {
constexpr double PI = 3.14159265358979323846;

inline double hann(size_t i) {
  return 0.5 - 0.5 * std::cos(2.0 * PI * static_cast<double>(i) /
                              static_cast<double>(kVocalSpectrogramFft));
}

void fft(std::vector<std::complex<double>>& a, bool inverse = false) {
  const size_t n = a.size();
  for (size_t i = 1, j = 0; i < n; ++i) {
    size_t bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) std::swap(a[i], a[j]);
  }
  for (size_t len = 2; len <= n; len <<= 1) {
    const double angle = (inverse ? 2.0 : -2.0) * PI / static_cast<double>(len);
    const std::complex<double> wlen(std::cos(angle), std::sin(angle));
    for (size_t i = 0; i < n; i += len) {
      std::complex<double> w(1.0, 0.0);
      for (size_t j = 0; j < len / 2; ++j) {
        const auto u = a[i + j];
        const auto v = a[i + j + len / 2] * w;
        a[i + j] = u + v;
        a[i + j + len / 2] = u - v;
        w *= wlen;
      }
    }
  }
  if (inverse) {
    const double scale = 1.0 / static_cast<double>(n);
    for (auto& value : a) value *= scale;
  }
}
}  // namespace

VocalSpectrogram ComputeVocalSpectrogram(
    const std::vector<std::vector<float>>& channels,
    double sample_rate) {
  VocalSpectrogram result;
  if (channels.size() < 2 ||
      std::abs(sample_rate - kVocalSpectrogramSampleRate) > 1.0) {
    return result;
  }
  const size_t sample_count = std::min(channels[0].size(), channels[1].size());
  if (sample_count < kVocalSpectrogramFft) return result;

  const size_t frames = 1 + (sample_count - kVocalSpectrogramFft) / kVocalSpectrogramHop;
  result.frames = frames;
  result.values.assign(2 * kVocalSpectrogramBins * frames, 0.0f);
  std::vector<std::complex<double>> spectrum(kVocalSpectrogramFft);

  for (size_t channel = 0; channel < 2; ++channel) {
    for (size_t frame = 0; frame < frames; ++frame) {
      const size_t start = frame * kVocalSpectrogramHop;
      for (size_t i = 0; i < kVocalSpectrogramFft; ++i) {
        spectrum[i] = {channels[channel][start + i] * hann(i), 0.0};
      }
      fft(spectrum);
      for (size_t bin = 0; bin < kVocalSpectrogramBins; ++bin) {
        result.values[(channel * kVocalSpectrogramBins + bin) * frames + frame] =
            static_cast<float>(std::abs(spectrum[bin]));
      }
    }
  }
  return result;
}

std::vector<std::vector<float>> ReconstructVocalStem(
    const std::vector<std::vector<float>>& channels,
    double sample_rate,
    const float* target_values,
    size_t model_frames,
    size_t usable_frames) {
  if (channels.size() < 2 || target_values == nullptr || model_frames == 0 ||
      std::abs(sample_rate - kVocalSpectrogramSampleRate) > 1.0) {
    return {};
  }

  const size_t sample_count = std::min(channels[0].size(), channels[1].size());
  if (sample_count < kVocalSpectrogramFft) return {};
  const size_t available_frames =
      1 + (sample_count - kVocalSpectrogramFft) / kVocalSpectrogramHop;
  const size_t frames = std::min({usable_frames, available_frames, model_frames});
  if (frames == 0) return {};

  std::vector<std::vector<float>> output(2, std::vector<float>(sample_count, 0.0f));
  std::vector<double> normalization(sample_count, 0.0);
  std::vector<std::complex<double>> mix(kVocalSpectrogramFft);
  std::vector<std::complex<double>> vocal(kVocalSpectrogramFft);

  // Normalization is identical for both channels, so compute it once.
  for (size_t frame = 0; frame < frames; ++frame) {
    const size_t start = frame * kVocalSpectrogramHop;
    for (size_t i = 0; i < kVocalSpectrogramFft; ++i) {
      const double w = hann(i);
      normalization[start + i] += w * w;
    }
  }

  for (size_t channel = 0; channel < 2; ++channel) {
    for (size_t frame = 0; frame < frames; ++frame) {
      const size_t start = frame * kVocalSpectrogramHop;
      for (size_t i = 0; i < kVocalSpectrogramFft; ++i) {
        mix[i] = {channels[channel][start + i] * hann(i), 0.0};
      }
      fft(mix);
      std::fill(vocal.begin(), vocal.end(), std::complex<double>(0.0, 0.0));

      for (size_t bin = 0; bin < kVocalSpectrogramBins; ++bin) {
        const double magnitude = std::abs(mix[bin]);
        const size_t target_index =
            (channel * kVocalSpectrogramBins + bin) * model_frames + frame;
        // Open-Unmix estimates the target magnitude. A ratio mask with mixture
        // phase is stable, conservative and cheap enough for a transition window.
        const double mask = magnitude > 1e-9
                                ? std::clamp(static_cast<double>(target_values[target_index]) /
                                                 magnitude,
                                             0.0, 1.0)
                                : 0.0;
        vocal[bin] = mix[bin] * mask;
        if (bin > 0 && bin + 1 < kVocalSpectrogramBins) {
          vocal[kVocalSpectrogramFft - bin] = std::conj(vocal[bin]);
        }
      }

      fft(vocal, /* inverse = */ true);
      for (size_t i = 0; i < kVocalSpectrogramFft; ++i) {
        output[channel][start + i] +=
            static_cast<float>(vocal[i].real() * hann(i));
      }
    }

    for (size_t i = 0; i < sample_count; ++i) {
      if (normalization[i] > 1e-9) {
        output[channel][i] = static_cast<float>(output[channel][i] / normalization[i]);
      }
      output[channel][i] = std::clamp(output[channel][i], -1.0f, 1.0f);
    }
  }

  return output;
}
}  // namespace bitchord::smart
