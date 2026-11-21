#include <algorithm>
#include <array>
#include <cmath>
#include <fstream>
#include <iostream>
#include <set>
#include <sstream>
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
  std::ifstream input(OMR_TEST_TEMPLATE_PATH);
  std::ostringstream json;
  json << input.rdbuf();
  omr::OmrTemplate tpl = omr::OmrTemplate::fromJson(json.str());
  const float xScale = 600.0f / tpl.width;
  const float yScale = 900.0f / tpl.height;
  tpl.registration.leftRailX *= xScale;
  tpl.registration.rightRailX *= xScale;
  tpl.registration.dotRadius *= (xScale + yScale) * 0.5f;
  for (float& row : tpl.registration.rowY) {
    row *= yScale;
  }
  tpl.width = 600.0f;
  tpl.height = 900.0f;
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
      cv::circle(
        canonical,
        cv::Point(cvRound(tpl.registration.leftRailX), y),
        cvRound(tpl.registration.dotRadius),
        cv::Scalar(8),
        -1,
        cv::LINE_AA
      );
    }
    if (missingRight.count(static_cast<int>(index)) == 0) {
      cv::circle(
        canonical,
        cv::Point(cvRound(tpl.registration.rightRailX), y),
        cvRound(tpl.registration.dotRadius),
        cv::Scalar(8),
        -1,
        cv::LINE_AA
      );
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
    sheet.matchedRowsUsed < tpl.registration.minDotsPerSideForNormalize ||
    cornerError > maximumMeanCornerError
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
  ok &= require(
    sheet.matchedRowsUsed >= tpl.registration.minDotsPerSideForNormalize,
    name + ": enough real T1 row correspondences must support normalization"
  );
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
    {1, 5, 9, 14, 18, 23},
    {2, 6, 11, 15, 20, 26},
    {cv::Point2f(132, 116), cv::Point2f(824, 78), cv::Point2f(778, 1135), cv::Point2f(102, 1180)},
    12.0
  );
  if (ok) {
    std::cout << "Geometry accuracy regression tests passed\n";
    return 0;
  }
  return 1;
}
