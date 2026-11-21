#pragma once

#include "omr/Geometry.hpp"

namespace omr {

class StabilityTracker {
public:
  void reset();
  int update(const Quad& quad, bool qualityOk, int frameWidth, int frameHeight, int requiredFrames);
  float score(int requiredFrames) const;
  bool isStable(int requiredFrames) const;
  int stableFrames() const;

private:
  bool hasLastQuad_ = false;
  Quad lastQuad_;
  int stableFrames_ = 0;
};

} // namespace omr
