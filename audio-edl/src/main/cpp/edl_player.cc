#include "edl_player.h"

#include <algorithm>
#include <cstring>
#include <fstream>

#ifdef __ANDROID__
#include <oboe/Oboe.h>
#endif

namespace voicestudio::edl {

#ifdef __ANDROID__
struct EdlPlayer::AndroidStreamState {
    std::shared_ptr<oboe::AudioStream> stream;
};
#endif

EdlPlayer::EdlPlayer()
#ifdef __ANDROID__
    : android_stream_(std::make_unique<AndroidStreamState>())
#endif
{}

EdlPlayer::~EdlPlayer() {
    Close();
}

bool EdlPlayer::Init(const std::string& room_tone_loop_path, std::string* error_out) {
    if (room_tone_loop_path.empty()) {
        if (error_out) {
            *error_out = "room_tone_loop_path must not be empty";
        }
        return false;
    }

    std::ifstream probe(room_tone_loop_path, std::ios::binary);
    if (!probe.good()) {
        if (error_out) {
            *error_out = "Unable to open room tone PCM file: " + room_tone_loop_path;
        }
        return false;
    }

    room_tone_path_ = room_tone_loop_path;
#ifdef __ANDROID__
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(kSampleRateHz);
    (void)builder;
#endif
    return true;
}

bool EdlPlayer::Start(std::string* error_out) {
    if (room_tone_path_.empty()) {
        if (error_out) {
            *error_out = "EdlPlayer::Init must succeed before Start()";
        }
        return false;
    }
    playing_.store(true, std::memory_order_release);
    return true;
}

void EdlPlayer::Pause() {
    playing_.store(false, std::memory_order_release);
}

void EdlPlayer::Close() {
    Pause();
#ifdef __ANDROID__
    if (android_stream_ && android_stream_->stream) {
        android_stream_->stream->close();
        android_stream_->stream.reset();
    }
#endif
    EdlSnapshot* prior = active_snapshot_.exchange(nullptr, std::memory_order_acq_rel);
    if (prior != nullptr) {
        retired_snapshots_.emplace_back(prior);
    }
    retired_snapshots_.clear();
}

void EdlPlayer::UpdateEdlSnapshot(EdlSnapshot* new_snapshot) {
    EdlSnapshot* prior = active_snapshot_.exchange(new_snapshot, std::memory_order_acq_rel);
    if (prior != nullptr) {
        retired_snapshots_.emplace_back(prior);
    }
}

void EdlPlayer::SeekToSample(int64_t timeline_sample) {
    playhead_sample_.store(std::max<int64_t>(0, timeline_sample), std::memory_order_release);
}

int64_t EdlPlayer::GetCurrentSample() const {
    return playhead_sample_.load(std::memory_order_acquire);
}

int32_t EdlPlayer::GetXRunCount() const {
#ifdef __ANDROID__
    if (android_stream_ && android_stream_->stream) {
        auto count = android_stream_->stream->getXRunCount();
        if (count) {
            return count.value();
        }
    }
#endif
    return xrun_count_.load(std::memory_order_acquire);
}

bool EdlPlayer::IsPlaying() const {
    return playing_.load(std::memory_order_acquire);
}

void EdlPlayer::RenderFrames(float* out_mono, int32_t num_frames) {
    if (out_mono == nullptr || num_frames <= 0) {
        return;
    }
    std::memset(out_mono, 0, static_cast<size_t>(num_frames) * sizeof(float));
    if (!playing_.load(std::memory_order_acquire)) {
        return;
    }
    const EdlSnapshot* snapshot = active_snapshot_.load(std::memory_order_acquire);
    if (snapshot == nullptr || snapshot->total_timeline_samples <= 0) {
        return;
    }
    playhead_sample_.fetch_add(num_frames, std::memory_order_acq_rel);
}

}  // namespace voicestudio::edl
