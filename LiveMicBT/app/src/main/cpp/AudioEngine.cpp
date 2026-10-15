#include "AudioEngine.h"
#include <android/log.h>
#include <thread>
#include <cstring>

#define LOG_TAG "LiveMicEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace livemic {

AudioEngine::AudioEngine() {
    inputReadBuffer_.resize(8192, 0.0f);
}

AudioEngine::~AudioEngine() {
    stopEngine();
}

bool AudioEngine::startEngine(
    int32_t inputDeviceId,
    int32_t outputDeviceId,
    bool sharedOutputMode,
    int32_t inputPreset,
    bool lowLatencyGameUsage
) {
    std::lock_guard<std::mutex> lock(streamLock_);
    currentInputDeviceId_ = inputDeviceId;
    currentOutputDeviceId_ = outputDeviceId;
    currentSharedOutputMode_ = sharedOutputMode;
    currentInputPreset_ = inputPreset;
    currentGameUsage_ = lowLatencyGameUsage;

    closeStreamsLocked();
    return openStreamsLocked();
}

void AudioEngine::stopEngine() {
    std::lock_guard<std::mutex> lock(streamLock_);
    closeStreamsLocked();
}

bool AudioEngine::reconfigureEngine(
    int32_t inputDeviceId,
    int32_t outputDeviceId,
    bool sharedOutputMode,
    int32_t inputPreset,
    bool lowLatencyGameUsage
) {
    std::lock_guard<std::mutex> lock(streamLock_);
    currentInputDeviceId_ = inputDeviceId;
    currentOutputDeviceId_ = outputDeviceId;
    currentSharedOutputMode_ = sharedOutputMode;
    currentInputPreset_ = inputPreset;
    currentGameUsage_ = lowLatencyGameUsage;

    if (!isRunning_.load(std::memory_order_relaxed)) {
        return true;
    }

    closeStreamsLocked();
    return openStreamsLocked();
}

bool AudioEngine::openStreamsLocked() {
    oboe::AudioStreamBuilder outBuilder;
    outBuilder.setDirection(oboe::Direction::Output)
        ->setFormat(oboe::AudioFormat::Float)
        ->setFormatConversionAllowed(true)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(currentSharedOutputMode_ ? oboe::SharingMode::Shared : oboe::SharingMode::Exclusive)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setUsage(currentGameUsage_ ? oboe::Usage::Game : oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    if (currentOutputDeviceId_ != oboe::kUnspecified && currentOutputDeviceId_ != 0) {
        outBuilder.setDeviceId(currentOutputDeviceId_);
    }

    oboe::Result result = outBuilder.openStream(outputStream_);
    if (result != oboe::Result::OK || !outputStream_) {
        LOGE("Failed to open output stream: %s", oboe::convertToText(result));
        return false;
    }

    const int32_t actualSampleRate = outputStream_->getSampleRate();
    dsp_.setSampleRate(static_cast<float>(actualSampleRate));

    outputStream_->setBufferSizeInFrames(outputStream_->getFramesPerBurst() * 2);

    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input)
        ->setFormat(oboe::AudioFormat::Float)
        ->setFormatConversionAllowed(true)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(actualSampleRate)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setInputPreset(static_cast<oboe::InputPreset>(currentInputPreset_))
        ->setErrorCallback(this);

    if (currentInputDeviceId_ != oboe::kUnspecified && currentInputDeviceId_ != 0) {
        inBuilder.setDeviceId(currentInputDeviceId_);
    }

    result = inBuilder.openStream(inputStream_);
    if (result != oboe::Result::OK || !inputStream_) {
        LOGE("Failed to open input stream: %s", oboe::convertToText(result));
        outputStream_->close();
        outputStream_.reset();
        return false;
    }

    inputStream_->setBufferSizeInFrames(inputStream_->getFramesPerBurst() * 2);
    settleDrainCallbacks_ = 3;

    result = inputStream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start input stream: %s", oboe::convertToText(result));
        closeStreamsLocked();
        return false;
    }

    result = outputStream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start output stream: %s", oboe::convertToText(result));
        closeStreamsLocked();
        return false;
    }

    isRunning_.store(true, std::memory_order_relaxed);
    LOGI("Full-duplex engine started at %d Hz (InDev=%d, OutDev=%d, Shared=%d, Preset=%d)",
         actualSampleRate, currentInputDeviceId_, currentOutputDeviceId_,
         currentSharedOutputMode_ ? 1 : 0, currentInputPreset_);
    return true;
}

void AudioEngine::closeStreamsLocked() {
    isRunning_.store(false, std::memory_order_relaxed);

    if (outputStream_) {
        outputStream_->requestStop();
        outputStream_->close();
        outputStream_.reset();
    }
    if (inputStream_) {
        inputStream_->requestStop();
        inputStream_->close();
        inputStream_.reset();
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
    oboe::AudioStream *outputStream,
    void *audioData,
    int32_t numFrames
) {
    auto *outFloat = static_cast<float *>(audioData);
    const int32_t outChannels = outputStream->getChannelCount();

    if (!inputStream_ || numFrames <= 0 || numFrames > static_cast<int32_t>(inputReadBuffer_.size())) {
        std::memset(outFloat, 0, sizeof(float) * numFrames * outChannels);
        return oboe::DataCallbackResult::Continue;
    }

    if (settleDrainCallbacks_ > 0) {
        int32_t drained = 0;
        do {
            auto drainRes = inputStream_->read(inputReadBuffer_.data(), numFrames, 0);
            drained = (drainRes == oboe::Result::OK) ? drainRes.value() : 0;
        } while (drained > 0);
        --settleDrainCallbacks_;
        std::memset(outFloat, 0, sizeof(float) * numFrames * outChannels);
        return oboe::DataCallbackResult::Continue;
    }

    oboe::ResultWithValue<int32_t> readResult = inputStream_->read(inputReadBuffer_.data(), numFrames, 0);
    int32_t framesRead = 0;
    if (readResult == oboe::Result::OK) {
        framesRead = readResult.value();
    }

    if (framesRead < numFrames) {
        std::memset(inputReadBuffer_.data() + framesRead, 0, sizeof(float) * (numFrames - framesRead));
    }

    dsp_.processBlock(inputReadBuffer_.data(), outFloat, numFrames, outChannels);
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream *oboeStream, oboe::Result error) {
    LOGE("AudioStream error after close: %s. Scheduling automatic stream recovery.", oboe::convertToText(error));
    if (isRunning_.load(std::memory_order_relaxed)) {
        std::thread([this]() {
            std::lock_guard<std::mutex> lock(streamLock_);
            closeStreamsLocked();
            openStreamsLocked();
        }).detach();
    }
}

double AudioEngine::getCurrentOutputLatencyMs() {
    std::lock_guard<std::mutex> lock(streamLock_);
    if (!outputStream_ || !isRunning_.load(std::memory_order_relaxed)) {
        return 0.0;
    }
    auto res = outputStream_->calculateLatencyMillis();
    if (res == oboe::Result::OK) {
        return res.value();
    }
    const double framesPerBurst = static_cast<double>(outputStream_->getFramesPerBurst());
    const double sr = static_cast<double>(outputStream_->getSampleRate());
    return (sr > 0.0) ? ((framesPerBurst * 2.0 * 1000.0) / sr) : 0.0;
}

} // namespace livemic
