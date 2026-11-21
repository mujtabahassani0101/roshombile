#include "geometry/Homography.hpp"

#include <opencv2/imgproc.hpp>

namespace omr {

cv::Mat Homography::perspectiveMatrix(const Quad& source, const OmrTemplate& tpl) {
  const std::vector<cv::Point2f> src = {
    {source.topLeft.x, source.topLeft.y},
    {source.topRight.x, source.topRight.y},
    {source.bottomRight.x, source.bottomRight.y},
    {source.bottomLeft.x, source.bottomLeft.y},
  };
  const std::vector<cv::Point2f> dst = {
    {0.0f, 0.0f},
    {tpl.width, 0.0f},
    {tpl.width, tpl.height},
    {0.0f, tpl.height},
  };
  return cv::getPerspectiveTransform(src, dst);
}

std::array<double, 9> Homography::toArray(const cv::Mat& matrix) {
  std::array<double, 9> out = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0};
  if (matrix.empty()) {
    return out;
  }
  cv::Mat doubleMatrix;
  matrix.convertTo(doubleMatrix, CV_64F);
  for (int row = 0; row < 3; ++row) {
    for (int col = 0; col < 3; ++col) {
      out[static_cast<size_t>(row * 3 + col)] = doubleMatrix.at<double>(row, col);
    }
  }
  return out;
}

} // namespace omr
