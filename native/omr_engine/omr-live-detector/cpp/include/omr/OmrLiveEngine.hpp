#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include "ImageView.hpp"
#include "OmrLiveOptions.hpp"
#include "OmrLiveResult.hpp"
#include "OmrTemplate.hpp"
#include "decision/StabilityTracker.hpp"

namespace omr {

class OmrLiveEngine {
public:
  OmrLiveEngine();
  ~OmrLiveEngine();

  void configure(const std::string& templateJson, const std::string& optionsJson);

  // The input is a raw Gray8 Y plane. rowStride must be at least width.
  // orientation is the clockwise rotation required to make the input upright
  // and must be exactly 0, 90, 180, or 270 degrees. Result quad coordinates,
  // frameWidth, and frameHeight are expressed in the upright analysis image,
  // after it has been resized to a maximum width of 720 pixels.
  OmrLiveResult detectYPlane(
    const uint8_t* yData,
    int width,
    int height,
    int rowStride,
    int orientation,
    int64_t timestampMs
  );

  void reset();

  const OmrTemplate& currentTemplate() const;
  const OmrLiveOptions& currentOptions() const;

private:
  OmrTemplate template_;
  OmrLiveOptions options_;
  StabilityTracker stability_;
  bool hasAnalysisGeometry_ = false;
  int lastOrientation_ = 0;
  int lastAnalysisWidth_ = 0;
  int lastAnalysisHeight_ = 0;
  mutable std::mutex mutex_;
};

} // namespace omr
