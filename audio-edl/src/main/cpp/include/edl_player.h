#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#ifdef __ANDROID__
#include <oboe/Oboe.h>
#endif

namespace voicestudio::edl {

constexpr int32_t kSampleRateHz = 44100;
constexpr int32_t kCrossfadeSamples = 660;  // 15 ms equal-power cosine @ 44.1 kHz
constexpr float kRoomToneTargetDbfs = -58.0f;

/**
 * Read-only POSIX mmap region for a 64-byte-aligned headerless 44.1 kHz Float32 mono `.pcm` file.
 * Lifetime is managed strictly off the audio thread via std::shared_ptr<const MmapRegion>.
 */
struct MmapRegion {
    std::string path;
    void* raw_mapping = nullptr;
    const float* data = nullptr;
    size_t byte_size = 0;
    int64_t sample_count = 0;
    int64_t mtime_ns = 0;

    MmapRegion() = default;
    ~MmapRegion();

    MmapRegion(const MmapRegion&) = delete;
    MmapRegion& operator=(const MmapRegion&) = delete;
};

/**
 * Reference-counted off-audio-thread POSIX mmap file cache.
 * Repeated snapshot swaps over the same take `.pcm` files reuse existing mappings in O(1)
 * with zero disk stitching and zero syscalls on cache hit.
 */
class MmapFileRegistry {
public:
    MmapFileRegistry() = default;
    ~MmapFileRegistry() = default;

    MmapFileRegistry(const MmapFileRegistry&) = delete;
    MmapFileRegistry& operator=(const MmapFileRegistry&) = delete;

    // Maps `path` (or returns cached mapping). Thread-safe for control threads; never call on audio thread.
    std::shared_ptr<const MmapRegion> MapFile(
        const std::string& path,
        std::string* error_out = nullptr,
        bool force_reload = false);

    // Invalidates a single cached path (e.g. after single-clause re-synthesis overwrote the file).
    void Invalidate(const std::string& path);

    // Releases all cached references. Active EdlSnapshots continue holding their shared_ptrs until retired.
    void Clear();

    // Returns the number of currently cached mmap file entries.
    size_t CachedFileCount() const;

private:
    mutable std::mutex mutex_;
    std::unordered_map<std::string, std::shared_ptr<const MmapRegion>> cache_;
};

/**
 * Input descriptor for building an EdlSnapshot off the audio thread.
 */
struct SliceDescriptor {
    std::string pcm_file_path;
    int64_t zero_cross_in_sample = 0;
    int64_t zero_cross_out_sample = 0;
    int32_t gap_after_samples = 0;
};

/**
 * A single slice inside an immutable EdlSnapshot.
 * Points directly to read-only mmap'd 44.1 kHz Float32 PCM memory.
 */
struct AudioSlice {
    std::string pcm_file_path;
    const float* mmap_pcm_ptr = nullptr;
    std::shared_ptr<const MmapRegion> mmap_region;
    int64_t total_samples = 0;
    int64_t zero_cross_in_sample = 0;
    int64_t zero_cross_out_sample = 0;
    int32_t gap_after_samples = 0;

    // Precomputed timeline & crossfade geometry (computed off the audio thread by PrepareSnapshot).
    int64_t timeline_start_sample = 0;
    int64_t active_samples = 0;
    int64_t timeline_end_sample = 0;
    int32_t fade_in_samples = 0;
    int32_t fade_out_samples = 0;
    bool direct_splice_with_next = false;
    int32_t splice_pre_samples = 0;
    int32_t splice_post_samples = 0;
};

/**
 * Immutable double-buffered Edit Decision List snapshot published via std::atomic<EdlSnapshot*>.
 */
struct EdlSnapshot {
    uint64_t version = 0;
    float global_gap_scale = 1.0f;
    std::vector<AudioSlice> slices;
    int64_t total_timeline_samples = 0;
    bool prepared = false;
};

/**
 * Pure C++20 wait-free real-time audio EDL renderer.
 * Callable both from Android Oboe AAudio onAudioReady() and host CTest without Android audio hardware.
 *
 * Real-time contract for RenderFrames():
 *  - ZERO heap allocations (new/malloc/vector growth)
 *  - ZERO deletions (delete/free/shared_ptr destruction)
 *  - ZERO mutex locks
 *  - ZERO syscalls
 *
 * Memory reclamation uses a seq_cst single-reader epoch + 1-slot hazard pointer so control threads
 * safely destroy retired EdlSnapshots and their MmapRegions off the audio thread.
 */
class EdlRenderer {
public:
    EdlRenderer();
    ~EdlRenderer();

    EdlRenderer(const EdlRenderer&) = delete;
    EdlRenderer& operator=(const EdlRenderer&) = delete;

    // Precomputes timeline offsets and crossfade geometry off the audio thread.
    static void PrepareSnapshot(EdlSnapshot& snapshot);

    // Installs the -58 dBFS room-tone loop region (must be called off audio thread before Start or while stopped).
    void SetRoomTone(std::shared_ptr<const MmapRegion> room_tone_region);
    bool HasRoomTone() const;

    // Wait-free atomic snapshot swap (< 0.05 ms). Takes ownership of `new_snapshot`.
    // Old snapshots are retired safely off the audio thread once the audio thread leaves its epoch
    // and unpins the snapshot.
    void UpdateEdlSnapshot(EdlSnapshot* new_snapshot);

    // Attempts to reclaim retired snapshots whose epoch/hazard conditions are satisfied.
    // Returns number of snapshots still pending reclamation.
    size_t ReclaimRetired();

    // Must be called ONLY after the audio thread/stream has completely stopped.
    // Clears active snapshot, hazard pointer, retired queue, and room-tone reference.
    void ResetAndReclaimAll();

