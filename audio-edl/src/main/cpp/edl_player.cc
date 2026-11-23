#include "edl_player.h"

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstring>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace voicestudio::edl {

namespace {

constexpr float kHalfPi = 1.57079632679489661923f;

int64_t ExtractMtimeNs(const struct stat& st) {
#if defined(__APPLE__)
    return static_cast<int64_t>(st.st_mtimespec.tv_sec) * 1000000000LL +
           static_cast<int64_t>(st.st_mtimespec.tv_nsec);
#else
    return static_cast<int64_t>(st.st_mtim.tv_sec) * 1000000000LL +
           static_cast<int64_t>(st.st_mtim.tv_nsec);
#endif
}

}  // namespace

// -----------------------------------------------------------------------------
// MmapRegion & MmapFileRegistry
// -----------------------------------------------------------------------------

MmapRegion::~MmapRegion() {
    if (raw_mapping != nullptr && raw_mapping != MAP_FAILED && byte_size > 0) {
        ::munmap(raw_mapping, byte_size);
    }
    raw_mapping = nullptr;
    data = nullptr;
    byte_size = 0;
    sample_count = 0;
}

std::shared_ptr<const MmapRegion> MmapFileRegistry::MapFile(
    const std::string& path,
    std::string* error_out,
    bool force_reload) {
    if (path.empty()) {
        if (error_out) {
            *error_out = "PCM file path must not be empty";
        }
        return nullptr;
    }

    if (!force_reload) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = cache_.find(path);
        if (it != cache_.end() && it->second != nullptr) {
            return it->second;
        }
    }

    const int fd = ::open(path.c_str(), O_RDONLY);
    if (fd < 0) {
        if (error_out) {
            *error_out = "open('" + path + "') failed: " + std::strerror(errno);
        }
        return nullptr;
    }

    struct stat st {};
    if (::fstat(fd, &st) != 0) {
        const int err = errno;
        ::close(fd);
        if (error_out) {
            *error_out = "fstat('" + path + "') failed: " + std::strerror(err);
        }
        return nullptr;
    }

    if (!S_ISREG(st.st_mode)) {
        ::close(fd);
        if (error_out) {
            *error_out = "PCM path is not a regular file: " + path;
        }
        return nullptr;
    }

    if (st.st_size < static_cast<off_t>(sizeof(float))) {
        ::close(fd);
        if (error_out) {
            *error_out = "PCM file is empty or smaller than 1 Float32 sample: " + path;
        }
        return nullptr;
    }

    const size_t byte_size = static_cast<size_t>(st.st_size);
    void* mapped = ::mmap(nullptr, byte_size, PROT_READ, MAP_PRIVATE, fd, 0);
    const int mmap_err = errno;
    ::close(fd);

    if (mapped == MAP_FAILED || mapped == nullptr) {
        if (error_out) {
            *error_out = "mmap('" + path + "') failed: " + std::strerror(mmap_err);
        }
        return nullptr;
    }

    // Advise sequential pre-faulting off the audio thread so Oboe callbacks don't stall on minor faults.
    (void)::madvise(mapped, byte_size, MADV_WILLNEED);

    auto region = std::make_shared<MmapRegion>();
    region->path = path;
    region->raw_mapping = mapped;
    region->data = reinterpret_cast<const float*>(mapped);
    region->byte_size = byte_size;
    region->sample_count = static_cast<int64_t>(byte_size / sizeof(float));
    region->mtime_ns = ExtractMtimeNs(st);

    {
        std::lock_guard<std::mutex> lock(mutex_);
        cache_[path] = region;
    }
    return region;
}

void MmapFileRegistry::Invalidate(const std::string& path) {
    std::lock_guard<std::mutex> lock(mutex_);
    cache_.erase(path);
}

void MmapFileRegistry::Clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    cache_.clear();
}

size_t MmapFileRegistry::CachedFileCount() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return cache_.size();
}

