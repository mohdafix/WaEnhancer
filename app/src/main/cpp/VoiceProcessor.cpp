#include "VoiceProcessor.h"
#include <algorithm>
#include <android/log.h>
#include <cmath>


#define LOG_TAG "VoiceChanger"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace voicechanger {

VoiceProcessor::VoiceProcessor()
    : effect_(VoiceEffect::DISABLED), params_{1.0f, 0.0f, 1.0f} {}

VoiceProcessor::~VoiceProcessor() = default;

void VoiceProcessor::setEffect(VoiceEffect effect) {
  effect_ = effect;
  updateParamsForEffect(effect);
}

void VoiceProcessor::setCustomParams(float tempo, float pitchSemitones,
                                     float speed) {
  params_.tempo = tempo;
  params_.pitch = pitchSemitones;
  params_.speed = speed;
}

void VoiceProcessor::updateParamsForEffect(VoiceEffect effect) {
  // Effect presets matching Fouad WhatsApp
  switch (effect) {
  case VoiceEffect::DISABLED:
    params_ = {1.0f, 0.0f, 1.0f};
    break;
  case VoiceEffect::BABY:
    params_ = {1.0f, 12.0f, 1.0f}; // High pitch
    break;
  case VoiceEffect::TEENAGER:
    params_ = {1.0f, 4.0f, 1.0f}; // Slightly high pitch
    break;
  case VoiceEffect::DEEP:
    params_ = {1.0f, -8.0f, 1.0f}; // Low pitch
    break;
  case VoiceEffect::ROBOT:
    params_ = {0.6f, -6.0f, 0.8f}; // Metallic effect
    break;
  case VoiceEffect::DRUNK:
    params_ = {0.6f, 0.0f, 0.9f}; // Slurred speech
    break;
  case VoiceEffect::FAST:
    params_ = {1.33f, 0.0f, 1.0f}; // Chipmunk speed
    break;
  case VoiceEffect::SLOW_MOTION:
    params_ = {0.33f, 0.0f, 1.0f}; // Slow motion
    break;
  case VoiceEffect::UNDERWATER:
    params_ = {1.25f, -12.0f, 1.0f}; // Muffled underwater
    break;
  case VoiceEffect::FUN:
    params_ = {1.0f, 16.0f, 1.0f}; // Very high pitch
    break;
  }
  LOGD("Effect set: tempo=%.2f, pitch=%.2f, speed=%.2f", params_.tempo,
       params_.pitch, params_.speed);
}

float VoiceProcessor::semitonesToFactor(float semitones) {
  // Convert semitones to pitch shift factor
  // 12 semitones = 1 octave = 2x frequency
  return std::pow(2.0f, semitones / 12.0f);
}

bool VoiceProcessor::process(const int16_t *input, size_t inputSize,
                             std::vector<int16_t> &output, int sampleRate) {
  if (!isEnabled() || inputSize == 0) {
    // No processing needed, copy input to output
    output.assign(input, input + inputSize);
    return true;
  }

  LOGD("Processing %zu samples at %d Hz", inputSize, sampleRate);

  // Convert input to float [-1.0, 1.0]
  inputBuffer_.resize(inputSize);
  for (size_t i = 0; i < inputSize; ++i) {
    inputBuffer_[i] = static_cast<float>(input[i]) / 32768.0f;
  }

  // Calculate pitch shift factor
  float pitchFactor = semitonesToFactor(params_.pitch);

  // Calculate output size based on tempo
  size_t outputSize = static_cast<size_t>(inputSize / params_.tempo);
  outputBuffer_.resize(outputSize);

  // === STFT-based Pitch Shifting ===
  // This is a simplified implementation. For production, use the full
  // stftPitchShift library.

  const int fftSize = FFT_SIZE;
  const int hopSize = HOP_SIZE;
  const int numFrames = (inputSize - fftSize) / hopSize + 1;

  if (numFrames <= 0) {
    // Audio too short for processing, just apply simple pitch shift
    for (size_t i = 0; i < outputSize; ++i) {
      size_t srcIdx = static_cast<size_t>(i * params_.tempo);
      if (srcIdx < inputSize) {
        outputBuffer_[i] = inputBuffer_[srcIdx];
      } else {
        outputBuffer_[i] = 0.0f;
      }
    }
  } else {
    // Simple time-domain pitch shifting (resampling)
    // For proper formant preservation, use full STFT implementation
    float resampleRatio = pitchFactor * params_.speed;
    size_t newSize = static_cast<size_t>(inputSize / resampleRatio);
    outputBuffer_.resize(newSize);

    for (size_t i = 0; i < newSize; ++i) {
      float srcPos = i * resampleRatio;
      size_t srcIdx = static_cast<size_t>(srcPos);
      float frac = srcPos - srcIdx;

      if (srcIdx + 1 < inputSize) {
        // Linear interpolation
        outputBuffer_[i] = inputBuffer_[srcIdx] * (1.0f - frac) +
                           inputBuffer_[srcIdx + 1] * frac;
      } else if (srcIdx < inputSize) {
        outputBuffer_[i] = inputBuffer_[srcIdx];
      } else {
        outputBuffer_[i] = 0.0f;
      }
    }
  }

  // Convert back to int16
  output.resize(outputBuffer_.size());
  for (size_t i = 0; i < outputBuffer_.size(); ++i) {
    float sample = outputBuffer_[i];
    // Clamp to [-1.0, 1.0]
    sample = std::max(-1.0f, std::min(1.0f, sample));
    output[i] = static_cast<int16_t>(sample * 32767.0f);
  }

  LOGD("Processed: input=%zu, output=%zu samples", inputSize, output.size());
  return true;
}

} // namespace voicechanger
