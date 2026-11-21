#pragma once

#include <opencv2/core.hpp>
#include "omr/Geometry.hpp"
#include "omr/OmrLiveResult.hpp"
#include "omr/OmrTemplate.hpp"
#include "rails/SideDotRailDetector.hpp"

namespace omr {

struct MatchedRailRow {
  Point detected;
  float canonicalX = 0.0f;
  float canonicalY = 0.0f;
};

struct SheetDetection {
  bool found = false;
  bool allCornersInside = false;
  Quad quad;
  float coverage = 0.0f;
  float angleScore = 0.0f;
  float railScore = 0.0f;
  std::string reason;
  std::string homographyStrategyUsed;
  int leftDotsUsed = 0;
  int rightDotsUsed = 0;
  int leftRowMatches = 0;
  int rightRowMatches = 0;
  int matchedRowsUsed = 0;
  double reprojectionError = 0.0;
  bool fallbackUsed = false;
  std::vector<MatchedRailRow> matchedRailRows;
};

class SheetQuadEstimator {
public:
  SheetDetection estimate(const RailDetection& rails, const OmrTemplate& tpl, int frameWidth, int frameHeight) const;
};

} // namespace omr
