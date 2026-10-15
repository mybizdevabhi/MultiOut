#pragma once

#include <atomic>
#include <cmath>
#include <algorithm>
#include <array>
#include <cstdint>

namespace livemic {

constexpr float kPi = 3.14159265358979323846f;
constexpr float kTwoPi = 6.28318530717958647692f;

enum class FilterType {
    HighPass,
    LowPass,
    Peaking,
    LowShelf,
    HighShelf,
    Notch
};

class BiquadFilter {
public:
    void reset() noexcept {
        z1_ = 0.0f;
        z2_ = 0.0f;
    }

    void configure(FilterType type, float freqHz, float sampleRate, float q, float gainDb) noexcept {
        if (sampleRate <= 0.0f) return;
        const float clampedFreq = std::clamp(freqHz, 15.0f, sampleRate * 0.45f);
        const float clampedQ = std::max(0.1f, q);
        const float A = std::pow(10.0f, gainDb / 40.0f);
        const float w0 = kTwoPi * clampedFreq / sampleRate;
        const float cosW0 = std::cos(w0);
        const float sinW0 = std::sin(w0);
        const float alpha = sinW0 / (2.0f * clampedQ);

        float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f;
        float a0 = 1.0f, a1 = 0.0f, a2 = 0.0f;

        switch (type) {
            case FilterType::HighPass:
                b0 = (1.0f + cosW0) * 0.5f;
                b1 = -(1.0f + cosW0);
                b2 = (1.0f + cosW0) * 0.5f;
                a0 = 1.0f + alpha;
                a1 = -2.0f * cosW0;
                a2 = 1.0f - alpha;
                break;

            case FilterType::LowPass:
                b0 = (1.0f - cosW0) * 0.5f;
                b1 = 1.0f - cosW0;
                b2 = (1.0f - cosW0) * 0.5f;
                a0 = 1.0f + alpha;
                a1 = -2.0f * cosW0;
                a2 = 1.0f - alpha;
                break;

            case FilterType::Peaking:
                b0 = 1.0f + alpha * A;
                b1 = -2.0f * cosW0;
                b2 = 1.0f - alpha * A;
                a0 = 1.0f + alpha / A;
                a1 = -2.0f * cosW0;
                a2 = 1.0f - alpha / A;
                break;

            case FilterType::Notch:
                b0 = 1.0f;
                b1 = -2.0f * cosW0;
                b2 = 1.0f;
                a0 = 1.0f + alpha;
                a1 = -2.0f * cosW0;
                a2 = 1.0f - alpha;
                break;

            case FilterType::LowShelf: {
                const float sqrtA = std::sqrt(A);
                const float twoSqrtAAlpha = 2.0f * sqrtA * alpha;
                b0 = A * ((A + 1.0f) - (A - 1.0f) * cosW0 + twoSqrtAAlpha);
                b1 = 2.0f * A * ((A - 1.0f) - (A + 1.0f) * cosW0);
                b2 = A * ((A + 1.0f) - (A - 1.0f) * cosW0 - twoSqrtAAlpha);
                a0 = (A + 1.0f) + (A - 1.0f) * cosW0 + twoSqrtAAlpha;
                a1 = -2.0f * ((A - 1.0f) + (A + 1.0f) * cosW0);
                a2 = (A + 1.0f) + (A - 1.0f) * cosW0 - twoSqrtAAlpha;
                break;
            }

            case FilterType::HighShelf: {
                const float sqrtA = std::sqrt(A);
                const float twoSqrtAAlpha = 2.0f * sqrtA * alpha;
                b0 = A * ((A + 1.0f) + (A - 1.0f) * cosW0 + twoSqrtAAlpha);
                b1 = -2.0f * A * ((A - 1.0f) + (A + 1.0f) * cosW0);
                b2 = A * ((A + 1.0f) + (A - 1.0f) * cosW0 - twoSqrtAAlpha);
                a0 = (A + 1.0f) - (A - 1.0f) * cosW0 + twoSqrtAAlpha;
                a1 = 2.0f * ((A - 1.0f) - (A + 1.0f) * cosW0);
                a2 = (A + 1.0f) - (A - 1.0f) * cosW0 - twoSqrtAAlpha;
                break;
            }
        }

        const float invA0 = 1.0f / a0;
        b0_ = b0 * invA0;
        b1_ = b1 * invA0;
        b2_ = b2 * invA0;
        a1_ = a1 * invA0;
        a2_ = a2 * invA0;
    }

    inline float process(float x) noexcept {
        const float y = b0_ * x + z1_;
        z1_ = b1_ * x - a1_ * y + z2_;
        z2_ = b2_ * x - a2_ * y;
        return y;
    }

private:
    float b0_ = 1.0f, b1_ = 0.0f, b2_ = 0.0f;
    float a1_ = 0.0f, a2_ = 0.0f;
    float z1_ = 0.0f, z2_ = 0.0f;
};

class DspPipeline {
public:
    DspPipeline() {
        updateCoefficients(48000.0f);
    }