// -----------------------------------------------------------------------------
// EdlRenderer (Pure C++20 Wait-Free Audio Engine)
// -----------------------------------------------------------------------------

EdlRenderer::EdlRenderer() {
    // Symmetric endpoints: theta(k) = (pi/2) * (k + 1) / (N + 1), k = 0..N-1.
    // Every transition step (including entry -1->0 and exit N-1->N) has equal delta_theta = pi/(2*(N+1)).
    const float denom = static_cast<float>(kCrossfadeSamples + 1);
    for (int32_t k = 0; k < kCrossfadeSamples; ++k) {
        const float theta = kHalfPi * static_cast<float>(k + 1) / denom;
        cos_lut_[k] = std::cos(theta);
        sin_lut_[k] = std::sin(theta);
    }
}

EdlRenderer::~EdlRenderer() {
    ResetAndReclaimAll();
}

void EdlRenderer::PrepareSnapshot(EdlSnapshot& snapshot) {
    int64_t cursor = 0;
    const size_t n = snapshot.slices.size();

    for (size_t i = 0; i < n; ++i) {
        AudioSlice& s = snapshot.slices[i];
        if (s.mmap_region != nullptr) {
            if (s.mmap_pcm_ptr == nullptr) {
                s.mmap_pcm_ptr = s.mmap_region->data;
            }
            if (s.total_samples <= 0 || s.total_samples > s.mmap_region->sample_count) {
                s.total_samples = s.mmap_region->sample_count;
            }
        }
        s.total_samples = std::max<int64_t>(0, s.total_samples);
        s.zero_cross_in_sample = std::clamp<int64_t>(s.zero_cross_in_sample, 0, s.total_samples);
        s.zero_cross_out_sample =
            std::clamp<int64_t>(s.zero_cross_out_sample, s.zero_cross_in_sample, s.total_samples);
        s.gap_after_samples = std::max<int32_t>(0, s.gap_after_samples);

        s.active_samples = s.zero_cross_out_sample - s.zero_cross_in_sample;
        s.timeline_start_sample = cursor;
        cursor += s.active_samples + static_cast<int64_t>(s.gap_after_samples);
        s.timeline_end_sample = cursor;

        s.fade_in_samples = 0;
        s.fade_out_samples = 0;
        s.direct_splice_with_next = false;
        s.splice_pre_samples = 0;
        s.splice_post_samples = 0;
    }

    snapshot.total_timeline_samples = cursor;

    // Default fade-in / fade-out against room tone for every slice; direct slice-to-slice crossfades
    // override adjacent fade_out/fade_in when gap == 0 and pre/post roll samples are available.
    for (size_t i = 0; i < n; ++i) {
        AudioSlice& s = snapshot.slices[i];
        if (s.active_samples <= 0) {
            continue;
        }
        const int64_t max_half = s.active_samples / 2;
        s.fade_in_samples = static_cast<int32_t>(std::min<int64_t>(kCrossfadeSamples, max_half));
        s.fade_out_samples =
            static_cast<int32_t>(std::min<int64_t>(kCrossfadeSamples, s.active_samples - s.fade_in_samples));
    }

    for (size_t i = 0; i + 1 < n; ++i) {
        AudioSlice& cur = snapshot.slices[i];
        AudioSlice& next = snapshot.slices[i + 1];
        if (cur.gap_after_samples != 0 || cur.active_samples <= 0 || next.active_samples <= 0) {
            continue;
        }

        const int64_t post_avail = cur.total_samples - cur.zero_cross_out_sample;
        const int64_t pre_avail = next.zero_cross_in_sample;
        const int64_t cur_body_avail = cur.active_samples - cur.fade_in_samples;
        const int64_t next_body_avail = next.active_samples - next.fade_out_samples;
        const int64_t target_xfade = std::min<int64_t>(
            kCrossfadeSamples,
            std::min(cur_body_avail, next_body_avail));

        const int64_t max_pre = std::min(pre_avail, cur_body_avail);
        const int64_t max_post = std::min(post_avail, next_body_avail);

        if (target_xfade > 0 && (max_pre + max_post) >= target_xfade) {
            const int64_t half = target_xfade / 2;
            int64_t splice_pre = std::min(max_pre, half);
            int64_t splice_post = std::min(max_post, target_xfade - splice_pre);
            if (splice_pre + splice_post < target_xfade) {
                splice_pre = target_xfade - splice_post;
            }
            cur.direct_splice_with_next = true;
            cur.splice_pre_samples = static_cast<int32_t>(splice_pre);
            cur.splice_post_samples = static_cast<int32_t>(splice_post);
            cur.fade_out_samples = 0;
            next.fade_in_samples = 0;
        }
    }

    snapshot.prepared = true;
}

