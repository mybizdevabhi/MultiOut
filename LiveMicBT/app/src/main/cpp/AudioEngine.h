#pragma once

#include <oboe/Oboe.h>
#include <mutex>
#include <vector>
#include <atomic>
#include "DspPipeline.h"

namespace livemic {

class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    AudioEngine();
    ~AudioEngine() override;

    bool startEngine(
        int32_t inputDeviceId,
        int32_t outputDeviceId,
        bool sharedOutputMode,
        int32_t inputPreset,
        bool lowLatencyGameUsage
    );

    void stopEngine();

    bool reconfigureEngine(
        int32_t inputDeviceId,
        int32_t outputDeviceId,
        bool sharedOutputMode,
        int32_t inputPreset,
        bool lowLatencyGameUsage
    );

    bool isRunning() const noexcept {
        return isRunning_.load(std::memory_order_relaxed);
    }

    DspPipeline& dsp() noexcept {
        return dsp_;
    }

    double getCurrentOutputLatencyMs();

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream *outputStream,
        void *audioData,
        int32_t numFrames
    ) override;

    void onErrorAfterClose(
        oboe::AudioStream *oboeStream,
        oboe::Result error
    ) override;

private:
    bool openStreamsLocked();
    void closeStreamsLocked();

    std::mutex streamLock_;
    std::shared_ptr<oboe::AudioStream> inputStream_;
    std::shared_ptr<oboe::AudioStream> outputStream_;

    std::vector<float> inputReadBuffer_;
    DspPipeline dsp_;

    std::atomic<bool> isRunning_{false};
    int32_t currentInputDeviceId_ = oboe::kUnspecified;
    int32_t currentOutputDeviceId_ = oboe::kUnspecified;
    bool currentSharedOutputMode_ = true;
    int32_t currentInputPreset_ = static_cast<int32_t>(oboe::InputPreset::VoicePerformance);
    bool currentGameUsage_ = false;
    int32_t settleDrainCallbacks_ = 3;
};

} // namespace livemic
