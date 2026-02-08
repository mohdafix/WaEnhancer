#include "VoiceProcessor.h"
#include <android/log.h>
#include <jni.h>
#include <string>
#include <vector>


#define LOG_TAG "VoiceChangerJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace voicechanger;

// Global processor instance
static VoiceProcessor *g_processor = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_wmods_wppenhacer_xposed_features_others_VoiceChanger_nativeInit(
    JNIEnv *env, jclass clazz) {

  if (g_processor == nullptr) {
    g_processor = new VoiceProcessor();
    LOGD("VoiceProcessor initialized");
  }
  return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_wmods_wppenhacer_xposed_features_others_VoiceChanger_nativeRelease(
    JNIEnv *env, jclass clazz) {

  if (g_processor != nullptr) {
    delete g_processor;
    g_processor = nullptr;
    LOGD("VoiceProcessor released");
  }
}

JNIEXPORT void JNICALL
Java_com_wmods_wppenhacer_xposed_features_others_VoiceChanger_nativeSetEffect(
    JNIEnv *env, jclass clazz, jint effectType) {

  if (g_processor != nullptr) {
    g_processor->setEffect(static_cast<VoiceEffect>(effectType));
    LOGD("Effect set to: %d", effectType);
  }
}

JNIEXPORT void JNICALL
Java_com_wmods_wppenhacer_xposed_features_others_VoiceChanger_nativeSetCustomParams(
    JNIEnv *env, jclass clazz, jfloat tempo, jfloat pitch, jfloat speed) {

  if (g_processor != nullptr) {
    g_processor->setCustomParams(tempo, pitch, speed);
    LOGD("Custom params: tempo=%.2f, pitch=%.2f, speed=%.2f", tempo, pitch,
         speed);
  }
}

JNIEXPORT jshortArray JNICALL
Java_com_wmods_wppenhacer_xposed_features_others_VoiceChanger_nativeProcessAudio(
    JNIEnv *env, jclass clazz, jshortArray inputArray, jint sampleRate) {

  if (g_processor == nullptr) {
    LOGE("Processor not initialized!");
    return nullptr;
  }

  // Get input data
  jsize inputSize = env->GetArrayLength(inputArray);
  jshort *inputData = env->GetShortArrayElements(inputArray, nullptr);

  if (inputData == nullptr) {
    LOGE("Failed to get input array elements");
    return nullptr;
  }

  LOGD("Processing %d samples", inputSize);

  // Process audio
  std::vector<int16_t> output;
  bool success = g_processor->process(
      reinterpret_cast<const int16_t *>(inputData),
      static_cast<size_t>(inputSize), output, static_cast<int>(sampleRate));

  // Release input array
  env->ReleaseShortArrayElements(inputArray, inputData, JNI_ABORT);

  if (!success || output.empty()) {
    LOGE("Processing failed or empty output");
    return nullptr;
  }

  // Create output array
  jshortArray outputArray =
      env->NewShortArray(static_cast<jsize>(output.size()));
  if (outputArray == nullptr) {
    LOGE("Failed to create output array");
    return nullptr;
  }

  env->SetShortArrayRegion(outputArray, 0, static_cast<jsize>(output.size()),
                           reinterpret_cast<const jshort *>(output.data()));

  LOGD("Processed successfully: %zu samples output", output.size());
  return outputArray;
}

JNIEXPORT jboolean JNICALL
Java_com_wmods_wppenhacer_xposed_features_others_VoiceChanger_nativeIsEnabled(
    JNIEnv *env, jclass clazz) {

  if (g_processor != nullptr) {
    return g_processor->isEnabled() ? JNI_TRUE : JNI_FALSE;
  }
  return JNI_FALSE;
}

} // extern "C"
