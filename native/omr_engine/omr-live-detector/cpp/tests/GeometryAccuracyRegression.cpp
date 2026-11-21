#include <algorithm>
#include <array>
#include <cmath>
#include <iostream>
#include <set>
#include <string>
#include <vector>

#include <opencv2/imgproc.hpp>

#include "geometry/SheetQuadEstimator.hpp"
#include "omr/Geometry.hpp"
#include "omr/OmrTemplate.hpp"
#include "rails/SideDotRailDetector.hpp"

namespace {

bool require(bool condition, const std::string& message) {
  if (!condition) {
    std::cerr << "FAIL: " << message << '\n';
  }
  return condition;
}

omr::OmrTemplate geometryTemplate() {
  omr::OmrTemplate tpl;
  tpl.width = 600.0f;
  tpl.height = 900.0f;
  tpl.registration.leftRailX = 25.0f;
  tpl.registration.rightRailX = 575.0f;
  tpl.registration.dotRadius = 6.0f;
  tpl.registration.expectedDotsPerSide = 47;
  tpl.registration.minDotsPerSideForNormalize = 28;

  const std::vector<float> referenceRows = {
    100.8f, 139.7f, 178.7f, 217.5f, 256.5f, 295.3f, 334.3f,
    487.0f, 525.9f, 564.8f, 603.7f, 642.6f, 681.5f, 720.3f, 759.3f, 798.1f, 839.8f,
    937.0f, 978.7f, 1020.3f, 1062.0f, 1103.7f, 1145.3f, 1187.0f, 1228.7f, 1270.3f,
    1312.0f, 1353.7f, 1395.3f, 1437.0f, 1478.7f, 1520.3f, 1562.0f, 1603.7f, 1645.3f,
    1687.0f, 1728.7f, 1770.3f, 1812.0f, 1853.7f, 1895.3f, 1937.0f, 1978.7f, 2020.3f,
    2062.0f, 2103.7f, 2145.3f,
  };
  for (const float y : referenceRows) {
    tpl.registration.rowY.push_back(y * tpl.height / 2339.0f);
  }
  return tpl;
}

cv::Mat syntheticCameraFrame(
  const omr::OmrTemplate& tpl,
  const std::set<int>& missingLeft,
  const std::set<int>& missingRight,
  const std::array<cv::Point2f, 4>& destination,
  cv::Size frameSize
) {
  cv::Mat canonical(
    static_cast<int>(tpl.height),
    static_cast<int>(tpl.width),
    CV_8UC1,
    cv::Scalar(248)
  );
  for (size_t index = 0; index < tpl.registration.rowY.size(); ++index) {
    const int y = cvRound(tpl.registration.rowY[index]);
    if (missingLeft.count(static_cast<int>(index)) == 0) {
      cv::circle(canonical, cv::Point(cvRound(tpl.registration.leftRailX), y), 6, cv::Scalar(8), -1, cv::LINE_AA);
    }
    if (missingRight.count(static_cast<int>(index)) == 0) {
      cv::circle(canonical, cv::Point(cvRound(tpl.registration.rightRailX), y), 6, cv::Scalar(8), -1, cv::LINE_AA);
    }
  }

  // Non-rail print and isolated circular distractors exercise cluster rejection.
  cv::rectangle(canonical, cv::Rect(170, 95, 260, 85), cv::Scalar(105), 2, cv::LINE_AA);
  for (int index = 0; index < 10; ++index) {
    cv::circle(
      canonical,
      cv::Point(150 + (index % 2) * 300, 245 + index * 47),
      6,
      cv::Scalar(25),
      -1,
      cv::LINE_AA
    );
  }

  const std::array<cv::Point2f, 4> source = {
    cv::Point2f(0.0f, 0.0f),
    cv::Point2f(tpl.width, 0.0f),
    cv::Point2f(tpl.width, tpl.height),
    cv::Point2f(0.0f, tpl.height),
  };
  const cv::Mat transform = cv::getPerspectiveTransform(source.data(), destination.data());
  cv::Mat frame(frameSize, CV_8UC1, cv::Scalar(232));
  cv::warpPerspective(
    canonical,
    frame,
    transform,
    frameSize,
    cv::INTER_LINEAR,
    cv::BORDER_TRANSPARENT
  );

  for (int y = 0; y < frame.rows; ++y) {
    uint8_t* row = frame.ptr<uint8_t>(y);
    for (int x = 0; x < frame.cols; ++x) {
      const double gradient = 0.72 + 0.28 * static_cast<double>(x) / std::max(1, frame.cols - 1);
      const double shadow = 1.0 - 0.16 * std::exp(
        -0.5 * std::pow((static_cast<double>(y) - frame.rows * 0.48) / (frame.rows * 0.16), 2.0)
      );
      row[x] = static_cast<uint8_t>(std::clamp(cvRound(row[x] * gradient * shadow), 0, 255));
    }
  }
  cv::GaussianBlur(frame, frame, cv::Size(3, 3), 0.65);
  return frame;
}

double meanCornerError(const omr::Quad& detected, const std::array<cv::Point2f, 4>& expected) {
  const std::array<omr::Point, 4> actual = {
    detected.topLeft,
    detected.topRight,
    detected.bottomRight,
    detected.bottomLeft,
  };
  double sum = 0.0;
  for (size_t index = 0; index < actual.size(); ++index) {
    const double dx = actual[index].x - expected[index].x;
    const double dy = actual[index].y - expected[index].y;
    sum += std::sqrt(dx * dx + dy * dy);
  }
  return sum / static_cast<double>(actual.size());
}

omr::RailModel profileRail(
  const omr::OmrTemplate& tpl,
  bool left,
  const std::vector<float>& observedRows,
  float upperRunsInwardShift
) {
  omr::RailModel rail;
  rail.found = true;
  rail.score = 1.0f;
  rail.rowMatchScore = 1.0f;
  rail.lineA = 1.0f;
  rail.lineB = 0.0f;

  const float canonicalX = left ? tpl.registration.leftRailX : tpl.registration.rightRailX;
  const float baseX = 150.0f + canonicalX * (700.0f / tpl.width);
  rail.lineC = -baseX;
  for (size_t index = 0; index < observedRows.size(); ++index) {
    const float rowY = observedRows[index];
    const bool inUpperRuns = index < 17;
    omr::DotCandidate dot;
    dot.x = baseX + (inUpperRuns ? (left ? upperRunsInwardShift : -upperRunsInwardShift) : 0.0f);
    dot.y = 100.0f + rowY * (1050.0f / tpl.height);
    dot.radius = 7.0f;
    dot.area = 154.0f;
    dot.circularity = 1.0f;
    dot.darkness = 1.0f;
    dot.score = 1.0f;
    rail.dots.push_back(dot);
  }
  rail.topY = rail.dots.front().y;
  rail.bottomY = rail.dots.back().y;
  return rail;
}

bool runInconsistentUpperProfileFallbackCase() {
  const omr::OmrTemplate tpl = geometryTemplate();
  std::vector<float> legacyRows = tpl.registration.rowY;
  const std::vector<float> legacyStudentRows = {
    459.3f, 498.2f, 537.0f, 575.8f, 614.7f,
    653.7f, 692.5f, 731.5f, 770.3f, 812.0f,
  };
  for (size_t index = 0; index < legacyStudentRows.size(); ++index) {
    legacyRows[index + 7] = legacyStudentRows[index] * tpl.height / 2339.0f;
  }

  // Reproduce the safety-critical shape seen when an older 10-column sheet is
  // processed with the new five-column template and the upper page has local
  // camera/scan distortion: the 30 answer rows remain mutually consistent,
  // while a high-count all-row fit is geometrically contradictory.
  omr::RailDetection rails;
  rails.left = profileRail(tpl, true, legacyRows, 24.0f);
  rails.right = profileRail(tpl, false, legacyRows, 24.0f);
  rails.expectedDotsPerSide = 47;
  rails.railScore = 1.0f;
  rails.railConfidence = 1.0f;
  rails.rowMatchScore = 1.0f;

  const omr::SheetDetection sheet = omr::SheetQuadEstimator{}.estimate(
    rails,
    tpl,
    1000,
    1250
  );
  const std::array<cv::Point2f, 4> expected = {
    cv::Point2f(150.0f, 100.0f),
    cv::Point2f(850.0f, 100.0f),
    cv::Point2f(850.0f, 1150.0f),
    cv::Point2f(150.0f, 1150.0f),
  };

  bool ok = true;
  if (sheet.homographyStrategyUsed != "answer_30_registration_rows") {
    std::cerr << "INFO: inconsistent-upper-profile strategy=" << sheet.homographyStrategyUsed
              << " matches=" << sheet.leftRowMatches << "/" << sheet.rightRowMatches
              << " used=" << sheet.matchedRowsUsed
              << " reprojection=" << sheet.reprojectionError
              << " cornerError=" << meanCornerError(sheet.quad, expected) << '\n';
  }
  ok &= require(sheet.found, "inconsistent upper timing profile must still produce an answer-safe quad");
  ok &= require(
    sheet.homographyStrategyUsed == "answer_30_registration_rows",
    "a high-residual upper profile must not be forced onto the new five-column row identities"
  );
  ok &= require(sheet.fallbackUsed, "inconsistent upper profile must be reported as an answer-run fallback");
  ok &= require(sheet.matchedRowsUsed == 30, "upper-profile fallback must use all 30 answer rows");
  ok &= require(sheet.reprojectionError <= 1.0, "fallback answer rows must fit precisely");
  ok &= require(
    meanCornerError(sheet.quad, expected) <= 1.0,
    "answer-run fallback must preserve the exact sheet transform when its 30 rows agree"
  );

  omr::RailDetection currentRails = rails;
  currentRails.left = profileRail(tpl, true, tpl.registration.rowY, 0.0f);
  currentRails.right = profileRail(tpl, false, tpl.registration.rowY, 0.0f);
  const omr::SheetDetection currentSheet = omr::SheetQuadEstimator{}.estimate(
    currentRails,
    tpl,
    1000,
    1250
  );
  ok &= require(
    currentSheet.homographyStrategyUsed == "all_47_registration_rows" && !currentSheet.fallbackUsed,
    "the matching five-column timing profile must continue to use all 47 rows"
  );
  return ok;
}

bool runGeometryCase(
  const std::string& name,
  const std::set<int>& missingLeft,
  const std::set<int>& missingRight,
  const std::array<cv::Point2f, 4>& destination,
  double maximumMeanCornerError
) {
  const omr::OmrTemplate tpl = geometryTemplate();
  const cv::Mat frame = syntheticCameraFrame(
    tpl,
    missingLeft,
    missingRight,
    destination,
    cv::Size(1000, 1250)
  );
  const omr::RailDetection rails = omr::SideDotRailDetector{}.detect(
    frame,
    tpl,
    tpl.registration.minDotsPerSideForNormalize
  );
  const omr::SheetDetection sheet = omr::SheetQuadEstimator{}.estimate(
    rails,
    tpl,
    frame.cols,
    frame.rows
  );

  bool ok = true;
  const double cornerError = sheet.found ? meanCornerError(sheet.quad, destination) : 1e9;
  if (
    !rails.left.found || !rails.right.found || !sheet.found ||
    sheet.matchedRowsUsed < 28 || cornerError > maximumMeanCornerError
  ) {
    std::cerr << "INFO: " << name
              << " left=" << rails.left.dots.size()
              << " right=" << rails.right.dots.size()
              << " strategy=" << sheet.homographyStrategyUsed
              << " rowMatches=" << sheet.leftRowMatches << "/" << sheet.rightRowMatches
              << " matched=" << sheet.matchedRowsUsed
              << " reprojection=" << sheet.reprojectionError
              << " cornerError=" << cornerError << '\n';
  }
  ok &= require(rails.left.found, name + ": left timing rail must be found");
  ok &= require(rails.right.found, name + ": right timing rail must be found");
  ok &= require(sheet.found, name + ": sheet quad must be estimated");
  ok &= require(sheet.matchedRowsUsed >= 28, name + ": enough real row correspondences must support normalization");
  if (sheet.found) {
    ok &= require(
      cornerError <= maximumMeanCornerError,
      name + ": mean corner error must stay within the camera-normalization envelope"
    );
  }
  return ok;
}

} // namespace

int main() {
  bool ok = true;
  ok &= runGeometryCase(
    "tilted perspective with all timing dots",
    {},
    {},
    {cv::Point2f(165, 75), cv::Point2f(805, 158), cv::Point2f(750, 1162), cv::Point2f(92, 1068)},
    9.0
  );
  ok &= runGeometryCase(
    "independent timing-dot dropout",
    {1, 5, 9, 14, 18, 23, 29, 35, 41},
    {2, 6, 11, 15, 20, 26, 31, 38, 44},
    {cv::Point2f(132, 116), cv::Point2f(824, 78), cv::Point2f(778, 1135), cv::Point2f(102, 1180)},
    12.0
  );
  ok &= runInconsistentUpperProfileFallbackCase();
  if (ok) {
    std::cout << "Geometry accuracy regression tests passed\n";
    return 0;
  }
  return 1;
}