void EdlRenderer::SetRoomTone(std::shared_ptr<const MmapRegion> room_tone_region) {
    std::lock_guard<std::mutex> lock(retire_mutex_);
    room_tone_region_ = std::move(room_tone_region);
    if (room_tone_region_ != nullptr && room_tone_region_->data != nullptr &&
        room_tone_region_->sample_count > 0) {
        room_tone_samples_.store(room_tone_region_->sample_count, std::memory_order_relaxed);
        room_tone_data_.store(room_tone_region_->data, std::memory_order_release);
    } else {
        room_tone_data_.store(nullptr, std::memory_order_release);
        room_tone_samples_.store(0, std::memory_order_release);
    }
}

bool EdlRenderer::HasRoomTone() const {
    return room_tone_data_.load(std::memory_order_acquire) != nullptr &&
           room_tone_samples_.load(std::memory_order_acquire) > 0;
}

void EdlRenderer::UpdateEdlSnapshot(EdlSnapshot* new_snapshot) {
    if (new_snapshot != nullptr) {
        if (new_snapshot->version == 0) {
            new_snapshot->version = next_version_.fetch_add(1, std::memory_order_relaxed);
        }
        if (!new_snapshot->prepared) {
            PrepareSnapshot(*new_snapshot);
        }
    }

    // Wait-free atomic publication: audio callback immediately sees new_snapshot without locking.
    EdlSnapshot* prior = active_snapshot_.exchange(new_snapshot, std::memory_order_seq_cst);
    const uint64_t retire_epoch = audio_epoch_.load(std::memory_order_seq_cst);

    std::lock_guard<std::mutex> lock(retire_mutex_);
    if (prior != nullptr) {
        retired_snapshots_.push_back(RetiredEntry{std::unique_ptr<EdlSnapshot>(prior), retire_epoch});
    }
    ReclaimRetiredLocked();
}

size_t EdlRenderer::ReclaimRetired() {
    std::lock_guard<std::mutex> lock(retire_mutex_);
    return ReclaimRetiredLocked();
}

size_t EdlRenderer::ReclaimRetiredLocked() {
    if (retired_snapshots_.empty()) {
        return 0;
    }

    const uint64_t now_epoch = audio_epoch_.load(std::memory_order_seq_cst);
    const EdlSnapshot* hp = hazard_current_.load(std::memory_order_seq_cst);

    // Move reclaimable entries out before destroying them so destruction happens cleanly off the audio thread.
    std::deque<RetiredEntry> remaining;
    std::vector<std::unique_ptr<EdlSnapshot>> to_delete;

    while (!retired_snapshots_.empty()) {
        RetiredEntry entry = std::move(retired_snapshots_.front());
        retired_snapshots_.pop_front();

        // Proof of safety:
        //  - If retire_epoch is EVEN, audio thread was outside RenderFrames after exchange completed,
        //    so it can never freshly load `entry.snapshot` from active_snapshot_.
        //  - If retire_epoch is ODD, audio thread was inside a RenderFrames callback that may have loaded
        //    `entry.snapshot` before updating hazard_current_; wait until now_epoch > retire_epoch.
        //  - In both cases, `entry.snapshot` must not equal hazard_current_ (which is cleared strictly
        //    AFTER the audio thread's final dereference of pinned_snapshot_).
        const bool epoch_safe = ((entry.retire_epoch & 1ULL) == 0ULL) || (now_epoch > entry.retire_epoch);
        const bool hazard_safe = (entry.snapshot.get() != hp);

        if (epoch_safe && hazard_safe) {
            to_delete.push_back(std::move(entry.snapshot));
        } else {
            remaining.push_back(std::move(entry));
        }
    }

    retired_snapshots_.swap(remaining);
    to_delete.clear();
    return retired_snapshots_.size();
}

