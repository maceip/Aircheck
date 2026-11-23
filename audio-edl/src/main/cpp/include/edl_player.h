#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace voicestudio::edl {

constexpr int32_t kSampleRateHz = 44100;
constexpr int32_t kCrossfadeSamples = 660;  // 15 ms equal-power cosine @ 44.1 kHz

struct AudioSlice {
    std::string pcm_file_path;
    const float* mmap_data = nullptr;
    int64_t total_samples = 0;
    int64_t zero_cross_in_sample = 0;
    int64_t zero_cross_out_sample = 0;
    int32_t gap_after_samples = 0;
};

struct EdlSnapshot {
    uint64_t version = 0;
    float global_gap_scale = 1.0f;
    std::vector<AudioSlice> slices;
    int64_t total_timeline_samples = 0;
};

class EdlPlayer {
public:
    EdlPlayer();
    ~EdlPlayer();

    EdlPlayer(const EdlPlayer&) = delete;
    EdlPlayer& operator=(const EdlPlayer&) = delete;

    bool Init(const std::string& room_tone_loop_path, std::string* error_out = nullptr);
    bool Start(std::string* error_out = nullptr);
    void Pause();
    void Close();

    // Wait-free atomic snapshot publication (audio callback never locks or deletes).
    void UpdateEdlSnapshot(EdlSnapshot* new_snapshot);

    void SeekToSample(int64_t timeline_sample);
    int64_t GetCurrentSample() const;
    int32_t GetXRunCount() const;
    bool IsPlaying() const;

    // Real-time-safe audio renderer invoked by Oboe callback (Android) or offline CTest (host).
    void RenderFrames(float* out_mono, int32_t num_frames);

private:
    std::atomic<EdlSnapshot*> active_snapshot_{nullptr};
    std::atomic<int64_t> playhead_sample_{0};
    std::atomic<bool> playing_{false};
    std::atomic<int32_t> xrun_count_{0};
    std::string room_tone_path_;
    std::vector<std::unique_ptr<EdlSnapshot>> retired_snapshots_;

#ifdef __ANDROID__
    struct AndroidStreamState;
    std::unique_ptr<AndroidStreamState> android_stream_;
#endif
};

}  // namespace voicestudio::edl
