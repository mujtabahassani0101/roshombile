#pragma once

#include <opencv2/core.hpp>
#include <string>
#include <vector>
#include "omr/Geometry.hpp"
#include "omr/OmrLiveResult.hpp"
#include "omr/OmrTemplate.hpp"

namespace omr {

struct RefinedAnswerBubbleCenter {
  std::string blockId;
  int questionNumber = 0;
  int rowIndex = 0;
  std::string option;
  Point expected;
  Point detected;
  Point blockAffine;
  Point rowCorrected;
  Point refined;
  float confidence = 0.0f;
  float rowConfidence = 0.0f;
  std::string refinementMode = "template_fallback";
  bool hasDetected = false;
  bool usedForFit = false;
};

struct RefinedStudentCodeBubbleCenter {
  int visualColumnIndex = 0;
  int digitIndex = 0;
  std::string digit;
  Point expected;
  Point detected;
  Point refined;
  float confidence = 0.0f;
  bool hasDetected = false;
  bool usedForFit = false;
};

struct BubbleGridRefinement {
  bool templateRefinementUsed = false;
  std::string bubbleRefinementMode = "template_fallback";
  std::vector<RefinedAnswerBubbleCenter> answerCenters;
  std::vector<RefinedStudentCodeBubbleCenter> studentCodeCenters;
  std::vector<OmrBubbleGridStats> answerBlockStats;
  OmrBubbleGridStats studentCodeStats;
  std::vector<OmrAnswerRowResidual> answerRowResiduals;
  std::vector<std::string> warnings;
};

// Selects, for the calling thread, between the exact window-classified dense
// ring search (default) and the original per-sample loop. Both return
// bit-identical centers; the switch exists for paired before/after timings.
class DenseRingSearchScope {
public:
  explicit DenseRingSearchScope(bool enabled = true);
  ~DenseRingSearchScope();
  DenseRingSearchScope(const DenseRingSearchScope&) = delete;
  DenseRingSearchScope& operator=(const DenseRingSearchScope&) = delete;
private:
  bool previous_;
};

class BubbleGridRefiner {
public:
  static BubbleGridRefinement refine(
    const cv::Mat& normalizedBgr,
    const cv::Mat& normalizedGray,
    const OmrTemplate& tpl
  );
};

} // namespace omr
