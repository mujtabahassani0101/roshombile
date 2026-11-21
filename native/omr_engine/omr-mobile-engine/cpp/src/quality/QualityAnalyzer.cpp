#include "quality/QualityAnalyzer.hpp"

#include <algorithm>
#include <array>
#include <opencv2/imgproc.hpp>

namespace omr {

namespace {

uint8_t percentileFromHistogram(const std::array<int, 256>& histogram, int targetIndex) {
  int seen = 0;
  for (size_t value = 0; value < histogram.size(); ++value) {
    seen += histogram[value];
    if (seen > targetIndex) {
      return static_cast<uint8_t>(value);
    }
  }
  return 255;
}

std::array<int, 256> buildHistogram(const cv::Mat& gray) {
  std::array<int, 256> histogram{};
  for (int y = 0; y < gray.rows; ++y) {
    const uint8_t* row = gray.ptr<uint8_t>(y);
    for (int x = 0; x < gray.cols; ++x) {
      histogram[row[x]] += 1;
    }
  }
  return histogram;
}

} // namespace

cv::Mat QualityAnalyzer::makeGrayMat(const ImageView& image, int maxWidth) {
  if (image.data == nullptr || image.width <= 0 || image.height <= 0 || image.rowStride <= 0) {
    return {};
  }

  cv::Mat source(image.height, image.width, CV_8UC1, const_cast<uint8_t*>(image.data), image.rowStride);
  if (image.width <= maxWidth) {
    return source.clone();
  }

  const double scale = static_cast<double>(maxWidth) / static_cast<double>(image.width);
  cv::Mat resized;
  cv::resize(source, resized, cv::Size(), scale, scale, cv::INTER_AREA);
  return resized;
}

OmrLiveMetrics QualityAnalyzer::analyzeGray(const cv::Mat& gray) {
  OmrLiveMetrics metrics;
  if (gray.empty()) {
    return metrics;
  }

  const int marginX = gray.cols / 10;
  const int marginY = gray.rows / 10;
  const cv::Rect roiRect(marginX, marginY, std::max(1, gray.cols - marginX * 2), std::max(1, gray.rows - marginY * 2));
  const cv::Mat roi = gray(roiRect);
  const std::array<int, 256> histogram = buildHistogram(roi);
  const int pixelCount = static_cast<int>(roi.total());
  const uint8_t median = percentileFromHistogram(histogram, pixelCount / 2);
  metrics.brightness = static_cast<float>(median) / 255.0f;

  const uint8_t p5 = percentileFromHistogram(histogram, static_cast<int>(static_cast<double>(pixelCount) * 0.05));
  const uint8_t p95 = percentileFromHistogram(histogram, static_cast<int>(static_cast<double>(pixelCount) * 0.95));
  metrics.contrast = static_cast<float>(p95 - p5) / 255.0f;

  cv::Mat lap;
  cv::Laplacian(roi, lap, CV_32F);
  cv::Scalar mean;
  cv::Scalar stddev;
  cv::meanStdDev(lap, mean, stddev);
  const double variance = stddev[0] * stddev[0];
  metrics.sharpness = clamp01(static_cast<float>((variance - 20.0) / (250.0 - 20.0)));

  const double brightPixels = static_cast<double>(cv::countNonZero(roi > 245));
  metrics.glare = static_cast<float>(brightPixels / static_cast<double>(roi.total()));
  return metrics;
}

} // namespace omr
