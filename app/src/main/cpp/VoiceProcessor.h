#ifndef VOICE_PROCESSOR_H
#define VOICE_PROCESSOR_H

#include <vector>
#include <cstdint>
#include <string>

namespace voicechanger {

/**
 * Voice effect types matching Fouad WhatsApp presets
 */
enum class VoiceEffect {
    DISABLED = 0,
    BABY = 1,
    TEENAGER = 2,
    DEEP = 3,
    ROBOT = 4,
    DRUNK = 5,
    FAST = 6,
    SLOW_MOTION = 7,
    UNDERWATER = 8,
    FUN = 9
};

/**
 * Effect parameters
 */
struct EffectParams {
    float tempo;      // Time stretch factor (1.0 = normal)
    float pitch;      // Pitch shift in semitones
    float speed;      // Playback speed factor
};

/**
 * VoiceProcessor - Processes audio samples with pitch/tempo effects
 * 
 * Uses STFT-based pitch shifting algorithm for real-time voice modification.
 */
class VoiceProcessor {
public:
    VoiceProcessor();
    ~VoiceProcessor();

    /**
     * Set the current voice effect
     */
    void setEffect(VoiceEffect effect);

    /**
     * Set custom effect parameters
     */
    void setCustomParams(float tempo, float pitchSemitones, float speed);

    /**
     * Process audio samples
     * @param input Input PCM samples (16-bit signed, mono)
     * @param inputSize Number of input samples
     * @param output Output buffer (will be resized as needed)
     * @param sampleRate Audio sample rate (typically 48000 for Opus)
     * @return true if processing succeeded
     */
    bool process(const int16_t* input, size_t inputSize,
                 std::vector<int16_t>& output, int sampleRate);

    /**
     * Get the current effect parameters
     */
    EffectParams getParams() const { return params_; }

    /**
     * Check if effect is enabled
     */
    bool isEnabled() const { return effect_ != VoiceEffect::DISABLED; }

private:
    VoiceEffect effect_;
    EffectParams params_;

    // Internal processing buffers
    std::vector<float> inputBuffer_;
    std::vector<float> outputBuffer_;

    // STFT parameters
    static constexpr int FFT_SIZE = 2048;
    static constexpr int HOP_SIZE = 512;
    static constexpr int OVERLAP = 4;

    void updateParamsForEffect(VoiceEffect effect);
    float semitonesToFactor(float semitones);
};

} // namespace voicechanger

#endif // VOICE_PROCESSOR_H
