#include "rails/SideDotRailDetector.hpp"

#include <algorithm>
#include <cmath>
#include <limits>
#include <numeric>
#include <opencv2/imgproc.hpp>
#include "utils/EllipticalMorphology.hpp"

namespace omr {

float RailModel::xAtY(float y) const {
  if (std::abs(lineA) < 1e-5f) {
    return dots.empty() ? 0.0f : dots.front().x;
  }
  return -(lineB * y + lineC) / lineA;
}

namespace {

float circularity(float area, float perimeter) {
  if (perimeter <= 0.0f) {
    return 0.0f;
  }
  return static_cast<float>((4.0 * CV_PI * area) / (perimeter * perimeter));
}

float distanceToLine(float a, float b, float c, const DotCandidate& dot) {
  return std::abs(a * dot.x + b * dot.y + c) / std::sqrt(a * a + b * b);
}

float medianFloat(std::vector<float> values) {
  if (values.empty()) {
    return 0.0f;
  }
  const size_t middle = values.size() / 2;
  std::nth_element(values.begin(), values.begin() + middle, values.end());
  float median = values[middle];
  if (values.size() % 2 == 0) {
    std::nth_element(values.begin(), values.begin() + middle - 1, values.end());
    median = (median + values[middle - 1]) * 0.5f;
  }
  return median;
}

std::vector<DotCandidate> sortedByY(std::vector<DotCandidate> dots) {
  std::sort(dots.begin(), dots.end(), [](const auto& lhs, const auto& rhs) {
    return lhs.y < rhs.y;
  });
  return dots;
}

float regularityError(const std::vector<DotCandidate>& dots) {
  if (dots.size() < 3) {
    return 1.0f;
  }

  const std::vector<DotCandidate> sorted = sortedByY(dots);
  std::vector<float> gaps;
  gaps.reserve(sorted.size() - 1);
  for (size_t index = 1; index < sorted.size(); ++index) {
    const float gap = sorted[index].y - sorted[index - 1].y;
    if (gap > 1.0f) {
      gaps.push_back(gap);
    }
  }
  const float medianGap = std::max(1.0f, medianFloat(gaps));
  float error = 0.0f;
  for (const float gap : gaps) {
    error += std::abs(gap - medianGap) / medianGap;
  }
  return gaps.empty() ? 1.0f : error / static_cast<float>(gaps.size());
}

float lineResidualRms(const std::vector<DotCandidate>& dots, const RailModel& rail) {
  if (dots.empty()) {
    return std::numeric_limits<float>::infinity();
  }
  double squaredError = 0.0;
  for (const auto& dot : dots) {
    const double error = distanceToLine(rail.lineA, rail.lineB, rail.lineC, dot);
    squaredError += error * error;
  }
  return static_cast<float>(std::sqrt(squaredError / static_cast<double>(dots.size())));
}

float centerX(const std::vector<DotCandidate>& dots) {
  if (dots.empty()) {
    return 0.0f;
  }
  float sum = 0.0f;
  for (const auto& dot : dots) {
    sum += dot.x;
  }
  return sum / static_cast<float>(dots.size());
}

float contourDarkness(const cv::Mat& gray, const std::vector<cv::Point>& contour, const cv::Rect& bounds) {
  cv::Mat componentMask(bounds.height, bounds.width, CV_8UC1, cv::Scalar(0));
  std::vector<std::vector<cv::Point>> shiftedContours(1);
  shiftedContours.front().reserve(contour.size());
  for (const cv::Point& point : contour) {
    shiftedContours.front().push_back({point.x - bounds.x, point.y - bounds.y});
  }

  cv::drawContours(componentMask, shiftedContours, 0, 255, cv::FILLED);
  const cv::Scalar mean = cv::mean(gray(bounds), componentMask);
  return 1.0f - static_cast<float>(mean[0]) / 255.0f;
}

float rowMatchScoreForDots(const std::vector<DotCandidate>& dots, const std::vector<float>& rows, float expectedRadius) {
  if (dots.size() < 2 || rows.size() < 2) {
    return 0.0f;
  }

  const std::vector<DotCandidate> sorted = sortedByY(dots);
  std::vector<float> expectedRows;
  expectedRows.reserve(sorted.size());
  if (sorted.size() == rows.size()) {
    expectedRows = rows;
  } else {
    const float first = rows.front();
    const float last = rows.back();
    for (size_t index = 0; index < sorted.size(); ++index) {
      const float t = sorted.size() == 1 ? 0.0f : static_cast<float>(index) / static_cast<float>(sorted.size() - 1);
      expectedRows.push_back(first + (last - first) * t);
    }
  }

  const float detectedSpan = sorted.back().y - sorted.front().y;
  const float templateSpan = expectedRows.back() - expectedRows.front();
  if (detectedSpan <= 1.0f || templateSpan <= 1.0f) {
    return 0.0f;
  }

  const float scale = detectedSpan / templateSpan;
  const float offset = sorted.front().y - scale * expectedRows.front();
  float meanError = 0.0f;
  for (size_t index = 0; index < sorted.size(); ++index) {
    const float expectedY = offset + scale * expectedRows[index];
    meanError += std::abs(sorted[index].y - expectedY);
  }
  meanError /= static_cast<float>(sorted.size());
  return clamp01(1.0f - meanError / std::max(2.0f, expectedRadius * 1.8f));
}

std::vector<std::vector<DotCandidate>> clusterByX(std::vector<DotCandidate> dots, float expectedRadius, int imageWidth) {
  std::sort(dots.begin(), dots.end(), [](const auto& lhs, const auto& rhs) {
    return lhs.x < rhs.x;
  });

  std::vector<std::vector<DotCandidate>> clusters;
  if (dots.empty()) {
    return clusters;
  }

  const float splitGap = std::max(expectedRadius * 4.0f, static_cast<float>(imageWidth) * 0.018f);
  clusters.emplace_back();
  clusters.back().push_back(dots.front());
  for (size_t index = 1; index < dots.size(); ++index) {
    if (dots[index].x - dots[index - 1].x > splitGap) {
      clusters.emplace_back();
    }
    clusters.back().push_back(dots[index]);
  }
  return clusters;
}

struct RailCluster {
  RailModel rail;
  float centerX = 0.0f;
  float score = 0.0f;
};

std::vector<DotCandidate> fallbackBandDots(const std::vector<DotCandidate>& candidates, int imageWidth, bool left) {
  std::vector<DotCandidate> dots;
  const float edge = static_cast<float>(imageWidth) * (left ? 0.12f : 0.88f);
  for (const auto& candidate : candidates) {
    if ((left && candidate.x < edge) || (!left && candidate.x > edge)) {
      dots.push_back(candidate);
    }
  }
  return dots;
}

cv::Mat localDarkMask(const cv::Mat& gray, float expectedRadius) {
  cv::Mat blurred;
  cv::GaussianBlur(gray, blurred, cv::Size(3, 3), 0.0);

  int backgroundKernelSize = std::max(15, static_cast<int>(std::ceil(expectedRadius * 5.0f)));
  if (backgroundKernelSize % 2 == 0) {
    backgroundKernelSize += 1;
  }
  cv::Mat localDarkContrast;
  localDarkContrast = ellipticalBlackHat(blurred, backgroundKernelSize);

  cv::Mat mask;
  const double localOtsu = cv::threshold(localDarkContrast, mask, 0, 255, cv::THRESH_BINARY | cv::THRESH_OTSU);
  if (localOtsu < 8.0) {
    cv::threshold(localDarkContrast, mask, 8.0, 255, cv::THRESH_BINARY);
  }
  cv::morphologyEx(mask, mask, cv::MORPH_OPEN, cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(3, 3)));
  return mask;
}

cv::Mat railCandidateMask(const cv::Mat& gray, float expectedRadius, bool sideBandsOnly) {
  if (!sideBandsOnly) {
    return localDarkMask(gray, expectedRadius);
  }

  // Registration dots are printed along the two outside rails. Processing two
  // generous 28% bands removes almost half of the expensive large-kernel
  // morphology while retaining heavily skewed/cropped sheets. The caller falls
  // back to the original full-page pass whenever these bands are inconclusive.
  const int bandWidth = std::min(
    gray.cols,
    std::max(
      static_cast<int>(std::ceil(static_cast<double>(gray.cols) * 0.28)),
      static_cast<int>(std::ceil(expectedRadius * 12.0f))
    )
  );
  if (bandWidth * 2 >= gray.cols) {
    return localDarkMask(gray, expectedRadius);
  }

  cv::Mat mask(gray.size(), CV_8UC1, cv::Scalar(0));
  const cv::Rect leftRect(0, 0, bandWidth, gray.rows);
  const cv::Rect rightRect(gray.cols - bandWidth, 0, bandWidth, gray.rows);
  localDarkMask(gray(leftRect), expectedRadius).copyTo(mask(leftRect));
  localDarkMask(gray(rightRect), expectedRadius).copyTo(mask(rightRect));
  return mask;
}

} // namespace