    void SetPlaying(bool playing);
    bool IsPlaying() const;

    void SeekToSample(int64_t timeline_sample);
    int64_t GetCurrentSample() const;
    int32_t GetXRunCount() const;
    size_t PendingRetiredCount() const;

    // Wait-free real-time mono Float32 renderer.
    void RenderFrames(float* out_mono, int32_t num_frames);

private:
    struct RetiredEntry {
        std::unique_ptr<EdlSnapshot> snapshot;
        uint64_t retire_epoch = 0;
    };

    // Audio-thread-only helpers (zero allocations, zero locks).
    float SampleRoomTone(int64_t rt_frame, const float* rt_data, int64_t rt_len) const noexcept;
    float EvalSnapshot(
        const EdlSnapshot* snapshot,
        int64_t timeline_sample,
        int64_t rt_frame,
        const float* rt_data,
        int64_t rt_len) const noexcept;
    static float ReadSliceSample(const AudioSlice& slice, int64_t sample_idx) noexcept;
    float EqualPowerCrossfade(float sample_a, float sample_b, int32_t k, int32_t length) const noexcept;
    void CaptureContinuationToFadeBuf(
        const EdlSnapshot* old_snapshot,
        int64_t playhead,
        int64_t rt_frame,
        const float* rt_data,
        int64_t rt_len) noexcept;

    size_t ReclaimRetiredLocked();

    // Precomputed 660-sample equal-power cosine/sine lookup tables (zero trig calls on hot path).
    std::array<float, kCrossfadeSamples> cos_lut_{};
    std::array<float, kCrossfadeSamples> sin_lut_{};

    // Wait-free shared atomics between control thread(s) and real-time audio thread.
    std::atomic<EdlSnapshot*> active_snapshot_{nullptr};
    std::atomic<const EdlSnapshot*> hazard_current_{nullptr};
    std::atomic<uint64_t> audio_epoch_{0};
    std::atomic<int64_t> playhead_sample_{0};
    std::atomic<int64_t> seek_target_sample_{0};
    std::atomic<uint64_t> seek_generation_{0};
    std::atomic<bool> playing_{false};
    std::atomic<int32_t> xrun_count_{0};
    std::atomic<const float*> room_tone_data_{nullptr};
    std::atomic<int64_t> room_tone_samples_{0};

    // Audio-thread-exclusive state (never accessed by control threads while audio runs).
    const EdlSnapshot* pinned_snapshot_ = nullptr;
    int64_t local_playhead_ = 0;
    int64_t global_render_frame_ = 0;
    uint64_t last_seek_generation_ = 0;
    bool was_playing_ = false;
    bool fading_to_pause_ = false;
    int32_t swap_fade_pos_ = kCrossfadeSamples;
    std::array<float, kCrossfadeSamples> fade_out_buf_{};
    std::array<float, kCrossfadeSamples> fade_scratch_buf_{};

    // Control-thread-only state (NEVER touched by RenderFrames).
    mutable std::mutex retire_mutex_;
    std::deque<RetiredEntry> retired_snapshots_;
    std::shared_ptr<const MmapRegion> room_tone_region_;
    std::atomic<uint64_t> next_version_{1};
};

/**
 * Full EDL playback engine combining MmapFileRegistry + wait-free EdlRenderer + Android Oboe AAudio callback.
 */
#ifdef __ANDROID__
class EdlPlayer : public oboe::AudioStreamDataCallback {
#else
class EdlPlayer {
#endif
public:
    EdlPlayer();
    ~EdlPlayer();

    EdlPlayer(const EdlPlayer&) = delete;
    EdlPlayer& operator=(const EdlPlayer&) = delete;

    bool Init(const std::string& room_tone_loop_path, std::string* error_out = nullptr);
    bool Start(std::string* error_out = nullptr);
    void Pause();
    void Close();

    // Pre-mmaps a list of `.pcm` paths off the audio thread so subsequent take swaps run in < 0.05 ms.
    bool PreloadFiles(const std::vector<std::string>& pcm_paths, std::string* error_out = nullptr);

    // Builds an immutable EdlSnapshot backed by reference-counted mmap'd `.pcm` files (off audio thread).
    std::unique_ptr<EdlSnapshot> BuildSnapshot(
        const std::vector<SliceDescriptor>& descriptors,
        float global_gap_scale,
        std::string* error_out = nullptr);

    // Wait-free atomic snapshot publication (takes ownership of `new_snapshot`).
    void UpdateEdlSnapshot(EdlSnapshot* new_snapshot);

    // Convenience helper: builds snapshot from descriptors via MmapFileRegistry and publishes atomically.
    bool UpdateEdlFromDescriptors(
        const std::vector<SliceDescriptor>& descriptors,
        float global_gap_scale,
        std::string* error_out = nullptr);

    void SeekToSample(int64_t timeline_sample);
    int64_t GetCurrentSample() const;
    int32_t GetXRunCount() const;
    bool IsPlaying() const;

    // Real-time-safe audio renderer invoked by Oboe callback (Android) or offline CTest (host).
    void RenderFrames(float* out_mono, int32_t num_frames);

    EdlRenderer& renderer() { return renderer_; }
    const EdlRenderer& renderer() const { return renderer_; }
    MmapFileRegistry& mmap_registry() { return mmap_registry_; }
    const MmapFileRegistry& mmap_registry() const { return mmap_registry_; }

#ifdef __ANDROID__
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* audio_stream,
        void* audio_data,
        int32_t num_frames) override;
#endif

private:
    MmapFileRegistry mmap_registry_;
    EdlRenderer renderer_;
    std::string room_tone_path_;

#ifdef __ANDROID__
    std::shared_ptr<oboe::AudioStream> stream_;
#endif
};

}  // namespace voicestudio::edl