void EdlRenderer::ResetAndReclaimAll() {
    playing_.store(false, std::memory_order_release);
    EdlSnapshot* prior = active_snapshot_.exchange(nullptr, std::memory_order_seq_cst);
    hazard_current_.store(nullptr, std::memory_order_seq_cst);
    pinned_snapshot_ = nullptr;
    was_playing_ = false;
    fading_to_pause_ = false;
    swap_fade_pos_ = kCrossfadeSamples;
    local_playhead_ = 0;
    global_render_frame_ = 0;
    playhead_sample_.store(0, std::memory_order_release);

    std::lock_guard<std::mutex> lock(retire_mutex_);
    if (prior != nullptr) {
        delete prior;
    }
    retired_snapshots_.clear();
    room_tone_data_.store(nullptr, std::memory_order_release);
    room_tone_samples_.store(0, std::memory_order_release);
    room_tone_region_.reset();
}

void EdlRenderer::SetPlaying(bool playing) {
    playing_.store(playing, std::memory_order_release);
}

bool EdlRenderer::IsPlaying() const {
    return playing_.load(std::memory_order_acquire);
}

void EdlRenderer::SeekToSample(int64_t timeline_sample) {
    const int64_t clamped = std::max<int64_t>(0, timeline_sample);
    seek_target_sample_.store(clamped, std::memory_order_release);
    seek_generation_.fetch_add(1, std::memory_order_acq_rel);
    playhead_sample_.store(clamped, std::memory_order_release);
}

int64_t EdlRenderer::GetCurrentSample() const {
    return playhead_sample_.load(std::memory_order_acquire);
}

int32_t EdlRenderer::GetXRunCount() const {
    return xrun_count_.load(std::memory_order_acquire);
}

size_t EdlRenderer::PendingRetiredCount() const {
    std::lock_guard<std::mutex> lock(retire_mutex_);
    return retired_snapshots_.size();
}

float EdlRenderer::ReadSliceSample(const AudioSlice& slice, int64_t sample_idx) noexcept {
    if (slice.mmap_pcm_ptr == nullptr || sample_idx < 0 || sample_idx >= slice.total_samples) {
        return 0.0f;
    }
    return slice.mmap_pcm_ptr[sample_idx];
}

float EdlRenderer::EqualPowerCrossfade(
    float sample_a,
    float sample_b,
    int32_t k,
    int32_t length) const noexcept {
    if (length <= 0) {
        return sample_b;
    }
    if (length == kCrossfadeSamples && k >= 0 && k < kCrossfadeSamples) {
        return cos_lut_[k] * sample_a + sin_lut_[k] * sample_b;
    }
    const int32_t clamped_k = std::clamp(k, 0, length - 1);
    const float theta = kHalfPi * static_cast<float>(clamped_k + 1) / static_cast<float>(length + 1);
    return std::cos(theta) * sample_a + std::sin(theta) * sample_b;
}

float EdlRenderer::SampleRoomTone(
    int64_t rt_frame,
    const float* rt_data,
    int64_t rt_len) const noexcept {
    if (rt_data == nullptr || rt_len <= 0) {
        return 0.0f;
    }
    if (rt_frame < 0) {
        rt_frame = 0;
    }
    if (rt_len > 2 * kCrossfadeSamples) {
        const int64_t loop_len = rt_len - kCrossfadeSamples;
        const int64_t idx = rt_frame % loop_len;
        if (rt_frame >= loop_len && idx < kCrossfadeSamples) {
            return cos_lut_[idx] * rt_data[loop_len + idx] + sin_lut_[idx] * rt_data[idx];
        }
        return rt_data[idx];
    }
    return rt_data[rt_frame % rt_len];
}

