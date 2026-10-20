#pragma once
#include <vector>
namespace orb::smart {
std::vector<float> Resample(const std::vector<float>& input, double input_rate, double output_rate);
}
