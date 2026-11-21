#pragma once

#include <opencv2/core.hpp>
#include "geometry/BubbleGridRefiner.hpp"
#include "OmrLiveResult.hpp"
#include "OmrTemplate.hpp"

namespace omr {

// Immutable per-sheet data shared by student-code and answer reading. Preparing
// it once avoids repeating full-page grayscale conversion and Otsu thresholding
// while keeping the scoring and classification algorithms unchanged.
struct AnswerReaderContext {
  int markThreshold = 118;
};

class AnswerReader {
 public:
  static AnswerReaderContext prepare(
    const cv::Mat& normalizedImage,
    const cv::Mat& normalizedGray = cv::Mat()
  );
  static OmrStudentCodeReadResult readStudentCode(
    const cv::Mat& normalizedImage,
    const OmrTemplate& tpl,
    const BubbleGridRefinement* refinement = nullptr
  );
  static OmrStudentCodeReadResult readStudentCode(
    const cv::Mat& normalizedImage,
    const OmrTemplate& tpl,
    const AnswerReaderContext& context,
    const BubbleGridRefinement* refinement = nullptr
  );
  static OmrAnswerReadResult read(
    const cv::Mat& normalizedImage,
    const OmrTemplate& tpl,
    const BubbleGridRefinement* refinement = nullptr
  );
  static OmrAnswerReadResult read(
    const cv::Mat& normalizedImage,
    const OmrTemplate& tpl,
    const AnswerReaderContext& context,
    const BubbleGridRefinement* refinement = nullptr
  );
  static OmrAnswerReadResult readAnswerBlock(
    const cv::Mat& warpedAnswerBlock,
    const OmrTemplate& tpl,
    const cv::Mat& templateToAnswerBlock
  );
};

} // namespace omr