float EdlRenderer::EvalSnapshot(
    const EdlSnapshot* snapshot,
    int64_t timeline_sample,
    int64_t rt_frame,
    const float* rt_data,
    int64_t rt_len) const noexcept {
    const float rt = SampleRoomTone(rt_frame, rt_data, rt_len);
    if (snapshot == nullptr || snapshot->slices.empty() ||
        timeline_sample < 0 || timeline_sample >= snapshot->total_timeline_samples) {
        return rt;
    }

    const auto& slices = snapshot->slices;
    const auto it = std::upper_bound(
        slices.begin(),
        slices.end(),
        timeline_sample,
        [](int64_t t, const AudioSlice& s) { return t < s.timeline_end_sample; });

    if (it == slices.end()) {
        return rt;
    }

    const size_t idx = static_cast<size_t>(it - slices.begin());
    const AudioSlice& s = *it;
    const int64_t u = timeline_sample - s.timeline_start_sample;

    if (u < 0 || u >= s.active_samples) {
        return rt;
    }

    // 1. Post-boundary half of direct slice-to-slice equal-power crossfade from slices[idx - 1].
    if (idx > 0) {
        const AudioSlice& prev = slices[idx - 1];
        if (prev.direct_splice_with_next && u < prev.splice_post_samples) {
            const int32_t xlen = prev.splice_pre_samples + prev.splice_post_samples;
            const int32_t k = prev.splice_pre_samples + static_cast<int32_t>(u);
            const float sample_a = ReadSliceSample(prev, prev.zero_cross_out_sample + u);
            const float sample_b = ReadSliceSample(s, s.zero_cross_in_sample + u);
            return EqualPowerCrossfade(sample_a, sample_b, k, xlen);
        }
    }

    // 2. Pre-boundary half of direct slice-to-slice equal-power crossfade into slices[idx + 1].
    if (s.direct_splice_with_next && idx + 1 < slices.size() &&
        u >= s.active_samples - s.splice_pre_samples) {
        const AudioSlice& next = slices[idx + 1];
        const int32_t xlen = s.splice_pre_samples + s.splice_post_samples;
        const int32_t offset_from_boundary = static_cast<int32_t>(s.active_samples - u);
        const int32_t k = s.splice_pre_samples - offset_from_boundary;
        const float sample_a = ReadSliceSample(s, s.zero_cross_in_sample + u);
        const float sample_b = ReadSliceSample(next, next.zero_cross_in_sample - offset_from_boundary);
        return EqualPowerCrossfade(sample_a, sample_b, k, xlen);
    }

    // 3. Gap -> Slice equal-power cosine fade-in over room tone.
    if (s.fade_in_samples > 0 && u < s.fade_in_samples) {
        const float sample_b = ReadSliceSample(s, s.zero_cross_in_sample + u);
        return EqualPowerCrossfade(rt, sample_b, static_cast<int32_t>(u), s.fade_in_samples);
    }

    // 4. Slice -> Gap equal-power cosine fade-out into room tone.
    if (s.fade_out_samples > 0 && u >= s.active_samples - s.fade_out_samples) {
        const int32_t k = static_cast<int32_t>(u - (s.active_samples - s.fade_out_samples));
        const float sample_a = ReadSliceSample(s, s.zero_cross_in_sample + u);
        return EqualPowerCrossfade(sample_a, rt, k, s.fade_out_samples);
    }

    // 5. Slice body.
    return ReadSliceSample(s, s.zero_cross_in_sample + u);
}

