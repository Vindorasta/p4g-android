#include "input_stick.h"

#include <cassert>
#include <cmath>
#include <limits>

using namespace nx::plat;

double length(StickPosition p) { return std::hypot(double(p.x), double(p.y)); }

int main() {
    const double pi = std::acos(-1.0);
    for (int angle = 0; angle < 360; ++angle) {
        const float x = float(std::cos(angle * pi / 180));
        const float y = float(std::sin(angle * pi / 180));
        for (float deadzone : {0.f, 0.12f}) {
            const StickPosition full = radial_stick(x, y, deadzone, 0.98f);
            assert(std::abs(length(full) - kStickMax) <= 1);
            assert(std::abs(double(full.x) * y - double(full.y) * x) <= 1);
            const StickPosition edge = radial_stick(x * 0.99f, y * 0.99f, deadzone, 0.98f);
            assert(std::abs(length(edge) - kStickMax) <= 1);
            double previous = 0;
            for (int step = 0; step <= 98; ++step) {
                const float radius = float(step) / 100;
                const double current = length(radial_stick(x * radius, y * radius, deadzone, 0.98f));
                assert(current + 1 >= previous);
                assert(current <= kStickMax + 1);
                if (radius < 0.7f) assert(current < kStickMax * 0.8);
                previous = current;
            }
        }
    }
    assert(length(radial_stick(0, 0, 0, 0.98f)) == 0);
    assert(length(radial_stick(0.05f, 0.05f, 0.12f, 0.98f)) == 0);
    assert(length(radial_stick(0.1f, 0.1f, 0.12f, 0.98f)) > 0);
    assert(std::abs(length(radial_stick(0.49f, 0, 0, 0.98f)) - kStickMax * 0.5) <= 1);
    assert(length(radial_stick(std::numeric_limits<float>::quiet_NaN(), 1, 0.12f, 0.98f)) == 0);
    assert(length(radial_stick(1, std::numeric_limits<float>::infinity(), 0.12f, 0.98f)) == 0);
    const auto far = radial_stick(10, -7, 0, 0.98f);
    assert(std::abs(length(far) - kStickMax) <= 1);

    // The old per-axis 12% deadzone cut a circular stick's full diagonal down to about 94%.
    const float diagonal = float(std::sqrt(0.5));
    const double previous = std::hypot((diagonal - 0.12) / 0.88, (diagonal - 0.12) / 0.88);
    assert(previous < 0.95);
    assert(length(radial_stick(diagonal, diagonal, 0.12f, 0.98f)) > kStickMax * 0.999);
}
