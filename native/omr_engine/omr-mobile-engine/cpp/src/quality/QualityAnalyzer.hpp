#pragma once

#include <opencv2/core.hpp>
#include "omr/ImageView.hpp"
#include "omr/OmrLiveResult.hpp"

namespace omr {

class QualityAnalyzer {
public:
  static OmrLiveMetrics analyzeGray(const cv::Mat& gray);
  static cv::Mat makeGrayMat(const ImageView& image, int maxWidth = 720);
};

} // namespace omr