    void setSampleRate(float sampleRate) noexcept {
        if (sampleRate > 8000.0f) {
            sampleRate_ = sampleRate;
            coeffsDirty_.store(true, std::memory_order_relaxed);
        }
    }

    void setInputGainDb(float db) noexcept {
        inputGainLinear_.store(std::pow(10.0f, std::clamp(db, -24.0f, 30.0f) / 20.0f), std::memory_order_relaxed);
    }

    void setHighPassCutoffHz(float hz) noexcept {
        hpfCutoffHz_.store(std::clamp(hz, 40.0f, 250.0f), std::memory_order_relaxed);
        coeffsDirty_.store(true, std::memory_order_relaxed);
    }

    void setNoiseGateThresholdDb(float db) noexcept {
        gateThresholdDb_.store(std::clamp(db, -75.0f, -15.0f), std::memory_order_relaxed);
    }

    void setSpeechDenoiseMode(bool speechMode) noexcept {
        speechDenoiseMode_.store(speechMode, std::memory_order_relaxed);
    }

    void setAntiFeedbackEnabled(bool enabled) noexcept {
        antiFeedbackEnabled_.store(enabled, std::memory_order_relaxed);
    }

    void setNormalizerEnabled(bool enabled) noexcept {
        normalizerEnabled_.store(enabled, std::memory_order_relaxed);
    }

    void setVoiceFocusEnabled(bool enabled) noexcept {
        voiceFocusEnabled_.store(enabled, std::memory_order_relaxed);
    }

    void setDuckingAttenuation(float linearGain) noexcept {
        duckingGain_.store(std::clamp(linearGain, 0.0f, 1.0f), std::memory_order_relaxed);
    }

    void setEqBandGainDb(int bandIndex, float gainDb) noexcept {
        if (bandIndex >= 0 && bandIndex < 5) {
            eqBandGainsDb_[bandIndex].store(std::clamp(gainDb, -12.0f, 12.0f), std::memory_order_relaxed);
            coeffsDirty_.store(true, std::memory_order_relaxed);
        }
    }

    float getInputRms() const noexcept {
        return lastInputRms_.load(std::memory_order_relaxed);
    }

    float getOutputRms() const noexcept {
        return lastOutputRms_.load(std::memory_order_relaxed);
    }

    float getGainReductionDb() const noexcept {
        return lastGainReductionDb_.load(std::memory_order_relaxed);
    }

