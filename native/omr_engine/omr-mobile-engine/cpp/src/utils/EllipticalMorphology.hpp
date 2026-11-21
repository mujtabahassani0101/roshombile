#pragma once

#include <opencv2/core.hpp>

namespace omr {
// Exact OpenCV elliptical black-hat for an isolated 8-bit grayscale image and
// an odd, square kernel. Used for local timing-dot contrast, including borders.
cv::Mat ellipticalBlackHat(const cv::Mat& gray, int kernelSize);
} // namespace omr
