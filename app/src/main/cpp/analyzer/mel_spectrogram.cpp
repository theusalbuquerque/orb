#include "mel_spectrogram.h"
#include <algorithm>
#include <cmath>
#include <complex>
namespace bitchord::smart {
namespace { constexpr double PI=3.14159265358979323846;
void fft(std::vector<std::complex<double>>& a){ size_t n=a.size(); for(size_t i=1,j=0;i<n;i++){ size_t bit=n>>1; for(;j&bit;bit>>=1) j^=bit; j^=bit; if(i<j) std::swap(a[i],a[j]); }
 for(size_t len=2;len<=n;len<<=1){ double ang=-2*PI/len; std::complex<double> wl(std::cos(ang),std::sin(ang)); for(size_t i=0;i<n;i+=len){ std::complex<double>w(1); for(size_t j=0;j<len/2;j++){ auto u=a[i+j],v=a[i+j+len/2]*w;a[i+j]=u+v;a[i+j+len/2]=u-v;w*=wl; } } } }
double hzmel(double f){ return 2595.0*std::log10(1.0+f/700.0);} double melhz(double m){return 700.0*(std::pow(10.0,m/2595.0)-1.0);} }
BeatSpectrogram ComputeBeatSpectrogram(const std::vector<float>& s,double sr){ BeatSpectrogram r; if(std::abs(sr-kBeatSpectrogramSampleRate)>1.0||s.empty()) return r;
 const size_t pad=kBeatSpectrogramFft/2; const size_t frames=(s.size()+2*pad>=kBeatSpectrogramFft)?1+(s.size()+2*pad-kBeatSpectrogramFft)/kBeatSpectrogramHop:0; if(!frames) return r; r.frames=frames; r.values.assign(frames*kBeatSpectrogramMels,0);
 const double m0=hzmel(30),m1=hzmel(11000); std::vector<double> edge(kBeatSpectrogramMels+2); for(size_t i=0;i<edge.size();++i) edge[i]=melhz(m0+(m1-m0)*i/(edge.size()-1));
 std::vector<std::complex<double>> x(kBeatSpectrogramFft); std::vector<double> mag(kBeatSpectrogramFft/2+1);
 for(size_t f=0;f<frames;++f){ long start=(long)(f*kBeatSpectrogramHop)-(long)pad; for(size_t i=0;i<kBeatSpectrogramFft;++i){ long p=start+(long)i; double v=(p>=0&&p<(long)s.size())?s[p]:0; double win=0.5-0.5*std::cos(2*PI*i/kBeatSpectrogramFft); x[i]={v*win,0}; } fft(x); for(size_t k=0;k<mag.size();++k) mag[k]=std::abs(x[k]);
  for(size_t m=0;m<kBeatSpectrogramMels;++m){ double sum=0; for(size_t k=0;k<mag.size();++k){ double hz=k*sr/kBeatSpectrogramFft,w=0; if(hz>=edge[m]&&hz<=edge[m+1]) w=(hz-edge[m])/std::max(1e-9,edge[m+1]-edge[m]); else if(hz>edge[m+1]&&hz<=edge[m+2]) w=(edge[m+2]-hz)/std::max(1e-9,edge[m+2]-edge[m+1]); if(w>0) sum+=mag[k]*w; } r.values[f*kBeatSpectrogramMels+m]=(float)std::log1p(1000.0*sum); }
 } return r; }
}