    void processBlock(const float* inputMono, float* outputInterleaved, int32_t numFrames, int32_t outChannels) noexcept {
        if (coeffsDirty_.exchange(false, std::memory_order_relaxed)) {
            updateCoefficients(sampleRate_);
        }

        const float inGain = inputGainLinear_.load(std::memory_order_relaxed);
        const float duckGain = duckingGain_.load(std::memory_order_relaxed);
        const float gateThreshLin = std::pow(10.0f, gateThresholdDb_.load(std::memory_order_relaxed) / 20.0f);
        const bool isSpeechMode = speechDenoiseMode_.load(std::memory_order_relaxed);
        const bool antiFeedback = antiFeedbackEnabled_.load(std::memory_order_relaxed);
        const bool normalize = normalizerEnabled_.load(std::memory_order_relaxed);
        const bool voiceFocus = voiceFocusEnabled_.load(std::memory_order_relaxed);

        const float gateAttack = 1.0f - std::exp(-1.0f / (0.0015f * sampleRate_));
        const float gateRelease = 1.0f - std::exp(-1.0f / ((isSpeechMode ? 0.045f : 0.140f) * sampleRate_));

        const float compAttack = 1.0f - std::exp(-1.0f / (0.005f * sampleRate_));
        const float compRelease = 1.0f - std::exp(-1.0f / (0.080f * sampleRate_));

        const float phaseStep = kTwoPi * 3.5f / sampleRate_;

        float sumSqIn = 0.0f;
        float sumSqOut = 0.0f;
        float minCompGain = 1.0f;

        for (int32_t i = 0; i < numFrames; ++i) {
            float sample = inputMono[i] * inGain;
            sumSqIn += sample * sample;

            sample = hpfFilter_.process(sample);

            const float absSample = std::fabs(sample);
            const float gateCoeff = (absSample > gateEnvelope_) ? gateAttack : gateRelease;
            gateEnvelope_ += gateCoeff * (absSample - gateEnvelope_);

            float gateGain = 1.0f;
            if (gateEnvelope_ < gateThreshLin && gateThreshLin > 1e-6f) {
                const float ratio = gateEnvelope_ / gateThreshLin;
                gateGain = isSpeechMode ? (ratio * ratio * ratio) : std::sqrt(ratio);
            }
            sample *= gateGain;

            if (antiFeedback) {
                sample = feedbackNotchLow_.process(sample);
                sample = feedbackNotchMid_.process(sample);
                sample = feedbackNotchHigh_.process(sample);

                shifterPhase_ += phaseStep;
                if (shifterPhase_ > kTwoPi) shifterPhase_ -= kTwoPi;
                sample *= (0.97f + 0.03f * std::cos(shifterPhase_));
            }

            if (normalize) {
                const float envIn = std::fabs(sample);
                const float compCoeff = (envIn > compEnvelope_) ? compAttack : compRelease;
                compEnvelope_ += compCoeff * (envIn - compEnvelope_);

                constexpr float compThreshold = 0.125f;
                float compGain = 1.0f;
                if (compEnvelope_ > compThreshold) {
                    const float envDb = 20.0f * std::log10(compEnvelope_ + 1e-7f);
                    const float threshDb = -18.0f;
                    const float overDb = envDb - threshDb;
                    const float compressedDb = threshDb + overDb * 0.25f;
                    const float gainDb = compressedDb - envDb + 4.5f;
                    compGain = std::pow(10.0f, gainDb / 20.0f);
                } else {
                    compGain = 1.678f;
                }
                minCompGain = std::min(minCompGain, compGain);
                sample *= compGain;
            }

            for (int b = 0; b < 5; ++b) {
                sample = eqBands_[b].process(sample);
            }

            if (voiceFocus) {
                sample = vfMudCut_.process(sample);
                sample = vfPresenceBoost_.process(sample);
                sample = vfHighRollOff_.process(sample);
            }

            sample *= duckGain;
            sample = std::tanh(sample * 1.05f) * 0.944f;

            sumSqOut += sample * sample;

            if (outChannels == 2) {
                outputInterleaved[i * 2] = sample;
                outputInterleaved[i * 2 + 1] = sample;
            } else {
                outputInterleaved[i] = sample;
            }
        }

        if (numFrames > 0) {
            lastInputRms_.store(std::sqrt(sumSqIn / static_cast<float>(numFrames)), std::memory_order_relaxed);
            lastOutputRms_.store(std::sqrt(sumSqOut / static_cast<float>(numFrames)), std::memory_order_relaxed);
            const float grDb = (minCompGain < 1.678f) ? (20.0f * std::log10(minCompGain / 1.678f)) : 0.0f;
            lastGainReductionDb_.store(grDb, std::memory_order_relaxed);
        }
    }

private:
    void updateCoefficients(float sr) noexcept {
        hpfFilter_.configure(
            FilterType::HighPass,
            hpfCutoffHz_.load(std::memory_order_relaxed),
            sr,
            0.7071f,
            0.0f
        );

        feedbackNotchLow_.configure(FilterType::Notch, 880.0f, sr, 10.0f, 0.0f);
        feedbackNotchMid_.configure(FilterType::Notch, 2350.0f, sr, 12.0f, 0.0f);
        feedbackNotchHigh_.configure(FilterType::Notch, 4100.0f, sr, 12.0f, 0.0f);

        constexpr std::array<float, 5> freqs = {100.0f, 350.0f, 1200.0f, 3500.0f, 8000.0f};
        for (int i = 0; i < 5; ++i) {
            FilterType type = FilterType::Peaking;
            if (i == 0) type = FilterType::LowShelf;
            if (i == 4) type = FilterType::HighShelf;
            eqBands_[i].configure(
                type,
                freqs[i],
                sr,
                1.0f,
                eqBandGainsDb_[i].load(std::memory_order_relaxed)
            );
        }

        vfMudCut_.configure(FilterType::Peaking, 300.0f, sr, 1.4f, -3.5f);
        vfPresenceBoost_.configure(FilterType::Peaking, 2800.0f, sr, 1.1f, 4.5f);
        vfHighRollOff_.configure(FilterType::LowPass, 8500.0f, sr, 0.7071f, 0.0f);
    }

    float sampleRate_ = 48000.0f;
    std::atomic<bool> coeffsDirty_{true};

    std::atomic<float> inputGainLinear_{1.58f};
    std::atomic<float> hpfCutoffHz_{90.0f};
    std::atomic<float> gateThresholdDb_{-45.0f};
    std::atomic<bool> speechDenoiseMode_{false};
    std::atomic<bool> antiFeedbackEnabled_{true};
    std::atomic<bool> normalizerEnabled_{true};
    std::atomic<bool> voiceFocusEnabled_{false};
    std::atomic<float> duckingGain_{1.0f};
    std::array<std::atomic<float>, 5> eqBandGainsDb_{0.0f, 0.0f, 0.0f, 0.0f, 0.0f};

    std::atomic<float> lastInputRms_{0.0f};
    std::atomic<float> lastOutputRms_{0.0f};
    std::atomic<float> lastGainReductionDb_{0.0f};

    float gateEnvelope_ = 0.0f;
    float compEnvelope_ = 0.0f;
    float shifterPhase_ = 0.0f;

    BiquadFilter hpfFilter_;
    BiquadFilter feedbackNotchLow_;
    BiquadFilter feedbackNotchMid_;
    BiquadFilter feedbackNotchHigh_;
    std::array<BiquadFilter, 5> eqBands_;
    BiquadFilter vfMudCut_;
    BiquadFilter vfPresenceBoost_;
    BiquadFilter vfHighRollOff_;
};

} // namespace livemic