RailDetection SideDotRailDetector::detect(const cv::Mat& gray, const OmrTemplate& tpl, int minDotsPerSide) const {
  RailDetection fast = detectWithScope(gray, tpl, minDotsPerSide, true);
  const bool fastResultIsReliable =
    fast.left.found &&
    fast.right.found &&
    static_cast<int>(fast.left.dots.size()) >= minDotsPerSide &&
    static_cast<int>(fast.right.dots.size()) >= minDotsPerSide &&
    fast.railConfidence >= 0.58f &&
    fast.rowMatchScore >= 0.52f;
  if (fastResultIsReliable) {
    fast.railDetectionMode += "_side_bands";
    return fast;
  }
  return detectWithScope(gray, tpl, minDotsPerSide, false);
}

RailDetection SideDotRailDetector::detectSideBands(
  const cv::Mat& gray,
  const OmrTemplate& tpl,
  int minDotsPerSide
) const {
  return detectWithScope(gray, tpl, minDotsPerSide, true);
}

RailDetection SideDotRailDetector::detectWithScope(
  const cv::Mat& gray,
  const OmrTemplate& tpl,
  int minDotsPerSide,
  bool sideBandsOnly
) const {
  RailDetection result;
  if (gray.empty()) {
    return result;
  }
  result.expectedDotsPerSide = tpl.registration.expectedDotsPerSide;
  const float expectedRadius = std::max(2.5f, tpl.registration.dotRadius * static_cast<float>(gray.cols) / tpl.width);

  // A page-wide Otsu threshold turns broad shadows into foreground and can
  // connect timing dots to the shaded paper. Black-hat contrast estimates the
  // paper locally with a scale comfortably larger than a timing dot, preserving
  // the dots under smooth illumination gradients without flattening them.
  cv::Mat mask = railCandidateMask(gray, expectedRadius, sideBandsOnly);

  std::vector<std::vector<cv::Point>> contours;
  cv::findContours(mask, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);

  std::vector<DotCandidate> leftCandidates;
  std::vector<DotCandidate> rightCandidates;
  std::vector<DotCandidate> allCandidates;
  allCandidates.reserve(contours.size());
  result.rejectedCandidates.reserve(contours.size());
  const float minArea = static_cast<float>(CV_PI) * expectedRadius * expectedRadius * 0.18f;
  const float maxArea = static_cast<float>(CV_PI) * expectedRadius * expectedRadius * 8.0f;

  for (const auto& contour : contours) {
    const double areaDouble = cv::contourArea(contour);
    const cv::Rect bounds = cv::boundingRect(contour);
    const float centerX = static_cast<float>(bounds.x) + static_cast<float>(bounds.width) * 0.5f;
    const float centerY = static_cast<float>(bounds.y) + static_cast<float>(bounds.height) * 0.5f;
    if (areaDouble < minArea || areaDouble > maxArea) {
      result.rejectedCandidates.push_back({centerX, centerY, "area"});
      continue;
    }

    const float aspect = static_cast<float>(bounds.width) / static_cast<float>(std::max(1, bounds.height));
    if (aspect < 0.65f || aspect > 1.45f) {
      result.rejectedCandidates.push_back({centerX, centerY, "aspect"});
      continue;
    }

    const double perimeter = cv::arcLength(contour, true);
    const float circ = circularity(static_cast<float>(areaDouble), static_cast<float>(perimeter));
    if (circ < 0.55f) {
      result.rejectedCandidates.push_back({centerX, centerY, "circularity"});
      continue;
    }

    const auto m = cv::moments(contour);
    if (std::abs(m.m00) < 1e-5) {
      continue;
    }

    const float darkness = contourDarkness(gray, contour, bounds);
    if (darkness < 0.45f) {
      result.rejectedCandidates.push_back({centerX, centerY, "darkness"});
      continue;
    }

    DotCandidate dot;
    dot.x = static_cast<float>(m.m10 / m.m00);
    dot.y = static_cast<float>(m.m01 / m.m00);
    dot.area = static_cast<float>(areaDouble);
    dot.radius = std::sqrt(dot.area / static_cast<float>(CV_PI));
    dot.circularity = circ;
    dot.darkness = darkness;
    dot.score = clamp01((circ - 0.55f) / 0.45f) * 0.45f + clamp01(darkness) * 0.35f +
                clamp01(1.0f - std::abs(dot.radius - expectedRadius) / std::max(1.0f, expectedRadius * 2.0f)) * 0.20f;

    ++result.totalCandidates;
    allCandidates.push_back(dot);
  }

  std::vector<RailCluster> clusters;
  for (const auto& cluster : clusterByX(allCandidates, expectedRadius, gray.cols)) {
    if (static_cast<int>(cluster.size()) < minDotsPerSide) {
      for (const auto& dot : cluster) {
        result.rejectedCandidates.push_back({dot.x, dot.y, "x_cluster"});
      }
      continue;
    }

    RailModel rail = fitRail(cluster, tpl.registration.expectedDotsPerSide, minDotsPerSide, expectedRadius);
    if (!rail.found) {
      for (const auto& dot : cluster) {
        result.rejectedCandidates.push_back({dot.x, dot.y, "row_mismatch"});
      }
      continue;
    }

    const auto& expectedRows = tpl.registration.rowsForSide(centerX(rail.dots) < gray.cols * .5f);
    const float rowScore = rowMatchScoreForDots(rail.dots, expectedRows, expectedRadius);
    const float countScore = clamp01(1.0f - std::abs(static_cast<float>(rail.dots.size() - tpl.registration.expectedDotsPerSide)) /
                                             static_cast<float>(std::max(1, tpl.registration.expectedDotsPerSide)));
    const float expectedSpan = expectedRows.empty()
      ? static_cast<float>(gray.rows) * 0.80f
      : (expectedRows.back() - expectedRows.front()) * static_cast<float>(gray.rows) / tpl.height;
    const float span = rail.bottomY - rail.topY;
    const float coverageScore = clamp01(1.0f - std::abs(span - expectedSpan) / std::max(1.0f, expectedSpan));
    const float xScore = clamp01(
      1.0f - lineResidualRms(rail.dots, rail) / std::max(1.0f, expectedRadius * 1.5f)
    );
    const float regularityScore = clamp01(1.0f - regularityError(rail.dots));
    rail.rowMatchScore = rowScore;

    RailCluster candidate;
    candidate.rail = std::move(rail);
    candidate.centerX = centerX(candidate.rail.dots);
    candidate.score = countScore * 0.28f + coverageScore * 0.20f + xScore * 0.17f +
                      regularityScore * 0.12f + rowScore * 0.23f;
    candidate.rail.score = clamp01(candidate.rail.score * 0.45f + candidate.score * 0.55f);
    clusters.push_back(std::move(candidate));
  }

  const auto chooseRail = [&](bool left) -> RailModel {
    std::vector<const RailCluster*> side;
    for (const auto& cluster : clusters) {
      if ((left && cluster.centerX < static_cast<float>(gray.cols) * 0.5f) ||
          (!left && cluster.centerX >= static_cast<float>(gray.cols) * 0.5f)) {
        side.push_back(&cluster);
      }
    }
    if (side.empty()) {
      return {};
    }

    float bestScore = 0.0f;
    for (const RailCluster* cluster : side) {
      bestScore = std::max(bestScore, cluster->score);
    }
    const float minimumScore = bestScore * 0.85f;
    const RailCluster* chosen = nullptr;
    for (const RailCluster* cluster : side) {
      if (cluster->score < minimumScore) {
        continue;
      }
      if (chosen == nullptr ||
          (left && cluster->centerX < chosen->centerX) ||
          (!left && cluster->centerX > chosen->centerX)) {
        chosen = cluster;
      }
    }
    return chosen == nullptr ? RailModel{} : chosen->rail;
  };

  result.left = chooseRail(true);
  result.right = chooseRail(false);
  if (result.left.found && result.right.found) {
    result.railDetectionMode = "adaptive_clusters";
  } else {
    leftCandidates = fallbackBandDots(allCandidates, gray.cols, true);
    rightCandidates = fallbackBandDots(allCandidates, gray.cols, false);
    result.left = fitRail(leftCandidates, tpl.registration.expectedDotsPerSide, minDotsPerSide, expectedRadius);
    result.right = fitRail(rightCandidates, tpl.registration.expectedDotsPerSide, minDotsPerSide, expectedRadius);
    result.left.rowMatchScore = rowMatchScoreForDots(result.left.dots, tpl.registration.rowY, expectedRadius);
    result.right.rowMatchScore = rowMatchScoreForDots(result.right.dots, tpl.registration.rowsForSide(false), expectedRadius);
    result.railDetectionMode = "side_band_fallback";
  }

  if (result.left.found && result.right.found) {
    result.railScore = clamp01((result.left.score + result.right.score) * 0.5f);
    result.rowMatchScore = clamp01((result.left.rowMatchScore + result.right.rowMatchScore) * 0.5f);
    result.railConfidence = clamp01(result.railScore * 0.65f + result.rowMatchScore * 0.35f);
  } else {
    result.railScore = 0.0f;
    result.railConfidence = 0.0f;
  }
  result.rejectedCandidateCount = static_cast<int>(result.rejectedCandidates.size());
  return result;
}

