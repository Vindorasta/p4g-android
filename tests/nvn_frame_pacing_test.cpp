#include "nvn/nvn_frame_pacing.h"

#include <cassert>
#include <initializer_list>

using namespace nx::nvn;

int main() {
    constexpr uint64_t hz60 = 16'666'667;
    constexpr uint64_t cap = 33'333'334;
    for (uint64_t display : {hz60, uint64_t(8'333'333), uint64_t(11'111'111)}) {
        for (int interval : {0, 1}) {
            assert(present_period_ns(interval, display, false, true) == cap);
            assert(present_period_ns(interval, display, true, true) == cap);
        }
    }
    // Do not accelerate a game that requests a slower cadence.
    assert(present_period_ns(3, hz60, false, true) == 3 * hz60);
    assert(present_period_ns(2, hz60, false, false) == 2 * hz60);
    assert(present_period_ns(1, hz60, false, false) == hz60);
    assert(present_period_ns(0, hz60, false, false) == 0);
    assert(present_period_ns(1, hz60, true, false) == 0);

    PresentPacer pacer;
    uint64_t now = 100;
    assert(pacer.deadline(now, cap) == now); // First present need not wait.
    pacer.presented(now);
    for (int frame = 1; frame <= 300; ++frame) {
        const uint64_t previous = now;
        const uint64_t work = frame % 7 == 0 ? 50'000'000 : 2'000'000;
        now += work;
        const uint64_t deadline = pacer.deadline(now, cap);
        if (now < deadline) now = deadline;
        if (frame % 11 == 0) now += 1'000'000; // Scheduler oversleep.
        assert(now - previous >= cap); // No catch-up burst, even after stalls.
        pacer.presented(now);
    }
    pacer.deadline(now, 0); // Unpaced clears stale timing.
    now += 100;
    assert(pacer.deadline(now, cap) == now);
}
