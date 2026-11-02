#pragma once

#include <cstdint>

namespace nx::nvn {

// The launcher's 60 setting means game default; 30 is an explicit host cap.
// An explicit cap also applies to interval zero and diagnostic unpaced mode.
constexpr uint64_t present_period_ns(int interval, uint64_t display_period, bool unpaced, bool cap30) {
    const uint64_t game_period = !unpaced && interval > 0 ? uint64_t(interval) * display_period : 0;
    constexpr uint64_t cap_period = (1'000'000'000ull + 29) / 30;
    return cap30 && game_period < cap_period ? cap_period : game_period;
}

class PresentPacer {
    uint64_t last_ = 0;
    bool started_ = false;
public:
    uint64_t deadline(uint64_t now, uint64_t period) {
        if (!period) { started_ = false; return now; }
        return started_ ? last_ + period : now;
    }
    void presented(uint64_t now) { last_ = now; started_ = true; }
};

} // namespace nx::nvn