RailModel SideDotRailDetector::fitRail(
  const std::vector<DotCandidate>& candidates,
  int expectedDots,
  int minDots,
  float expectedRadius
) const {
  RailModel model;
  if (static_cast<int>(candidates.size()) < minDots) {
    model.dots = candidates;
    return model;
  }

  std::vector<cv::Point2f> points;
  points.reserve(candidates.size());
  for (const auto& candidate : candidates) {
    points.emplace_back(candidate.x, candidate.y);
  }

  cv::Vec4f line;
  cv::fitLine(points, line, cv::DIST_HUBER, 0.0, 0.01, 0.01);
  const float vx = line[0];
  const float vy = line[1];
  const float x0 = line[2];
  const float y0 = line[3];
  float a = vy;
  float b = -vx;
  float c = vx * y0 - vy * x0;

  std::vector<DotCandidate> inliers;
  const float tolerance = std::max(4.0f, expectedRadius * 1.25f);
  for (const auto& candidate : candidates) {
    if (distanceToLine(a, b, c, candidate) <= tolerance) {
      inliers.push_back(candidate);
    }
  }

  if (static_cast<int>(inliers.size()) < minDots) {
    model.dots = inliers;
    return model;
  }

  points.clear();
  for (const auto& candidate : inliers) {
    points.emplace_back(candidate.x, candidate.y);
  }
  cv::fitLine(points, line, cv::DIST_HUBER, 0.0, 0.01, 0.01);
  a = line[1];
  b = -line[0];
  c = line[0] * line[3] - line[1] * line[2];

  std::sort(inliers.begin(), inliers.end(), [](const DotCandidate& lhs, const DotCandidate& rhs) {
    return lhs.y < rhs.y;
  });

  float averageScore = 0.0f;
  for (const auto& dot : inliers) {
    averageScore += dot.score;
  }
  averageScore /= static_cast<float>(inliers.size());

  model.found = true;
  model.dots = std::move(inliers);
  model.lineA = a;
  model.lineB = b;
  model.lineC = c;
  model.topY = model.dots.front().y;
  model.bottomY = model.dots.back().y;
  const float countScore = clamp01(static_cast<float>(model.dots.size()) / static_cast<float>(std::max(1, expectedDots)));
  const float minScore = clamp01(static_cast<float>(model.dots.size()) / static_cast<float>(std::max(1, minDots)));
  model.score = clamp01(averageScore * 0.55f + countScore * 0.30f + minScore * 0.15f);
  return model;
}

} // namespace omr
