#pragma once

#include <array>
#include <opencv2/core.hpp>
#include "omr/Geometry.hpp"
#include "omr/OmrTemplate.hpp"

namespace omr {

class Homography {
public:
  static cv::Mat perspectiveMatrix(const Quad& source, const OmrTemplate& tpl);
  static std::array<double, 9> toArray(const cv::Mat& matrix);
};

} // namespace omr