void EdlRenderer::CaptureContinuationToFadeBuf(
    const EdlSnapshot* old_snapshot,
    int64_t playhead,
    int64_t rt_frame,
    const float* rt_data,
    int64_t rt_len) noexcept {
    for (int32_t i = 0; i < kCrossfadeSamples; ++i) {
        const float base = EvalSnapshot(old_snapshot, playhead + i, rt_frame + i, rt_data, rt_len);
        const int32_t prev_pos = swap_fade_pos_ + i;
        if (prev_pos < kCrossfadeSamples) {
            fade_scratch_buf_[i] =
                cos_lut_[prev_pos] * fade_out_buf_[prev_pos] + sin_lut_[prev_pos] * base;
        } else {
            fade_scratch_buf_[i] = base;
        }
    }
    fade_out_buf_ = fade_scratch_buf_;
}

void EdlRenderer::RenderFrames(float* out_mono, int32_t num_frames) {
    if (out_mono == nullptr || num_frames <= 0) {
        return;
    }

    // Enter real-time reader epoch (odd = active callback).
    audio_epoch_.fetch_add(1, std::memory_order_seq_cst);

    const bool want_play = playing_.load(std::memory_order_acquire);
    const EdlSnapshot* active = active_snapshot_.load(std::memory_order_seq_cst);
    const uint64_t seek_gen = seek_generation_.load(std::memory_order_acquire);
    const float* rt_data = room_tone_data_.load(std::memory_order_acquire);
    const int64_t rt_len = room_tone_samples_.load(std::memory_order_acquire);

    if (!want_play) {
        if (was_playing_) {
            // Smooth 660-sample equal-power fade-out to silence on pause.
            CaptureContinuationToFadeBuf(
                pinned_snapshot_, local_playhead_, global_render_frame_, rt_data, rt_len);
            was_playing_ = false;
            fading_to_pause_ = true;
            swap_fade_pos_ = 0;
        }

        // Release stale pinned snapshot while paused so control thread can reclaim it promptly.
        if (active != pinned_snapshot_) {
            pinned_snapshot_ = active;
            hazard_current_.store(active, std::memory_order_seq_cst);
        }
        if (seek_gen != last_seek_generation_) {
            last_seek_generation_ = seek_gen;
            local_playhead_ = seek_target_sample_.load(std::memory_order_acquire);
            playhead_sample_.store(local_playhead_, std::memory_order_release);
        }

        if (fading_to_pause_) {
            for (int32_t i = 0; i < num_frames; ++i) {
                if (swap_fade_pos_ < kCrossfadeSamples) {
                    out_mono[i] = std::clamp(
                        cos_lut_[swap_fade_pos_] * fade_out_buf_[swap_fade_pos_], -1.0f, 1.0f);
                    ++swap_fade_pos_;
                    ++global_render_frame_;
                } else {
                    out_mono[i] = 0.0f;
                    fading_to_pause_ = false;
                }
            }
        } else {
            std::memset(out_mono, 0, static_cast<size_t>(num_frames) * sizeof(float));
        }

        audio_epoch_.fetch_add(1, std::memory_order_seq_cst);
        return;
    }

    // Playing state.
    if (!was_playing_) {
        if (fading_to_pause_) {
            for (int32_t i = 0; i < kCrossfadeSamples; ++i) {
                const int32_t prev_pos = swap_fade_pos_ + i;
                fade_scratch_buf_[i] = (prev_pos < kCrossfadeSamples)
                    ? (cos_lut_[prev_pos] * fade_out_buf_[prev_pos])
                    : 0.0f;
            }
            fade_out_buf_ = fade_scratch_buf_;
            fading_to_pause_ = false;
        } else {
            fade_out_buf_.fill(0.0f);
        }
        swap_fade_pos_ = 0;
        pinned_snapshot_ = active;
        hazard_current_.store(active, std::memory_order_seq_cst);
        if (seek_gen != last_seek_generation_) {
            last_seek_generation_ = seek_gen;
            local_playhead_ = seek_target_sample_.load(std::memory_order_acquire);
        }
        was_playing_ = true;
    } else {
        const bool has_seek = (seek_gen != last_seek_generation_);
        const bool has_swap = (active != pinned_snapshot_);
        if (has_seek || has_swap) {
            // Capture exact 660-sample continuation from pinned_snapshot_ BEFORE unpinning it.
            CaptureContinuationToFadeBuf(
                pinned_snapshot_, local_playhead_, global_render_frame_, rt_data, rt_len);
            swap_fade_pos_ = 0;

            if (has_swap) {
                pinned_snapshot_ = active;
                hazard_current_.store(active, std::memory_order_seq_cst);
            }
            if (has_seek) {
                last_seek_generation_ = seek_gen;
                local_playhead_ = seek_target_sample_.load(std::memory_order_acquire);
            }
        }
    }

    for (int32_t i = 0; i < num_frames; ++i) {
        const float target =
            EvalSnapshot(pinned_snapshot_, local_playhead_, global_render_frame_, rt_data, rt_len);
        float sample = target;
        if (swap_fade_pos_ < kCrossfadeSamples) {
            sample = cos_lut_[swap_fade_pos_] * fade_out_buf_[swap_fade_pos_] +
                     sin_lut_[swap_fade_pos_] * target;
            ++swap_fade_pos_;
        }
        out_mono[i] = std::clamp(sample, -1.0f, 1.0f);
        ++local_playhead_;
        ++global_render_frame_;
    }

    playhead_sample_.store(local_playhead_, std::memory_order_release);

    // Leave real-time reader epoch (even = callback finished).
    audio_epoch_.fetch_add(1, std::memory_order_seq_cst);
}

