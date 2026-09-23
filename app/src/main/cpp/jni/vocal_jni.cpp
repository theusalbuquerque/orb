/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard).
 *
 * Copyright (C) 2026 SFG545 (original Orchard implementation)
 * Copyright (C) 2026 Kushagra Singh (BitChord adaptation)
 *
 * Orchard's original source is licensed under the GNU Affero General Public
 * License, version 3 or later. Per AGPLv3 section 13, this file is combined
 * here into BitChord -- a work licensed under the GNU General Public
 * License, version 3 or later -- and remains itself governed by the AGPLv3
 * as part of that combination.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

// JNI bridge to the vocal-separation front end.
//
// Unlike the mel front end this one is stereo and linear-frequency, because
// that is what open-unmix was trained on. The layout it produces is bin-major
// rather than frame-major specifically so it matches the model's tensor shape
// [1, 2, bins, frames] with no transpose on the Kotlin side.

#include <jni.h>

#include <vector>

#include "analyzer/vocal_spectrogram.h"

extern "C" {

// Takes the two channels as separate arrays rather than one interleaved one,
// mirroring the planar layout the front end wants and avoiding a deinterleave
// on either side of the boundary.
JNIEXPORT jfloatArray JNICALL
Java_com_music_orb_playback_smart_VocalSpectrogram_nativeCompute(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray left,
    jfloatArray right,
    jdouble sample_rate) {
  const jsize left_count = env->GetArrayLength(left);
  const jsize right_count = env->GetArrayLength(right);

  std::vector<std::vector<float>> channels(2);
  channels[0].resize(static_cast<size_t>(left_count));
  channels[1].resize(static_cast<size_t>(right_count));
  if (left_count > 0) env->GetFloatArrayRegion(left, 0, left_count, channels[0].data());
  if (right_count > 0) env->GetFloatArrayRegion(right, 0, right_count, channels[1].data());

  const bitchord::smart::VocalSpectrogram spectrogram =
      bitchord::smart::ComputeVocalSpectrogram(channels, sample_rate);

  const jsize produced = static_cast<jsize>(spectrogram.values.size());
  jfloatArray result = env->NewFloatArray(produced);
  if (result == nullptr) return nullptr;
  if (produced > 0) {
    env->SetFloatArrayRegion(result, 0, produced, spectrogram.values.data());
  }
  return result;
}

JNIEXPORT jint JNICALL
Java_com_music_orb_playback_smart_VocalSpectrogram_nativeBins(
    JNIEnv* /* env */, jclass /* clazz */) {
  return static_cast<jint>(bitchord::smart::kVocalSpectrogramBins);
}

JNIEXPORT jdouble JNICALL
Java_com_music_orb_playback_smart_VocalSpectrogram_nativeSampleRate(
    JNIEnv* /* env */, jclass /* clazz */) {
  return bitchord::smart::kVocalSpectrogramSampleRate;
}

JNIEXPORT jint JNICALL
Java_com_music_orb_playback_smart_VocalSpectrogram_nativeHop(
    JNIEnv* /* env */, jclass /* clazz */) {
  return static_cast<jint>(bitchord::smart::kVocalSpectrogramHop);
}

JNIEXPORT jint JNICALL
Java_com_music_orb_playback_smart_VocalSpectrogram_nativeFftSize(
    JNIEnv* /* env */, jclass /* clazz */) {
  return static_cast<jint>(bitchord::smart::kVocalSpectrogramFft);
}

}  // extern "C"

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_music_orb_playback_smart_VocalSpectrogram_nativeReconstructVocals(
    JNIEnv* env,
    jclass /* clazz */,
    jfloatArray left,
    jfloatArray right,
    jdouble sample_rate,
    jobject target_buffer,
    jint model_frames,
    jint usable_frames) {
  if (target_buffer == nullptr || model_frames <= 0 || usable_frames <= 0) return nullptr;
  auto* target = static_cast<float*>(env->GetDirectBufferAddress(target_buffer));
  if (target == nullptr) return nullptr;

  const jsize left_count = env->GetArrayLength(left);
  const jsize right_count = env->GetArrayLength(right);
  std::vector<std::vector<float>> channels(2);
  channels[0].resize(static_cast<size_t>(left_count));
  channels[1].resize(static_cast<size_t>(right_count));
  if (left_count > 0) env->GetFloatArrayRegion(left, 0, left_count, channels[0].data());
  if (right_count > 0) env->GetFloatArrayRegion(right, 0, right_count, channels[1].data());

  const auto stems = bitchord::smart::ReconstructVocalStem(
      channels,
      sample_rate,
      target,
      static_cast<size_t>(model_frames),
      static_cast<size_t>(usable_frames));
  if (stems.size() < 2) return nullptr;

  jclass float_array_class = env->FindClass("[F");
  if (float_array_class == nullptr) return nullptr;
  jobjectArray result = env->NewObjectArray(2, float_array_class, nullptr);
  if (result == nullptr) return nullptr;

  for (jsize channel = 0; channel < 2; ++channel) {
    const auto& values = stems[static_cast<size_t>(channel)];
    jfloatArray array = env->NewFloatArray(static_cast<jsize>(values.size()));
    if (array == nullptr) return nullptr;
    if (!values.empty()) {
      env->SetFloatArrayRegion(array, 0, static_cast<jsize>(values.size()), values.data());
    }
    env->SetObjectArrayElement(result, channel, array);
    env->DeleteLocalRef(array);
  }
  return result;
}
