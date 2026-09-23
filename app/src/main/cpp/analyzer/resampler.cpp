#include "resampler.h"
#include <algorithm>
#include <cmath>
namespace bitchord::smart {
namespace { constexpr int kHalfTaps = 16; constexpr double kPi = 3.14159265358979323846;
inline double sinc(double x){ if(std::abs(x)<1e-12) return 1.0; const double p=kPi*x; return std::sin(p)/p; }
inline double hann(double x){ x=std::abs(x); if(x>=kHalfTaps) return 0.0; return 0.5+0.5*std::cos(kPi*x/kHalfTaps); }
}
std::vector<float> Resample(const std::vector<float>& input,double input_rate,double output_rate){
 if(input.empty()||input_rate<=0||output_rate<=0) return {}; if(std::abs(input_rate-output_rate)<1e-6) return input;
 const double ratio=output_rate/input_rate; const size_t out_n=std::max<size_t>(1,(size_t)std::llround(input.size()*ratio)); std::vector<float> out(out_n);
 const double cutoff=std::min(1.0,ratio);
 for(size_t i=0;i<out_n;++i){ double src=i/ratio; int center=(int)std::floor(src); double sum=0,ws=0;
  for(int k=-kHalfTaps+1;k<=kHalfTaps;++k){ int j=center+k; if(j<0||j>=(int)input.size()) continue; double d=src-j; double w=cutoff*sinc(d*cutoff)*hann(d); sum+=input[j]*w; ws+=w; }
  out[i]=(float)(ws!=0?sum/ws:0.0);
 } return out;
}
}
