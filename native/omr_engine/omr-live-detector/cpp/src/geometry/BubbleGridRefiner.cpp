#include "geometry/BubbleGridRefiner.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <numeric>
#include <opencv2/calib3d.hpp>
#include <opencv2/imgproc.hpp>

namespace omr {

namespace {

constexpr int kLocalSearchRadius = 12;
constexpr float kMinLocalConfidence = 0.28f;
constexpr float kMaxCenterShift = 10.0f;
constexpr const char* kLegacyBlockGridAffineMode = "block_grid_affine";

struct RingCandidate {
  Point center;
  float radius = 0.0f;
  float quality = 0.0f;
};

struct LocalBubbleCenter {
  Point expected;
  Point detected;
  Point snapped;
  float confidence = 0.0f;
  bool accepted = false;
  bool hasRing = false;
};

struct LocalMagentaCenter {
  Point center;
  float confidence = 0.0f;
  float quality = 0.0f;
  int pixelCount = 0;
  int angleBins = 0;
  bool found = false;
  bool fitEligible = false;
};

struct FinalBubbleCenter {
  Point refined;
  Point blockAffine;
  Point rowCorrected;
  float confidence = 0.0f;
  float rowConfidence = 0.0f;
  std::string mode = "template_fallback";
  bool usedForFit = false;
};

struct CorrectionResult {
  std::vector<FinalBubbleCenter> centers;
  OmrBubbleGridStats stats;
};

float clampFloat(float value, float minValue, float maxValue) {
  return std::min(maxValue, std::max(minValue, value));
}

double clampDouble(double value, double minValue, double maxValue) {
  return std::min(maxValue, std::max(minValue, value));
}

float median(std::vector<float> values) {
  if (values.empty()) {
    return 0.0f;
  }
  std::sort(values.begin(), values.end());
  const size_t middle = values.size() / 2;
  if (values.size() % 2 == 1) {
    return values[middle];
  }
  return (values[middle - 1] + values[middle]) * 0.5f;
}

bool isMagentaColor(int blue, int green, int red) {
  const int maxChannel = std::max({red, green, blue});
  const int minChannel = std::min({red, green, blue});

  const bool pinkRing =
    red >= 95 &&
    blue >= 35 &&
    red > green + 16 &&
    blue > green * 0.45 &&
    (maxChannel - minChannel) >= 24;
  const bool redMagenta =
    red >= 105 &&
    red > green + 25 &&
    blue >= 25 &&
    green <= 170;
  return pinkRing || redMagenta;
}

cv::Mat createMagentaMask(const cv::Mat& bgr) {
  if (bgr.empty() || bgr.channels() < 3) {
    return {};
  }

  cv::Mat hsv;
  cv::cvtColor(bgr, hsv, cv::COLOR_BGR2HSV);
  cv::Mat mask(bgr.rows, bgr.cols, CV_8UC1, cv::Scalar(0));
  const int channels = bgr.channels();
  // Each output row is independent. OpenCV's scheduler keeps the exact same
  // pixel predicate while distributing this full-page pass across available
  // cores on mobile CPUs.
  cv::parallel_for_(cv::Range(0, bgr.rows), [&](const cv::Range& range) {
    for (int y = range.start; y < range.end; ++y) {
      const uint8_t* hsvRow = hsv.ptr<uint8_t>(y);
      const uint8_t* bgrRow = bgr.ptr<uint8_t>(y);
      uint8_t* maskRow = mask.ptr<uint8_t>(y);
      for (int x = 0; x < bgr.cols; ++x) {
        const int hue = hsvRow[x * 3 + 0];
        const int saturation = hsvRow[x * 3 + 1];
        const int value = hsvRow[x * 3 + 2];
        const uint8_t* pixel = bgrRow + x * channels;
        const bool hsvPink =
          saturation >= 42 &&
          value >= 65 &&
          ((hue >= 132 && hue <= 179) || hue <= 10);
        if (hsvPink || isMagentaColor(pixel[0], pixel[1], pixel[2])) {
          maskRow[x] = 255;
        }
      }
    }
  });

  const cv::Mat kernel = cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(3, 3));
  cv::morphologyEx(mask, mask, cv::MORPH_CLOSE, kernel, cv::Point(-1, -1), 1);
  cv::morphologyEx(mask, mask, cv::MORPH_OPEN, kernel, cv::Point(-1, -1), 1);
  return mask;
}

std::vector<RingCandidate> detectRingCandidates(const cv::Mat& magentaMask) {
  std::vector<RingCandidate> candidates;
  if (magentaMask.empty()) {
    return candidates;
  }

  std::vector<std::vector<cv::Point>> contours;
  cv::findContours(magentaMask, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
  candidates.reserve(contours.size());
  for (const auto& contour : contours) {
    if (contour.size() < 5) {
      continue;
    }

    const double area = cv::contourArea(contour);
    if (area < 18.0 || area > 1400.0) {
      continue;
    }

    const double perimeter = cv::arcLength(contour, true);
    if (perimeter <= 1.0) {
      continue;
    }

    const cv::Rect bbox = cv::boundingRect(contour);
    if (bbox.width < 5 || bbox.height < 5) {
      continue;
    }
    const float aspect = static_cast<float>(bbox.width) / static_cast<float>(std::max(1, bbox.height));
    if (aspect < 0.48f || aspect > 2.05f) {
      continue;
    }

    cv::Point2f circleCenter;
    float circleRadius = 0.0f;
    cv::minEnclosingCircle(contour, circleCenter, circleRadius);
    if (circleRadius < 4.5f || circleRadius > 24.0f) {
      continue;
    }

    const double circularity = 4.0 * CV_PI * area / (perimeter * perimeter);
    const double aspectScore = 1.0 - std::min(1.0, std::abs(static_cast<double>(aspect) - 1.0));
    const double radiusScore = 1.0 - std::min(1.0, std::abs(static_cast<double>(circleRadius) - 15.0) / 11.0);
    const double fillRatio = static_cast<double>(cv::countNonZero(magentaMask(bbox))) /
                             static_cast<double>(std::max(1, bbox.width * bbox.height));
    const double quality = clampDouble(
      circularity * 0.40 + aspectScore * 0.25 + radiusScore * 0.22 + std::min(1.0, fillRatio * 5.0) * 0.13,
      0.0,
      1.0
    );
    if (quality < 0.22) {
      continue;
    }

    cv::Moments moments = cv::moments(contour);
    Point center{
      static_cast<float>(circleCenter.x),
      static_cast<float>(circleCenter.y),
    };
    if (std::abs(moments.m00) > 1e-4) {
      center = {
        static_cast<float>(moments.m10 / moments.m00),
        static_cast<float>(moments.m01 / moments.m00),
      };
    }
    candidates.push_back({center, circleRadius, static_cast<float>(quality)});
  }
  return candidates;
}

class PixelEvidenceView {
public:
  PixelEvidenceView(const cv::Mat& gray, const cv::Mat& bgr)
    : gray_(gray),
      bgr_(bgr),
      hasGray_(!gray.empty()),
      hasColor_(!bgr.empty() && bgr.channels() >= 3),
      columns_(hasGray_ ? gray.cols : bgr.cols),
      rows_(hasGray_ ? gray.rows : bgr.rows),
      colorColumns_(hasColor_ ? bgr.cols : 0),
      colorRows_(hasColor_ ? bgr.rows : 0),
      colorChannels_(hasColor_ ? bgr.channels() : 0) {}

  bool inBounds(int x, int y) const {
    return x >= 0 && y >= 0 && x < columns_ && y < rows_;
  }

  bool isMagenta(int x, int y) const {
    if (!hasColor_ || x < 0 || y < 0 || x >= colorColumns_ || y >= colorRows_) {
      return false;
    }
    const uint8_t* pixel = bgr_.ptr<uint8_t>(y) + x * colorChannels_;
    return isMagentaColor(pixel[0], pixel[1], pixel[2]);
  }

  bool isDarkInk(int x, int y) const {
    int grayValue = 255;
    if (hasGray_) {
      grayValue = gray_.ptr<uint8_t>(y)[x];
    } else if (hasColor_) {
      const uint8_t* pixel = bgr_.ptr<uint8_t>(y) + x * colorChannels_;
      grayValue = static_cast<int>(std::round(
        pixel[2] * 0.299 + pixel[1] * 0.587 + pixel[0] * 0.114
      ));
    }
    if (grayValue > 118) {
      return false;
    }
    if (hasColor_ && x >= 0 && y >= 0 && x < colorColumns_ && y < colorRows_) {
      const uint8_t* pixel = bgr_.ptr<uint8_t>(y) + x * colorChannels_;
      const int blue = pixel[0];
      const int green = pixel[1];
      const int red = pixel[2];
      const int maxChannel = std::max({red, green, blue});
      const int minChannel = std::min({red, green, blue});
      return maxChannel <= 155 && (maxChannel - minChannel) <= 70;
    }
    return true;
  }

private:
  const cv::Mat& gray_;
  const cv::Mat& bgr_;
  bool hasGray_ = false;
  bool hasColor_ = false;
  int columns_ = 0;
  int rows_ = 0;
  int colorColumns_ = 0;
  int colorRows_ = 0;
  int colorChannels_ = 0;
};

const std::array<Point, 40>& annulusUnitOffsets() {
  static const std::array<Point, 40> offsets = [] {
    std::array<Point, 40> values{};
    for (std::size_t index = 0; index < values.size(); ++index) {
      const float angle = static_cast<float>(index) *
        2.0f * static_cast<float>(CV_PI) / static_cast<float>(values.size());
      values[index] = {std::cos(angle), std::sin(angle)};
    }
    return values;
  }();
  return offsets;
}

float annulusEvidence(
  const PixelEvidenceView& pixels,
  float centerX,
  float centerY,
  float radius
) {
  const float innerRadius = radius * 0.78f;
  const float outerRadius = radius * 1.08f;
  float evidence = 0.0f;
  int samples = 0;

  for (const Point unit : annulusUnitOffsets()) {
    for (const float sampleRadius : {innerRadius, radius, outerRadius}) {
      const int x = static_cast<int>(std::round(centerX + unit.x * sampleRadius));
      const int y = static_cast<int>(std::round(centerY + unit.y * sampleRadius));
      if (!pixels.inBounds(x, y)) {
        continue;
      }
      samples += 1;
      if (pixels.isMagenta(x, y)) {
        evidence += 1.0f;
      } else if (pixels.isDarkInk(x, y)) {
        evidence += 0.35f;
      }
    }
  }

  return samples > 0 ? evidence / static_cast<float>(samples) : 0.0f;
}

float innerInkEvidence(
  const PixelEvidenceView& pixels,
  float centerX,
  float centerY,
  float radius
) {
  const float scoreRadius = radius * 0.42f;
  const float step = std::max(2.0f, radius / 5.5f);
  int dark = 0;
  int samples = 0;

  for (float dy = -scoreRadius; dy <= scoreRadius; dy += step) {
    for (float dx = -scoreRadius; dx <= scoreRadius; dx += step) {
      if (dx * dx + dy * dy > scoreRadius * scoreRadius) {
        continue;
      }
      const int x = static_cast<int>(std::round(centerX + dx));
      const int y = static_cast<int>(std::round(centerY + dy));
      if (!pixels.inBounds(x, y)) {
        continue;
      }
      samples += 1;
      if (pixels.isDarkInk(x, y)) {
        dark += 1;
      }
    }
  }

  return samples > 0 ? static_cast<float>(dark) / static_cast<float>(samples) : 0.0f;
}

std::vector<cv::Point2f> collectLocalMagentaAnnulusPixels(
  const cv::Mat& bgr,
  Point expected,
  Point annulusCenter,
  float radius
) {
  std::vector<cv::Point2f> pixels;
  if (bgr.empty() || bgr.channels() < 3) {
    return pixels;
  }

  const float minRadius = radius * 0.55f;
  const float maxRadius = radius * 1.55f;
  const int window = static_cast<int>(std::ceil(std::max(radius * 1.85f, static_cast<float>(kLocalSearchRadius) + radius * 0.85f)));
  const int minX = std::max(0, static_cast<int>(std::floor(expected.x - static_cast<float>(window))));
  const int maxX = std::min(bgr.cols - 1, static_cast<int>(std::ceil(expected.x + static_cast<float>(window))));
  const int minY = std::max(0, static_cast<int>(std::floor(expected.y - static_cast<float>(window))));
  const int maxY = std::min(bgr.rows - 1, static_cast<int>(std::ceil(expected.y + static_cast<float>(window))));

  const int channels = bgr.channels();
  pixels.reserve(static_cast<size_t>(std::max(0, maxX - minX + 1) * std::max(0, maxY - minY + 1) / 6));
  for (int y = minY; y <= maxY; ++y) {
    const uint8_t* row = bgr.ptr<uint8_t>(y);
    for (int x = minX; x <= maxX; ++x) {
      const uint8_t* pixel = row + x * channels;
      if (!isMagentaColor(pixel[0], pixel[1], pixel[2])) {
        continue;
      }
      const float dx = static_cast<float>(x) - annulusCenter.x;
      const float dy = static_cast<float>(y) - annulusCenter.y;
      const float annulusDistance = std::sqrt(dx * dx + dy * dy);
      if (annulusDistance < minRadius || annulusDistance > maxRadius) {
        continue;
      }
      pixels.push_back(cv::Point2f(static_cast<float>(x), static_cast<float>(y)));
    }
  }
  return pixels;
}

Point meanPoint(const std::vector<cv::Point2f>& pixels) {
  Point center{0.0f, 0.0f};
  if (pixels.empty()) {
    return center;
  }
  for (const auto& pixel : pixels) {
    center.x += pixel.x;
    center.y += pixel.y;
  }
  center.x /= static_cast<float>(pixels.size());
  center.y /= static_cast<float>(pixels.size());
  return center;
}

LocalMagentaCenter detectLocalMagentaRingCenter(
  const cv::Mat& bgr,
  const PixelEvidenceView& evidencePixels,
  Point expected,
  float radius
) {
  LocalMagentaCenter result;
  result.center = expected;
  if (bgr.empty()) {
    return result;
  }

  std::vector<cv::Point2f> pixels = collectLocalMagentaAnnulusPixels(bgr, expected, expected, radius);
  if (pixels.size() < 28) {
    return result;
  }

  Point center = meanPoint(pixels);
  for (int iteration = 0; iteration < 2; ++iteration) {
    std::vector<cv::Point2f> refinedPixels = collectLocalMagentaAnnulusPixels(bgr, expected, center, radius);
    if (refinedPixels.size() < 28) {
      break;
    }
    pixels = std::move(refinedPixels);
    center = meanPoint(pixels);
  }

  std::array<bool, 24> angleSeen{};
  float radialError = 0.0f;
  for (const auto& pixel : pixels) {
    const float dx = pixel.x - center.x;
    const float dy = pixel.y - center.y;
    const float pixelRadius = std::sqrt(dx * dx + dy * dy);
    radialError += std::abs(pixelRadius - radius) / std::max(1.0f, radius);

    float angle = std::atan2(dy, dx);
    if (angle < 0.0f) {
      angle += 2.0f * static_cast<float>(CV_PI);
    }
    const int bin = std::min<int>(
      static_cast<int>(angleSeen.size()) - 1,
      static_cast<int>(std::floor(angle / (2.0f * static_cast<float>(CV_PI)) * static_cast<float>(angleSeen.size())))
    );
    angleSeen[static_cast<size_t>(std::max(0, bin))] = true;
  }

  int angleBins = 0;
  for (const bool seen : angleSeen) {
    if (seen) {
      angleBins += 1;
    }
  }

  radialError /= static_cast<float>(std::max<size_t>(1, pixels.size()));
  const float searchRadius = static_cast<float>(kLocalSearchRadius) + radius * 0.25f;
  const float shift = distance(expected, center);
  const float countScore = clampFloat(static_cast<float>(pixels.size()) / 260.0f, 0.0f, 1.0f);
  const float coverageScore = clampFloat(static_cast<float>(angleBins) / static_cast<float>(angleSeen.size()), 0.0f, 1.0f);
  const float radiusScore = clampFloat(1.0f - radialError, 0.0f, 1.0f);
  const float ringEvidence = annulusEvidence(evidencePixels, center.x, center.y, radius);
  const float inkEvidence = innerInkEvidence(evidencePixels, center.x, center.y, radius);
  const float shiftPenalty = clampFloat(shift / std::max(1.0f, searchRadius), 0.0f, 1.0f);

  const float quality = countScore * 0.30f +
                        coverageScore * 0.32f +
                        radiusScore * 0.20f +
                        ringEvidence * 0.24f -
                        shiftPenalty * 0.12f -
                        inkEvidence * 0.18f;

  result.center = center;
  result.pixelCount = static_cast<int>(pixels.size());
  result.angleBins = angleBins;
  result.quality = quality;
  result.confidence = clampFloat((quality - 0.18f) / 0.55f, 0.0f, 1.0f);
  result.found =
    pixels.size() >= 35 &&
    angleBins >= 7 &&
    shift <= searchRadius + 2.0f &&
    quality >= 0.22f;
  result.fitEligible =
    result.found &&
    result.confidence >= kMinLocalConfidence &&
    pixels.size() >= 90 &&
    angleBins >= 10 &&
    inkEvidence <= 0.62f &&
    shift <= searchRadius;
  return result;
}

LocalBubbleCenter snapCenterLocal(
  const cv::Mat& bgr,
  const cv::Mat& gray,
  const std::vector<RingCandidate>& ringCandidates,
  Point expected,
  float radius
) {
  LocalBubbleCenter result;
  const PixelEvidenceView pixels(gray, bgr);
  result.expected = expected;
  result.detected = expected;
  result.snapped = expected;

  bool foundVisualRing = false;
  float bestRingScore = -1.0f;
  RingCandidate bestRing;
  const float searchRadius = static_cast<float>(kLocalSearchRadius) + radius * 0.22f;
  for (const auto& candidate : ringCandidates) {
    const float shift = distance(expected, candidate.center);
    if (shift > searchRadius) {
      continue;
    }
    const float radiusRatio = candidate.radius / std::max(1.0f, radius);
    if (radiusRatio < 0.45f || radiusRatio > 1.75f) {
      continue;
    }
    const float distancePenalty = shift / std::max(1.0f, searchRadius);
    const float radiusPenalty = std::abs(radiusRatio - 1.0f);
    const float annulus = annulusEvidence(pixels, candidate.center.x, candidate.center.y, radius);
    const float score = candidate.quality * 0.58f + annulus * 0.30f - distancePenalty * 0.20f - radiusPenalty * 0.10f;
    if (score > bestRingScore) {
      bestRingScore = score;
      bestRing = candidate;
    }
  }

  if (bestRingScore >= 0.24f) {
    foundVisualRing = true;
    result.hasRing = true;
    result.detected = bestRing.center;
    result.snapped = bestRing.center;
    result.confidence = clampFloat((bestRingScore - 0.12f) / 0.48f, 0.0f, 1.0f);
    result.accepted = result.confidence >= kMinLocalConfidence;
    if (result.accepted) {
      return result;
    }
  }

  const LocalMagentaCenter localRing = detectLocalMagentaRingCenter(bgr, pixels, expected, radius);
  if (localRing.found && localRing.confidence >= result.confidence) {
    foundVisualRing = true;
    result.hasRing = true;
    result.detected = localRing.center;
    result.snapped = localRing.center;
    result.confidence = localRing.confidence;
    result.accepted = localRing.fitEligible;
    if (result.accepted) {
      return result;
    }
  }

  if (foundVisualRing) {
    return result;
  }

  float bestScore = -1.0f;
  Point best = expected;
  for (int dy = -kLocalSearchRadius; dy <= kLocalSearchRadius; ++dy) {
    for (int dx = -kLocalSearchRadius; dx <= kLocalSearchRadius; ++dx) {
      const float x = expected.x + static_cast<float>(dx);
      const float y = expected.y + static_cast<float>(dy);
      if (x < 0.0f || y < 0.0f || x >= static_cast<float>(bgr.cols) || y >= static_cast<float>(bgr.rows)) {
        continue;
      }
      const float ring = annulusEvidence(pixels, x, y, radius);
      const float ink = innerInkEvidence(pixels, x, y, radius);
      const float prior = std::sqrt(static_cast<float>(dx * dx + dy * dy)) / static_cast<float>(kLocalSearchRadius);
      const float score = ring * 0.86f + ink * 0.08f - prior * 0.18f;
      if (score > bestScore) {
        bestScore = score;
        best = {x, y};
      }
    }
  }

  result.confidence = std::max(result.confidence, clampFloat((bestScore - 0.10f) / 0.48f, 0.0f, 0.72f));
  result.accepted =
    result.confidence >= 0.62f &&
    distance(expected, best) <= std::min(static_cast<float>(kLocalSearchRadius), radius * 0.50f);
  result.snapped = result.accepted ? best : expected;
  if (result.accepted) {
    result.detected = best;
  }
  return result;
}

std::vector<float> authoritativeAnswerRowYs(const OmrTemplate& tpl) {
  int expectedRows = 0;
  for (const auto& block : tpl.answers.blocks) {
    expectedRows = std::max(expectedRows, block.questionCount);
  }

  std::vector<float> rows;
  rows.reserve(tpl.registration.rowY.size());
  const float top = tpl.answersRegion.y - 1.0f;
  const float bottom = tpl.answersRegion.y + tpl.answersRegion.height + 1.0f;
  for (const float y : tpl.registration.rowY) {
    if (y >= top && y <= bottom) {
      rows.push_back(y);
    }
  }
  std::sort(rows.begin(), rows.end());
  if (expectedRows > 0 && static_cast<int>(rows.size()) >= expectedRows) {
    rows.resize(static_cast<size_t>(expectedRows));
    return rows;
  }

  for (const auto& block : tpl.answers.blocks) {
    if (expectedRows > 0 && static_cast<int>(block.yByRow.size()) >= expectedRows) {
      return std::vector<float>(block.yByRow.begin(), block.yByRow.begin() + expectedRows);
    }
  }
  return rows;
}

float answerRowY(const std::vector<float>& rows, const OmrAnswerBlockTemplate& block, int row) {
  if (row >= 0 && row < static_cast<int>(rows.size())) {
    return rows[static_cast<size_t>(row)];
  }
  return block.yByRow[static_cast<size_t>(row)];
}

bool solveAffine(
  const std::vector<LocalBubbleCenter>& localCenters,
  const std::vector<size_t>& usedIndexes,
  std::array<double, 6>& affine
) {
  if (usedIndexes.size() < 6) {
    return false;
  }

  cv::Mat a(static_cast<int>(usedIndexes.size()), 3, CV_64F);
  cv::Mat bx(static_cast<int>(usedIndexes.size()), 1, CV_64F);
  cv::Mat by(static_cast<int>(usedIndexes.size()), 1, CV_64F);
  for (size_t row = 0; row < usedIndexes.size(); ++row) {
    const LocalBubbleCenter& center = localCenters[usedIndexes[row]];
    a.at<double>(static_cast<int>(row), 0) = center.expected.x;
    a.at<double>(static_cast<int>(row), 1) = center.expected.y;
    a.at<double>(static_cast<int>(row), 2) = 1.0;
    bx.at<double>(static_cast<int>(row), 0) = center.snapped.x;
    by.at<double>(static_cast<int>(row), 0) = center.snapped.y;
  }

  cv::Mat sx;
  cv::Mat sy;
  if (!cv::solve(a, bx, sx, cv::DECOMP_SVD) || !cv::solve(a, by, sy, cv::DECOMP_SVD)) {
    return false;
  }

  affine = {
    sx.at<double>(0, 0), sx.at<double>(1, 0), sx.at<double>(2, 0),
    sy.at<double>(0, 0), sy.at<double>(1, 0), sy.at<double>(2, 0),
  };
  return true;
}

Point applyAffine(const std::array<double, 6>& affine, Point point) {
  return {
    static_cast<float>(affine[0] * point.x + affine[1] * point.y + affine[2]),
    static_cast<float>(affine[3] * point.x + affine[4] * point.y + affine[5]),
  };
}

Point clampedRefinedPoint(Point expected, Point refined) {
  const float shift = distance(expected, refined);
  if (shift <= kMaxCenterShift || shift <= 0.001f) {
    return refined;
  }
  const float scale = kMaxCenterShift / shift;
  return {
    expected.x + (refined.x - expected.x) * scale,
    expected.y + (refined.y - expected.y) * scale,
  };
}

void calculateShiftStats(
  const std::vector<LocalBubbleCenter>& localCenters,
  CorrectionResult& correction
) {
  if (localCenters.empty() || correction.centers.empty()) {
    return;
  }

  double sumDx = 0.0;
  double sumDy = 0.0;
  double sumShiftSq = 0.0;
  double sumFitResidualSq = 0.0;
  int fitResidualCount = 0;
  double maxShift = 0.0;
  std::vector<float> topDy;
  std::vector<float> bottomDy;
  std::vector<float> leftDx;
  std::vector<float> rightDx;

  std::vector<float> xs;
  std::vector<float> ys;
  xs.reserve(localCenters.size());
  ys.reserve(localCenters.size());
  for (const auto& center : localCenters) {
    xs.push_back(center.expected.x);
    ys.push_back(center.expected.y);
  }
  const float medianX = median(xs);
  const float medianY = median(ys);

  for (size_t index = 0; index < localCenters.size(); ++index) {
    const Point expected = localCenters[index].expected;
    const Point refined = correction.centers[index].refined;
    const double dx = static_cast<double>(refined.x - expected.x);
    const double dy = static_cast<double>(refined.y - expected.y);
    const double shift = std::sqrt(dx * dx + dy * dy);
    sumDx += dx;
    sumDy += dy;
    sumShiftSq += shift * shift;
    maxShift = std::max(maxShift, shift);
    if (correction.centers[index].usedForFit && localCenters[index].hasRing) {
      const double residual = distance(refined, localCenters[index].snapped);
      sumFitResidualSq += residual * residual;
      fitResidualCount += 1;
    }

    if (expected.y <= medianY) topDy.push_back(static_cast<float>(dy));
    else bottomDy.push_back(static_cast<float>(dy));
    if (expected.x <= medianX) leftDx.push_back(static_cast<float>(dx));
    else rightDx.push_back(static_cast<float>(dx));
  }

  const double count = static_cast<double>(localCenters.size());
  correction.stats.meanDx = sumDx / count;
  correction.stats.meanDy = sumDy / count;
  correction.stats.appliedShiftRms = std::sqrt(sumShiftSq / count);
  correction.stats.fitResidualRms = fitResidualCount > 0
    ? std::sqrt(sumFitResidualSq / static_cast<double>(fitResidualCount))
    : 99.0;
  correction.stats.rmsError = correction.stats.appliedShiftRms;
  correction.stats.maxError = maxShift;
  correction.stats.verticalDrift = static_cast<double>(median(bottomDy) - median(topDy));
  correction.stats.horizontalDrift = static_cast<double>(median(rightDx) - median(leftDx));
}

double residualRms(
  const std::vector<LocalBubbleCenter>& localCenters,
  const std::vector<size_t>& usedIndexes,
  const std::vector<FinalBubbleCenter>& finalCenters
) {
  if (usedIndexes.empty()) {
    return 99.0;
  }
  double sum = 0.0;
  for (const size_t index : usedIndexes) {
    const double residual = distance(finalCenters[index].refined, localCenters[index].snapped);
    sum += residual * residual;
  }
  return std::sqrt(sum / static_cast<double>(usedIndexes.size()));
}

struct RowResidualCorrection {
  std::vector<float> rowDx;
  std::vector<float> rowDy;
  std::vector<float> confidence;
  std::vector<OmrAnswerRowResidual> residuals;
  bool applied = false;
};

std::vector<float> interpolateResiduals(const std::vector<float>& values, const std::vector<bool>& present) {
  std::vector<float> out(values.size(), 0.0f);
  if (values.empty()) {
    return out;
  }

  int firstPresent = -1;
  for (size_t index = 0; index < present.size(); ++index) {
    if (present[index]) {
      firstPresent = static_cast<int>(index);
      break;
    }
  }
  if (firstPresent < 0) {
    return out;
  }

  for (int index = 0; index <= firstPresent; ++index) {
    out[static_cast<size_t>(index)] = values[static_cast<size_t>(firstPresent)];
  }

  int previous = firstPresent;
  for (size_t index = static_cast<size_t>(firstPresent + 1); index < values.size(); ++index) {
    if (!present[index]) {
      continue;
    }
    const int current = static_cast<int>(index);
    for (int fill = previous + 1; fill <= current; ++fill) {
      const float t = static_cast<float>(fill - previous) / static_cast<float>(std::max(1, current - previous));
      out[static_cast<size_t>(fill)] = values[static_cast<size_t>(previous)] * (1.0f - t) + values[index] * t;
    }
    previous = current;
  }

  for (size_t index = static_cast<size_t>(previous + 1); index < values.size(); ++index) {
    out[index] = values[static_cast<size_t>(previous)];
  }
  return out;
}

std::vector<float> smoothResiduals(std::vector<float> values) {
  if (values.size() < 3) {
    return values;
  }
  std::vector<float> smoothed = values;
  for (size_t index = 0; index < values.size(); ++index) {
    std::vector<float> window;
    window.reserve(3);
    const size_t begin = index > 0 ? index - 1 : index;
    const size_t end = std::min(values.size() - 1, index + 1);
    for (size_t item = begin; item <= end; ++item) {
      window.push_back(values[item]);
    }
    smoothed[index] = median(std::move(window));
  }
  return smoothed;
}

RowResidualCorrection computeRowResidualCorrection(
  const std::vector<LocalBubbleCenter>& localCenters,
  const std::vector<FinalBubbleCenter>& blockCenters,
  const std::vector<int>& rowIndexes,
  const std::vector<int>& questionNumbers,
  const std::string& blockId,
  int rowCount
) {
  RowResidualCorrection correction;
  if (rowCount <= 0 || localCenters.empty() || blockCenters.size() != localCenters.size()) {
    return correction;
  }

  correction.rowDx.assign(static_cast<size_t>(rowCount), 0.0f);
  correction.rowDy.assign(static_cast<size_t>(rowCount), 0.0f);
  correction.confidence.assign(static_cast<size_t>(rowCount), 0.0f);
  std::vector<float> rawDx(static_cast<size_t>(rowCount), 0.0f);
  std::vector<float> rawDy(static_cast<size_t>(rowCount), 0.0f);
  std::vector<bool> present(static_cast<size_t>(rowCount), false);
  std::vector<int> detectedCount(static_cast<size_t>(rowCount), 0);
  std::vector<double> beforeSq(static_cast<size_t>(rowCount), 0.0);
  std::vector<double> afterSq(static_cast<size_t>(rowCount), 0.0);
  std::vector<double> detectedMedianY(static_cast<size_t>(rowCount), 0.0);

  for (int row = 0; row < rowCount; ++row) {
    std::vector<float> dxs;
    std::vector<float> dys;
    std::vector<float> detectedYs;
    for (size_t index = 0; index < localCenters.size(); ++index) {
      if (index >= rowIndexes.size() || rowIndexes[index] != row || !localCenters[index].accepted) {
        continue;
      }
      const float dx = localCenters[index].detected.x - blockCenters[index].refined.x;
      const float dy = localCenters[index].detected.y - blockCenters[index].refined.y;
      const float residual = std::sqrt(dx * dx + dy * dy);
      if (residual > 14.0f) {
        continue;
      }
      dxs.push_back(dx);
      dys.push_back(dy);
      detectedYs.push_back(localCenters[index].detected.y);
    }

    detectedCount[static_cast<size_t>(row)] = static_cast<int>(dxs.size());
    if (dxs.size() >= 2) {
      rawDx[static_cast<size_t>(row)] = median(dxs);
      rawDy[static_cast<size_t>(row)] = median(dys);
      detectedMedianY[static_cast<size_t>(row)] = median(detectedYs);
      present[static_cast<size_t>(row)] = true;
      correction.confidence[static_cast<size_t>(row)] = clampFloat(static_cast<float>(dxs.size()) / 4.0f, 0.0f, 1.0f);
    }
  }

  correction.rowDx = smoothResiduals(interpolateResiduals(rawDx, present));
  correction.rowDy = smoothResiduals(interpolateResiduals(rawDy, present));
  for (int row = 0; row < rowCount; ++row) {
    correction.rowDx[static_cast<size_t>(row)] = clampFloat(correction.rowDx[static_cast<size_t>(row)], -8.0f, 8.0f);
    correction.rowDy[static_cast<size_t>(row)] = clampFloat(correction.rowDy[static_cast<size_t>(row)], -8.0f, 8.0f);
  }

  for (size_t index = 0; index < localCenters.size(); ++index) {
    if (index >= rowIndexes.size() || !localCenters[index].accepted) {
      continue;
    }
    const int row = rowIndexes[index];
    if (row < 0 || row >= rowCount) {
      continue;
    }
    const double before = distance(blockCenters[index].refined, localCenters[index].detected);
    const Point afterPoint{
      blockCenters[index].refined.x + correction.rowDx[static_cast<size_t>(row)],
      blockCenters[index].refined.y + correction.rowDy[static_cast<size_t>(row)],
    };
    const double after = distance(afterPoint, localCenters[index].detected);
    beforeSq[static_cast<size_t>(row)] += before * before;
    afterSq[static_cast<size_t>(row)] += after * after;
  }

  correction.residuals.reserve(static_cast<size_t>(rowCount));
  for (int row = 0; row < rowCount; ++row) {
    const size_t rowIndex = static_cast<size_t>(row);
    OmrAnswerRowResidual residual;
    residual.blockId = blockId;
    residual.rowIndex = row;
    residual.questionNumber = row < static_cast<int>(questionNumbers.size()) ? questionNumbers[rowIndex] : 0;
    for (size_t index = 0; index < localCenters.size(); ++index) {
      if (index < rowIndexes.size() && rowIndexes[index] == row) {
        residual.expectedY = localCenters[index].expected.y;
        residual.refinedYBefore = blockCenters[index].refined.y;
        break;
      }
    }
    residual.detectedMedianY = detectedMedianY[rowIndex];
    residual.rowDx = rawDx[rowIndex];
    residual.rowDy = rawDy[rowIndex];
    residual.smoothedRowDx = correction.rowDx[rowIndex];
    residual.smoothedRowDy = correction.rowDy[rowIndex];
    residual.detectedCount = detectedCount[rowIndex];
    residual.confidence = correction.confidence[rowIndex];
    if (detectedCount[rowIndex] > 0) {
      residual.beforeRms = std::sqrt(beforeSq[rowIndex] / static_cast<double>(detectedCount[rowIndex]));
      residual.afterRms = std::sqrt(afterSq[rowIndex] / static_cast<double>(detectedCount[rowIndex]));
    }
    correction.residuals.push_back(residual);
  }

  double beforeTotal = 0.0;
  double afterTotal = 0.0;
  int rowsWithEvidence = 0;
  for (const auto& residual : correction.residuals) {
    if (residual.detectedCount <= 0) {
      continue;
    }
    beforeTotal += residual.beforeRms;
    afterTotal += residual.afterRms;
    rowsWithEvidence += 1;
  }
  correction.applied = rowsWithEvidence >= 6 && afterTotal <= beforeTotal * 1.03;
  return correction;
}

CorrectionResult fitCorrection(
  const std::vector<LocalBubbleCenter>& localCenters,
  const std::string& blockId,
  int minAffineCenters
) {
  CorrectionResult correction;
  correction.stats.blockId = blockId;
  correction.stats.expectedCentersCount = static_cast<int>(localCenters.size());
  correction.centers.resize(localCenters.size());

  std::vector<float> dxValues;
  std::vector<float> dyValues;
  std::vector<size_t> accepted;
  for (size_t index = 0; index < localCenters.size(); ++index) {
    const auto& center = localCenters[index];
    if (center.hasRing) {
      correction.stats.detectedCentersCount += 1;
    }
    if (!center.accepted) {
      continue;
    }
    accepted.push_back(index);
    dxValues.push_back(center.snapped.x - center.expected.x);
    dyValues.push_back(center.snapped.y - center.expected.y);
  }
  correction.stats.detectedCoverage = localCenters.empty()
    ? 0.0
    : static_cast<double>(correction.stats.detectedCentersCount) / static_cast<double>(localCenters.size());

  if (accepted.empty()) {
    correction.stats.mode = "template_fallback";
    correction.stats.fallbackReason = "no_confident_local_centers";
    for (size_t index = 0; index < localCenters.size(); ++index) {
      correction.centers[index] = {
        localCenters[index].expected,
        localCenters[index].expected,
        localCenters[index].expected,
        0.0f,
        0.0f,
        "template_fallback",
        false
      };
    }
    return correction;
  }

  const float medianDx = median(dxValues);
  const float medianDy = median(dyValues);
  std::vector<size_t> used;
  used.reserve(accepted.size());
  for (const size_t index : accepted) {
    const auto& center = localCenters[index];
    const float dx = center.snapped.x - center.expected.x;
    const float dy = center.snapped.y - center.expected.y;
    const float vectorResidual = std::sqrt((dx - medianDx) * (dx - medianDx) + (dy - medianDy) * (dy - medianDy));
    if (accepted.size() < 8 || vectorResidual <= 6.5f) {
      used.push_back(index);
    }
  }

  std::array<double, 6> affine = {1.0, 0.0, medianDx, 0.0, 1.0, medianDy};
  bool useAffine = false;
  if (static_cast<int>(used.size()) >= minAffineCenters && solveAffine(localCenters, used, affine)) {
    useAffine = true;
    for (size_t index = 0; index < localCenters.size(); ++index) {
      const Point refined = clampedRefinedPoint(localCenters[index].expected, applyAffine(affine, localCenters[index].expected));
      correction.centers[index] = {
        refined,
        refined,
        refined,
        localCenters[index].confidence,
        0.0f,
        "block_affine",
        false
      };
    }
    const double fitResidual = residualRms(localCenters, used, correction.centers);
    if (fitResidual > 5.5) {
      useAffine = false;
    }
  }

  if (!useAffine) {
    for (size_t index = 0; index < localCenters.size(); ++index) {
      Point refined = {
        localCenters[index].expected.x + medianDx,
        localCenters[index].expected.y + medianDy,
      };
      refined = clampedRefinedPoint(localCenters[index].expected, refined);
      correction.centers[index] = {
        refined,
        refined,
        refined,
        localCenters[index].confidence,
        0.0f,
        "local_center_snap",
        false
      };
    }
    correction.stats.mode = "local_center_snap";
    if (static_cast<int>(used.size()) < minAffineCenters) {
      correction.stats.fallbackReason = "too_few_centers_for_affine";
    } else {
      correction.stats.fallbackReason = "affine_unstable";
    }
  } else {
    correction.stats.mode = correction.stats.detectedCoverage >= 0.70 ? "pink_ring_grid" : "block_affine";
  }

  for (const size_t index : used) {
    correction.centers[index].usedForFit = true;
  }
  correction.stats.usedCentersCount = static_cast<int>(used.size());
  calculateShiftStats(localCenters, correction);

  const double usedRatio = localCenters.empty()
    ? 0.0
    : static_cast<double>(used.size()) / static_cast<double>(localCenters.size());
  const double residualPenalty = clampDouble(correction.stats.fitResidualRms / 5.5, 0.0, 1.0);
  correction.stats.confidence = clampDouble(usedRatio * 1.55 + (1.0 - residualPenalty) * 0.35, 0.0, 1.0);
  return correction;
}

void addStatsWarnings(const OmrBubbleGridStats& stats, std::vector<std::string>& warnings, const std::string& label) {
  if (stats.expectedCentersCount == 0) {
    return;
  }
  if (stats.usedCentersCount < std::min(12, std::max(4, stats.expectedCentersCount / 12))) {
    warnings.push_back(label + ":too_few_centers_detected");
  }
  if (stats.detectedCoverage < 0.70) {
    warnings.push_back(label + ":detected_ring_coverage_lt_70pct");
  }
  if (stats.appliedShiftRms > 8.5) {
    warnings.push_back(label + ":applied_shift_near_limit");
  }
  if (stats.usedCentersCount > 0 && stats.fitResidualRms > 3.5) {
    warnings.push_back(label + ":fit_residual_gt_3_5px");
  }
  if (std::abs(stats.verticalDrift) > 5.0) {
    warnings.push_back(label + ":vertical_drift_gt_5px");
  }
  if (std::abs(stats.horizontalDrift) > 5.0) {
    warnings.push_back(label + ":horizontal_drift_gt_5px");
  }
}

} // namespace

BubbleGridRefinement BubbleGridRefiner::refine(
  const cv::Mat& normalizedBgr,
  const cv::Mat& normalizedGray,
  const OmrTemplate& tpl
) {
  BubbleGridRefinement result;
  if (normalizedBgr.empty() && normalizedGray.empty()) {
    result.warnings.push_back("bubble_refinement:image_empty");
    return result;
  }

  cv::Mat gray = normalizedGray;
  if (gray.empty() && !normalizedBgr.empty()) {
    cv::cvtColor(normalizedBgr, gray, cv::COLOR_BGR2GRAY);
  }
  const cv::Mat magentaMask = createMagentaMask(normalizedBgr);
  const std::vector<RingCandidate> ringCandidates = detectRingCandidates(magentaMask);

  int totalUsed = 0;
  bool anyRowGrid = false;
  bool anyRowResidual = false;
  bool anyPinkGrid = false;
  bool anyAffine = false;
  bool anyLocal = false;

  if (tpl.answers.enabled) {
    const std::vector<float> rowYs = authoritativeAnswerRowYs(tpl);
    result.answerBlockStats.reserve(tpl.answers.blocks.size());
    for (const auto& block : tpl.answers.blocks) {
      const int rowCount = std::min(block.questionCount, static_cast<int>(rowYs.empty() ? block.yByRow.size() : rowYs.size()));
      const size_t expectedCenters = static_cast<size_t>(std::max(0, rowCount)) * tpl.answers.options.size();
      std::vector<LocalBubbleCenter> localCenters;
      std::vector<Point> expectedPoints;
      expectedPoints.reserve(expectedCenters);
      std::vector<int> rowIndexes;
      rowIndexes.reserve(expectedCenters);
      std::vector<int> questionNumbers;
      questionNumbers.reserve(static_cast<size_t>(std::max(0, rowCount)));
      struct Identity {
        int questionNumber = 0;
        int rowIndex = 0;
        std::string option;
      };
      std::vector<Identity> identities;
      identities.reserve(expectedCenters);

      for (int row = 0; row < rowCount; ++row) {
        const float y = answerRowY(rowYs, block, row);
        questionNumbers.push_back(block.questionStart + row);
        for (const auto& option : tpl.answers.options) {
          const auto xIt = block.xByOption.find(option);
          if (xIt == block.xByOption.end()) {
            continue;
          }
          const Point expected{xIt->second, y};
          expectedPoints.push_back(expected);
          rowIndexes.push_back(row);
          identities.push_back({block.questionStart + row, row, option});
        }
      }

      localCenters.resize(expectedPoints.size());
      cv::parallel_for_(
        cv::Range(0, static_cast<int>(expectedPoints.size())),
        [&](const cv::Range& range) {
          for (int index = range.start; index < range.end; ++index) {
            localCenters[static_cast<size_t>(index)] = snapCenterLocal(
              normalizedBgr,
              gray,
              ringCandidates,
              expectedPoints[static_cast<size_t>(index)],
              block.bubbleRadius
            );
          }
        }
      );

      CorrectionResult correction = fitCorrection(localCenters, block.id, 12);
      RowResidualCorrection rowCorrection = computeRowResidualCorrection(
        localCenters,
        correction.centers,
        rowIndexes,
        questionNumbers,
        block.id,
        rowCount
      );
      result.answerRowResiduals.insert(
        result.answerRowResiduals.end(),
        rowCorrection.residuals.begin(),
        rowCorrection.residuals.end()
      );
      if (rowCorrection.applied) {
        for (size_t index = 0; index < correction.centers.size(); ++index) {
          if (index >= rowIndexes.size()) {
            continue;
          }
          const int row = rowIndexes[index];
          if (row < 0 || row >= static_cast<int>(rowCorrection.rowDx.size())) {
            continue;
          }
          const Point base = correction.centers[index].refined;
          const Point rowCorrected{
            base.x + rowCorrection.rowDx[static_cast<size_t>(row)],
            base.y + rowCorrection.rowDy[static_cast<size_t>(row)],
          };
          correction.centers[index].blockAffine = base;
          correction.centers[index].rowCorrected = clampedRefinedPoint(localCenters[index].expected, rowCorrected);
          correction.centers[index].refined = correction.centers[index].rowCorrected;
          correction.centers[index].rowConfidence = rowCorrection.confidence[static_cast<size_t>(row)];
          correction.centers[index].mode = correction.stats.detectedCoverage >= 0.70
            ? "pink_ring_row_column_grid"
            : "block_affine_plus_row_residual";
        }
        correction.stats.mode = correction.stats.detectedCoverage >= 0.70
          ? "pink_ring_row_column_grid"
          : "block_affine_plus_row_residual";
        calculateShiftStats(localCenters, correction);
      }
      result.answerBlockStats.push_back(correction.stats);
      totalUsed += correction.stats.usedCentersCount;
      anyRowGrid = anyRowGrid || correction.stats.mode == "pink_ring_row_column_grid";
      anyRowResidual = anyRowResidual || correction.stats.mode == "block_affine_plus_row_residual";
      anyPinkGrid = anyPinkGrid || correction.stats.mode == "pink_ring_grid" || correction.stats.mode == "pink_ring_row_column_grid";
      anyAffine = anyAffine ||
        correction.stats.mode == "block_affine" ||
        correction.stats.mode == "pink_ring_grid" ||
        correction.stats.mode == "block_affine_plus_row_residual" ||
        correction.stats.mode == "pink_ring_row_column_grid";
      anyLocal = anyLocal || correction.stats.mode == "local_center_snap";
      addStatsWarnings(correction.stats, result.warnings, block.id.empty() ? "answer_block" : block.id);

      for (size_t index = 0; index < identities.size() && index < correction.centers.size(); ++index) {
        RefinedAnswerBubbleCenter center;
        center.blockId = block.id;
        center.questionNumber = identities[index].questionNumber;
        center.rowIndex = identities[index].rowIndex;
        center.option = identities[index].option;
        center.expected = localCenters[index].expected;
        center.detected = localCenters[index].detected;
        center.blockAffine = correction.centers[index].blockAffine;
        center.rowCorrected = correction.centers[index].rowCorrected;
        center.refined = correction.centers[index].refined;
        center.confidence = correction.centers[index].confidence;
        center.rowConfidence = correction.centers[index].rowConfidence;
        center.refinementMode = correction.centers[index].mode;
        center.hasDetected = localCenters[index].hasRing;
        center.usedForFit = correction.centers[index].usedForFit;
        result.answerCenters.push_back(center);
      }
    }
  }

  if (
    tpl.studentCode.enabled &&
    !tpl.studentCode.bubbleXs().empty() &&
    !tpl.studentCode.bubbleYs().empty()
  ) {
    std::vector<LocalBubbleCenter> localCenters;
    std::vector<Point> expectedPoints;
    const auto& digitLabels = tpl.studentCode.digitLabelsByChoiceOrder();
    const size_t expectedCenters = static_cast<size_t>(tpl.studentCode.columns) * digitLabels.size();
    expectedPoints.reserve(expectedCenters);
    struct Identity {
      int visualColumnIndex = 0;
      int digitIndex = 0;
      std::string digit;
    };
    std::vector<Identity> identities;
    identities.reserve(expectedCenters);
    result.studentCodeCenters.reserve(expectedCenters);

    for (size_t visualIndex = 0; visualIndex < static_cast<size_t>(tpl.studentCode.columns); ++visualIndex) {
      for (size_t digitIndex = 0; digitIndex < digitLabels.size(); ++digitIndex) {
        const Point expected = tpl.studentCode.bubbleCenter(visualIndex, digitIndex);
        expectedPoints.push_back(expected);
        const std::string digit = digitLabels[digitIndex];
        identities.push_back({static_cast<int>(visualIndex), static_cast<int>(digitIndex), digit});
      }
    }

    localCenters.resize(expectedPoints.size());
    cv::parallel_for_(
      cv::Range(0, static_cast<int>(expectedPoints.size())),
      [&](const cv::Range& range) {
        for (int index = range.start; index < range.end; ++index) {
          localCenters[static_cast<size_t>(index)] = snapCenterLocal(
            normalizedBgr,
            gray,
            ringCandidates,
            expectedPoints[static_cast<size_t>(index)],
            tpl.studentCode.bubbleRadius
          );
        }
      }
    );

    CorrectionResult correction = fitCorrection(localCenters, "student_code", 10);
    result.studentCodeStats = correction.stats;
    totalUsed += correction.stats.usedCentersCount;
    anyPinkGrid = anyPinkGrid || correction.stats.mode == "pink_ring_grid";
    anyAffine = anyAffine || correction.stats.mode == "block_affine" || correction.stats.mode == "pink_ring_grid";
    anyLocal = anyLocal || correction.stats.mode == "local_center_snap";
    addStatsWarnings(correction.stats, result.warnings, "student_code");

    for (size_t index = 0; index < identities.size() && index < correction.centers.size(); ++index) {
      RefinedStudentCodeBubbleCenter center;
      center.visualColumnIndex = identities[index].visualColumnIndex;
      center.digitIndex = identities[index].digitIndex;
      center.digit = identities[index].digit;
      center.expected = localCenters[index].expected;
      center.detected = localCenters[index].detected;
      center.refined = correction.centers[index].refined;
      center.confidence = correction.centers[index].confidence;
      center.hasDetected = localCenters[index].hasRing;
      center.usedForFit = correction.centers[index].usedForFit;
      result.studentCodeCenters.push_back(center);
    }
  }

  if (anyRowGrid) {
    result.bubbleRefinementMode = "pink_ring_row_column_grid";
  } else if (anyRowResidual) {
    result.bubbleRefinementMode = "block_affine_plus_row_residual";
  } else if (anyPinkGrid) {
    result.bubbleRefinementMode = "pink_ring_grid";
  } else if (anyAffine) {
    result.bubbleRefinementMode = "block_affine";
  } else if (anyLocal) {
    result.bubbleRefinementMode = "local_center_snap";
  } else {
    result.bubbleRefinementMode = "template_fallback";
  }
  result.templateRefinementUsed = totalUsed > 0 && result.bubbleRefinementMode != "template_fallback";

  if (result.answerBlockStats.size() > 1) {
    double meanDx = 0.0;
    double meanDy = 0.0;
    int count = 0;
    for (const auto& stats : result.answerBlockStats) {
      if (stats.usedCentersCount <= 0) continue;
      meanDx += stats.meanDx;
      meanDy += stats.meanDy;
      count += 1;
    }
    if (count > 0) {
      meanDx /= static_cast<double>(count);
      meanDy /= static_cast<double>(count);
      for (const auto& stats : result.answerBlockStats) {
        if (stats.usedCentersCount <= 0) continue;
        const double diff = std::sqrt((stats.meanDx - meanDx) * (stats.meanDx - meanDx) +
                                      (stats.meanDy - meanDy) * (stats.meanDy - meanDy));
        if (diff > 6.0) {
          result.warnings.push_back((stats.blockId.empty() ? "answer_block" : stats.blockId) + ":correction_differs_from_other_blocks");
        }
      }
      if (result.studentCodeStats.usedCentersCount > 0) {
        const double diff = std::sqrt(
          (result.studentCodeStats.meanDx - meanDx) * (result.studentCodeStats.meanDx - meanDx) +
          (result.studentCodeStats.meanDy - meanDy) * (result.studentCodeStats.meanDy - meanDy)
        );
        if (diff > 6.0) {
          result.warnings.push_back("student_code:correction_differs_from_answer_blocks");
        }
      }
    }
  }

  return result;
}

} // namespace omr