// -----------------------------------------------------------------------------
// EdlPlayer (Oboe AAudio + MmapFileRegistry + Wait-Free EdlRenderer)
// -----------------------------------------------------------------------------

EdlPlayer::EdlPlayer() = default;

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

    auto room_tone = mmap_registry_.MapFile(room_tone_loop_path, error_out);
    if (!room_tone) {
        return false;
    }

#ifdef __ANDROID__
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(kSampleRateHz)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setDataCallback(this);

    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        // Fallback to Shared mode if Exclusive is unavailable on this audio route.
        builder.setSharingMode(oboe::SharingMode::Shared);
        result = builder.openStream(stream_);
    }
    if (result != oboe::Result::OK || !stream_) {
        if (error_out) {
            *error_out = std::string("Oboe openStream failed: ") + oboe::convertToText(result);
        }
        stream_.reset();
        return false;
    }
#endif

    room_tone_path_ = room_tone_loop_path;
    renderer_.SetRoomTone(std::move(room_tone));
    return true;
}

bool EdlPlayer::Start(std::string* error_out) {
    if (room_tone_path_.empty() || !renderer_.HasRoomTone()) {
        if (error_out) {
            *error_out = "EdlPlayer::Init must succeed before Start()";
        }
        return false;
    }

    renderer_.SetPlaying(true);

#ifdef __ANDROID__
    if (!stream_) {
        renderer_.SetPlaying(false);
        if (error_out) {
            *error_out = "Oboe AudioStream is not open";
        }
        return false;
    }
    const oboe::Result result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        renderer_.SetPlaying(false);
        if (error_out) {
            *error_out = std::string("Oboe requestStart failed: ") + oboe::convertToText(result);
        }
        return false;
    }
#endif

    return true;
}

void EdlPlayer::Pause() {
    renderer_.SetPlaying(false);
#ifdef __ANDROID__
    if (stream_) {
        (void)stream_->requestPause();
    }
#endif
}

void EdlPlayer::Close() {
    renderer_.SetPlaying(false);
#ifdef __ANDROID__
    if (stream_) {
        (void)stream_->stop();
        (void)stream_->close();
        stream_.reset();
    }
#endif
    renderer_.ResetAndReclaimAll();
    mmap_registry_.Clear();
    room_tone_path_.clear();
}

