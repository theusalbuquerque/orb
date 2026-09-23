#include "audio_analysis.h"
#include "tempo_analysis.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <numeric>
namespace bitchord::smart { namespace { double clamp01(double x){return std::clamp(x,0.0,1.0);} }
AnalysisResult AnalyzeAudio(const std::vector<float>& s,double sr,double duration){AnalysisResult r; if(s.empty()||sr<=0)return r;r.duration=duration>0?duration:s.size()/sr;
 const size_t win=std::max<size_t>(128,(size_t)std::round(sr*0.20)),hop=std::max<size_t>(64,(size_t)std::round(sr*0.10)); std::vector<double> env,onset; double prev=0,lp=0; const double alpha=std::exp(-2*3.141592653589793*180.0/sr);
 for(size_t st=0;st<s.size();st+=hop){size_t en=std::min(s.size(),st+win);double e=0,le=0;for(size_t i=st;i<en;++i){double v=s[i];e+=v*v;lp=(1-alpha)*v+alpha*lp;le+=lp*lp;}e=std::sqrt(e/std::max<size_t>(1,en-st));le=std::sqrt(le/std::max<size_t>(1,en-st));double t=(st+(en-st)/2.0)/sr;env.push_back(e);r.energy_curve.push_back({t,e});r.low_energy_curve.push_back({t,le});const double non_low=std::sqrt(std::max(0.0,e*e-le*le));r.mid_energy_curve.push_back({t,non_low*0.72});r.high_energy_curve.push_back({t,non_low*0.28});onset.push_back(std::max(0.0,e-prev));prev=e;if(en==s.size())break;}
 if(env.empty())return r;auto sorted=env;std::sort(sorted.begin(),sorted.end());double ref=sorted[(size_t)((sorted.size()-1)*0.85)],thr=std::max(1e-6,ref*0.08); size_t first=0,last=env.size()-1;while(first<env.size()&&env[first]<thr)++first;while(last>first&&env[last]<thr)--last;r.audible_start_time=r.energy_curve[std::min(first,r.energy_curve.size()-1)].time;r.content_end_time=r.energy_curve[last].time;
 auto tempo=AnalyzeTempo(onset,hop/sr);r.bpm=tempo.bpm;r.beat_interval=tempo.interval;r.first_beat=tempo.first;r.beat_confidence=tempo.confidence;
 if(r.beat_interval>0){for(double t=r.first_beat;t<r.duration;t+=r.beat_interval*4)r.downbeats.push_back(t);for(double t=r.first_beat;t<r.duration;t+=r.beat_interval*16)r.phrase_boundaries.push_back(t);} 
 r.intro_end_time=std::min(r.duration,std::max(r.audible_start_time,r.first_beat+r.beat_interval*8));r.pickup_time=r.audible_start_time; r.outro_start_time=std::max(r.intro_end_time,r.content_end_time-std::max(8.0,r.beat_interval*16)); r.mix_in_time=r.audible_start_time;r.mix_out_time=r.outro_start_time;
 r.mix_in_candidates.push_back({r.mix_in_time,0.8,"audible_start"}); if(r.intro_end_time>r.mix_in_time+1)r.mix_in_candidates.push_back({r.intro_end_time,0.7,"intro_end"}); r.mix_out_candidates.push_back({r.mix_out_time,0.8,"outro_start"});r.mix_out_candidates.push_back({r.content_end_time,0.6,"content_end"});
 // Lightweight chroma proxy: accumulate energy at equal-tempered pitch frequencies.
 std::array<double,12> chroma{}; const double twoPi=6.283185307179586; size_t stride=std::max<size_t>(1,s.size()/std::max<size_t>(s.size(),(size_t)(sr*45))); for(int pc=0;pc<12;++pc){for(int oct=2;oct<=6;++oct){double f=440.0*std::pow(2.0,(pc-9+(oct-4)*12)/12.0);if(f>sr*0.45)continue;double c=0,si=0;for(size_t i=0;i<s.size();i+=stride){double a=twoPi*f*i/sr;c+=s[i]*std::cos(a);si+=s[i]*std::sin(a);}chroma[pc]+=c*c+si*si;}}
 static const char* names[]={"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};int best=0;for(int i=1;i<12;++i)if(chroma[i]>chroma[best])best=i;double total=std::accumulate(chroma.begin(),chroma.end(),0.0);r.chroma.assign(chroma.begin(),chroma.end());if(total>0){for(double& value:r.chroma)value/=total;}r.key=std::string(names[best])+" major";r.key_confidence=total>0?clamp01(chroma[best]/total*4):0;
 // Native heuristic only; TrackAnalyzer replaces/augments this with the vocal ONNX mask.
 r.vocal_activity_mask.assign(r.energy_curve.size(),0.5);r.vocal_probability=0.5;return r; }
}
