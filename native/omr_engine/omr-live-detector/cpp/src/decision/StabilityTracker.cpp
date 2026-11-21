#include "decision/StabilityTracker.hpp"

#include <algorithm>
#include <cmath>

namespace omr {

void StabilityTracker::reset() {
  hasLastQuad_ = false;
  stableFrames_ = 0;
}

int StabilityTracker::update(const Quad& quad, bool qualityOk, int frameWidth, int frameHeight, int requiredFrames) {
  if (!qualityOk) {
    reset();
    return stableFrames_;
  }

  if (!hasLastQuad_) {
    lastQuad_ = quad;
    hasLastQuad_ = true;
    stableFrames_ = 1;
    return stableFrames_;
  }

  const float meanDistance = (
    distance(quad.topLeft, lastQuad_.topLeft) +
    distance(quad.topRight, lastQuad_.topRight) +
    distance(quad.bottomRight, lastQuad_.bottomRight) +
    distance(quad.bottomLeft, lastQuad_.bottomLeft)
  ) / 4.0f;
  const float diagonal = std::sqrt(static_cast<float>(frameWidth * frameWidth + frameHeight * frameHeight));
  const bool stable = diagonal > 0.0f && meanDistance / diagonal < 0.015f;
  stableFrames_ = stable ? std::min(stableFrames_ + 1, requiredFrames) : 1;
  lastQuad_ = quad;
  return stableFrames_;
}

float StabilityTracker::score(int requiredFrames) const {
  return clamp01(static_cast<float>(stableFrames_) / static_cast<float>(std::max(1, requiredFrames)));
}

bool StabilityTracker::isStable(int requiredFrames) const {
  return stableFrames_ >= requiredFrames;
}

int StabilityTracker::stableFrames() const {
  return stableFrames_;
}

} // namespace omr
