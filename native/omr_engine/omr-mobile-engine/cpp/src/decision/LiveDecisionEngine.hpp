#pragma once

#include "geometry/SheetQuadEstimator.hpp"
#include "omr/OmrLiveOptions.hpp"
#include "omr/OmrLiveResult.hpp"

namespace omr {

class LiveDecisionEngine {
public:
  static OmrLiveResult decide(
    const OmrLiveMetrics& metrics,
    const SheetDetection& sheet,
    const OmrLiveOptions& options,
    int stableFrames,
    int frameWidth,
    int frameHeight,
    int64_t timestampMs
  );
};

} // namespace omr
