#pragma once

#include <algorithm>
#include <cmath>

#include "platform.h"

namespace nx::plat {

struct StickPosition {
    int32_t x = 0, y = 0;
};

// Apply the deadzone to the stick's radius, preserving its direction. An axial deadzone shrinks a circular
// controller's full diagonal below full strength. The outer radius allows a small tolerance at full deflection.
inline StickPosition radial_stick(float x, float y, float deadzone, float outer) {
    if (!std::isfinite(x) || !std::isfinite(y)) return {};
    const double length = std::hypot(double(x), double(y));
    if (length <= deadzone) return {};
    const double strength = std::clamp((length - deadzone) / (outer - deadzone), 0.0, 1.0);
    const double scale = strength * kStickMax / length;
    return {int32_t(std::lround(double(x) * scale)), int32_t(std::lround(double(y) * scale))};
}

}  // namespace nx::plat
