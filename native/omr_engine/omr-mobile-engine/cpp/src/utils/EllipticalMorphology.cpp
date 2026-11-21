#include "EllipticalMorphology.hpp"

#include <algorithm>
#include <vector>
#include <opencv2/imgproc.hpp>

namespace omr {
namespace {
struct Rectangle { int width; int height; };

template<bool Maximum>
void mergeRow(const unsigned char* a, const unsigned char* b, unsigned char* out, int count) {
  for (int x = 0; x < count; ++x)
    out[x] = Maximum ? std::max(a[x], b[x]) : std::min(a[x], b[x]);
}

// Prefix/suffix extrema give an exact centered vertical rectangle in linear
// time, independent of its height. Neutral padding matches isolated OpenCV
// morphology at every image edge, including kernels larger than the image.
template<bool Maximum>
void vertical(const cv::Mat& source, int height, cv::Mat& output,
              cv::Mat& prefixStorage, cv::Mat& suffixStorage) {
  const int radius = height / 2;
  const int rows = source.rows + height - 1;
  const unsigned char neutral = Maximum ? 0 : 255;
  const int width = source.cols;
  cv::parallel_for_(cv::Range(0,(rows+height-1)/height),[&](const cv::Range& range) {
    for (int block = range.start; block < range.end; ++block) {
      const int begin=block*height, end=std::min(rows,begin+height);
      for (int y = begin; y < end; ++y) {
        auto* dst = prefixStorage.ptr<unsigned char>(y);
        const int sy = y - radius;
        if (sy < 0 || sy >= source.rows) std::fill_n(dst, width, neutral);
        else std::copy_n(source.ptr<unsigned char>(sy), width, dst);
        if (y > begin)
          mergeRow<Maximum>(prefixStorage.ptr<unsigned char>(y - 1), dst, dst, width);
      }
      for (int y = end - 1; y >= begin; --y) {
        auto* dst = suffixStorage.ptr<unsigned char>(y);
        const int sy = y - radius;
        if (sy < 0 || sy >= source.rows) std::fill_n(dst, width, neutral);
        else std::copy_n(source.ptr<unsigned char>(sy), width, dst);
        if (y + 1 < end)
          mergeRow<Maximum>(suffixStorage.ptr<unsigned char>(y + 1), dst, dst, width);
      }
    }
  });
  output.create(source.size(), CV_8UC1);
  cv::parallel_for_(cv::Range(0,source.rows),[&](const cv::Range& range) {
    for (int y = range.start; y < range.end; ++y)
      mergeRow<Maximum>(suffixStorage.ptr<unsigned char>(y),
        prefixStorage.ptr<unsigned char>(y + height - 1), output.ptr<unsigned char>(y), width);
  });
}

template<bool Maximum>
cv::Mat extremum(const cv::Mat& source, const std::vector<Rectangle>& rectangles, int kernelSize) {
  cv::Mat horizontal = source.clone(), next, part, result;
  cv::Mat prefix(source.rows + kernelSize - 1, source.cols, CV_8UC1);
  cv::Mat suffix(prefix.size(), CV_8UC1);
  int previousWidth = 1;
  for (const auto& rectangle : rectangles) {
    // Centered intervals compose by adding their radii. Reuse the preceding
    // horizontal result instead of applying each large rectangle from scratch.
    const int growth = rectangle.width - previousWidth;
    if (growth > 0) {
      const auto kernel = cv::getStructuringElement(cv::MORPH_RECT, cv::Size(growth + 1, 1));
      constexpr int border = cv::BORDER_CONSTANT | cv::BORDER_ISOLATED;
      if constexpr (Maximum) cv::dilate(horizontal, next, kernel, cv::Point(-1,-1), 1, border);
      else cv::erode(horizontal, next, kernel, cv::Point(-1,-1), 1, border);
      std::swap(horizontal, next);
    }
    previousWidth = rectangle.width;
    vertical<Maximum>(horizontal, rectangle.height, part, prefix, suffix);
    if (result.empty()) part.copyTo(result);
    else if constexpr (Maximum) cv::max(result, part, result);
    else cv::min(result, part, result);
  }
  return result;
}
} // namespace

cv::Mat ellipticalBlackHat(const cv::Mat& gray, int kernelSize) {
  CV_Assert(!gray.empty() && gray.type() == CV_8UC1 && kernelSize >= 3 && kernelSize % 2 == 1);
  const auto ellipse = cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(kernelSize,kernelSize));
  // Preserve OpenCV's actual rasterized ellipse; no shape approximation,
  // downsampling, changed thresholds or reduced registration evidence.
  std::vector<Rectangle> rectangles;
  int previousWidth = 0;
  for (int y = 0; y <= ellipse.rows / 2; ++y) {
    const int width = cv::countNonZero(ellipse.row(y));
    if (width == previousWidth || width == 0) continue;
    rectangles.push_back({width, ellipse.rows - 2*y});
    previousWidth = width;
  }
  const auto dilated = extremum<true>(gray,rectangles,kernelSize);
  const auto closed = extremum<false>(dilated,rectangles,kernelSize);
  cv::Mat contrast;
  cv::subtract(closed,gray,contrast);
  return contrast;
}
} // namespace omr