bool EdlPlayer::PreloadFiles(const std::vector<std::string>& pcm_paths, std::string* error_out) {
    for (const auto& path : pcm_paths) {
        if (!mmap_registry_.MapFile(path, error_out)) {
            return false;
        }
    }
    return true;
}

std::unique_ptr<EdlSnapshot> EdlPlayer::BuildSnapshot(
    const std::vector<SliceDescriptor>& descriptors,
    float global_gap_scale,
    std::string* error_out) {
    auto snapshot = std::make_unique<EdlSnapshot>();
    snapshot->global_gap_scale = global_gap_scale;
    snapshot->slices.reserve(descriptors.size());

    for (const auto& desc : descriptors) {
        auto region = mmap_registry_.MapFile(desc.pcm_file_path, error_out);
        if (!region) {
            return nullptr;
        }
        AudioSlice slice;
        slice.pcm_file_path = desc.pcm_file_path;
        slice.mmap_pcm_ptr = region->data;
        slice.total_samples = region->sample_count;
        slice.mmap_region = std::move(region);
        slice.zero_cross_in_sample = desc.zero_cross_in_sample;
        slice.zero_cross_out_sample = desc.zero_cross_out_sample;
        slice.gap_after_samples = desc.gap_after_samples;
        snapshot->slices.push_back(std::move(slice));
    }

    EdlRenderer::PrepareSnapshot(*snapshot);
    return snapshot;
}

void EdlPlayer::UpdateEdlSnapshot(EdlSnapshot* new_snapshot) {
    renderer_.UpdateEdlSnapshot(new_snapshot);
}

bool EdlPlayer::UpdateEdlFromDescriptors(
    const std::vector<SliceDescriptor>& descriptors,
    float global_gap_scale,
    std::string* error_out) {
    auto snapshot = BuildSnapshot(descriptors, global_gap_scale, error_out);
    if (!snapshot) {
        return false;
    }
    renderer_.UpdateEdlSnapshot(snapshot.release());
    return true;
}

void EdlPlayer::SeekToSample(int64_t timeline_sample) {
    renderer_.SeekToSample(timeline_sample);
}

int64_t EdlPlayer::GetCurrentSample() const {
    return renderer_.GetCurrentSample();
}

int32_t EdlPlayer::GetXRunCount() const {
#ifdef __ANDROID__
    if (stream_) {
        const auto result = stream_->getXRunCount();
        if (result) {
            return result.value();
        }
    }
#endif
    return renderer_.GetXRunCount();
}

bool EdlPlayer::IsPlaying() const {
    return renderer_.IsPlaying();
}

void EdlPlayer::RenderFrames(float* out_mono, int32_t num_frames) {
    renderer_.RenderFrames(out_mono, num_frames);
}

#ifdef __ANDROID__
oboe::DataCallbackResult EdlPlayer::onAudioReady(
    oboe::AudioStream* audio_stream,
    void* audio_data,
    int32_t num_frames) {
    if (audio_data == nullptr || num_frames <= 0) {
        return oboe::DataCallbackResult::Continue;
    }

    auto* out = static_cast<float*>(audio_data);
    const int32_t channels = (audio_stream != nullptr) ? audio_stream->getChannelCount() : 1;

    if (channels <= 1) {
        renderer_.RenderFrames(out, num_frames);
    } else {
        // Render mono into the tail slot of the interleaved buffer, then fan out L/R in-place
        // with zero stack or heap allocation.
        float* mono_tail = out + static_cast<size_t>(num_frames) * static_cast<size_t>(channels - 1);
        renderer_.RenderFrames(mono_tail, num_frames);
        for (int32_t i = 0; i < num_frames; ++i) {
            const float sample = mono_tail[i];
            for (int32_t ch = 0; ch < channels; ++ch) {
                out[static_cast<size_t>(i) * static_cast<size_t>(channels) + static_cast<size_t>(ch)] =
                    sample;
            }
        }
    }

    return oboe::DataCallbackResult::Continue;
}
#endif

}  // namespace voicestudio::edl
