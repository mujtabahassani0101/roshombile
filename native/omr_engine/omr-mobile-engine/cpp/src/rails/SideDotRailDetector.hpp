#pragma once

#include <opencv2/core.hpp>
#include <string>
#include <vector>
#include "omr/OmrTemplate.hpp"

namespace omr {

struct DotCandidate {
  float x = 0.0f;
  float y = 0.0f;
  float radius = 0.0f;
  float area = 0.0f;
  float circularity = 0.0f;
  float darkness = 0.0f;
  float score = 0.0f;
};

struct RejectedDotCandidate {
  float x = 0.0f;
  float y = 0.0f;
  std::string reason;
};

struct RailModel {
  bool found = false;
  std::vector<DotCandidate> dots;
  float lineA = 1.0f;
  float lineB = 0.0f;
  float lineC = 0.0f;
  float topY = 0.0f;
  float bottomY = 0.0f;
  float score = 0.0f;
  float rowMatchScore = 0.0f;

  float xAtY(float y) const;
};

struct RailDetection {
  RailModel left;
  RailModel right;
  int totalCandidates = 0;
  int expectedDotsPerSide = 0;
  int rejectedCandidateCount = 0;
  float railScore = 0.0f;
  float railConfidence = 0.0f;
  float rowMatchScore = 0.0f;
  std::string railDetectionMode = "unavailable";
  std::vector<RejectedDotCandidate> rejectedCandidates;
};

class SideDotRailDetector {
public:
  RailDetection detect(const cv::Mat& gray, const OmrTemplate& tpl, int minDotsPerSide) const;
  // Low-resolution orientation probes must never trigger the expensive
  // full-page fallback; final geometry is always verified by detect().
  RailDetection detectSideBands(const cv::Mat& gray, const OmrTemplate& tpl, int minDotsPerSide) const;

private:
  RailDetection detectWithScope(
    const cv::Mat& gray,
    const OmrTemplate& tpl,
    int minDotsPerSide,
    bool sideBandsOnly
  ) const;
  RailModel fitRail(
    const std::vector<DotCandidate>& candidates,
    int expectedDots,
    int minDots,
    float expectedRadius
  ) const;
};

} // namespace omr
