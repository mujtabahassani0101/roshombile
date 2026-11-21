#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include <opencv2/imgproc.hpp>

#include "omr/OmrLiveEngine.hpp"

namespace {

bool require(bool condition, const std::string& message) {
  if (!condition) {
    std::cerr << "FAIL: " << message << '\n';
  }
  return condition;
}

std::string templateJson() {
  std::ifstream input(OMR_TEST_TEMPLATE_PATH);
  std::ostringstream contents;
  contents << input.rdbuf();
  return contents.str();
}

std::string optionsJson(int stableFrameCount) {
  return std::string(R"json({
    "minBrightness": 0.0,
    "maxBrightness": 1.0,
    "minSharpness": 0.0,
    "maxGlare": 1.0,
    "minContrast": 0.0,
    "minCoverage": 0.01,
    "maxCoverage": 1.0,
    "minRailScore": 0.0,
    "minAngleScore": 0.0,
    "minDotsPerSideForLive": 10,
    "stableFrameCount": )json") + std::to_string(stableFrameCount) + "}";
}

cv::Mat makeUprightFrame() {
  const omr::OmrTemplate tpl = omr::OmrTemplate::fromJson(templateJson());
  const int canonicalWidth = static_cast<int>(std::round(tpl.width));
  const int canonicalHeight = static_cast<int>(std::round(tpl.height));
  cv::Mat canonical(canonicalHeight, canonicalWidth, CV_8UC1, cv::Scalar(248));
  for (const float y : tpl.registration.rowY) {
    cv::circle(
      canonical,
      cv::Point(cvRound(tpl.registration.leftRailX), cvRound(y)),
      cvRound(tpl.registration.dotRadius),
      cv::Scalar(8),
      -1,
      cv::LINE_AA
    );
    cv::circle(
      canonical,
      cv::Point(cvRound(tpl.registration.rightRailX), cvRound(y)),
      cvRound(tpl.registration.dotRadius),
      cv::Scalar(8),
      -1,
      cv::LINE_AA
    );
  }

  const std::array<cv::Point2f, 4> source = {
    cv::Point2f(0.0f, 0.0f),
    cv::Point2f(static_cast<float>(canonicalWidth), 0.0f),
    cv::Point2f(static_cast<float>(canonicalWidth), static_cast<float>(canonicalHeight)),
    cv::Point2f(0.0f, static_cast<float>(canonicalHeight)),
  };
  const std::array<cv::Point2f, 4> destination = {
    cv::Point2f(130.0f, 100.0f),
    cv::Point2f(830.0f, 112.0f),
    cv::Point2f(846.0f, 1170.0f),
    cv::Point2f(114.0f, 1158.0f),
  };
  const cv::Mat transform = cv::getPerspectiveTransform(source.data(), destination.data());
  cv::Mat frame(1280, 960, CV_8UC1, cv::Scalar(218));
  cv::warpPerspective(canonical, frame, transform, frame.size(), cv::INTER_LINEAR, cv::BORDER_TRANSPARENT);
  cv::GaussianBlur(frame, frame, cv::Size(3, 3), 0.5);
  return frame;
}

cv::Mat rawFrameForOrientation(const cv::Mat& upright, int orientation) {
  cv::Mat raw;
  if (orientation == 0) {
    raw = upright.clone();
  } else if (orientation == 90) {
    cv::rotate(upright, raw, cv::ROTATE_90_COUNTERCLOCKWISE);
  } else if (orientation == 180) {
    cv::rotate(upright, raw, cv::ROTATE_180);
  } else {
    cv::rotate(upright, raw, cv::ROTATE_90_CLOCKWISE);
  }
  return raw;
}

struct PaddedPlane {
  std::vector<uint8_t> bytes;
  int width = 0;
  int height = 0;
  int stride = 0;
};

PaddedPlane paddedPlane(const cv::Mat& gray, int padding) {
  PaddedPlane plane;
  plane.width = gray.cols;
  plane.height = gray.rows;
  plane.stride = gray.cols + padding;
  plane.bytes.assign(static_cast<size_t>(plane.stride) * plane.height, 0);
  for (int y = 0; y < plane.height; ++y) {
    std::memcpy(
      plane.bytes.data() + static_cast<size_t>(y) * plane.stride,
      gray.ptr<uint8_t>(y),
      static_cast<size_t>(plane.width)
    );
  }
  return plane;
}

omr::OmrLiveResult detect(
  omr::OmrLiveEngine& engine,
  const cv::Mat& upright,
  int orientation,
  int64_t timestamp
) {
  const cv::Mat raw = rawFrameForOrientation(upright, orientation);
  const PaddedPlane plane = paddedPlane(raw, 37);
  return engine.detectYPlane(
    plane.bytes.data(),
    plane.width,
    plane.height,
    plane.stride,
    orientation,
    timestamp
  );
}

double quadDifference(const omr::Quad& lhs, const omr::Quad& rhs) {
  const std::array<omr::Point, 4> left = {lhs.topLeft, lhs.topRight, lhs.bottomRight, lhs.bottomLeft};
  const std::array<omr::Point, 4> right = {rhs.topLeft, rhs.topRight, rhs.bottomRight, rhs.bottomLeft};
  double maximum = 0.0;
  for (size_t index = 0; index < left.size(); ++index) {
    const double dx = left[index].x - right[index].x;
    const double dy = left[index].y - right[index].y;
    maximum = std::max(maximum, std::sqrt(dx * dx + dy * dy));
  }
  return maximum;
}

bool runAllOrientationsAndPaddedStride() {
  const cv::Mat upright = makeUprightFrame();
  omr::OmrLiveEngine engine;
  engine.configure(templateJson(), optionsJson(1));

  bool ok = true;
  omr::OmrLiveResult baseline;
  for (const int orientation : {0, 90, 180, 270}) {
    const omr::OmrLiveResult result = detect(engine, upright, orientation, 1000 + orientation);
    ok &= require(result.hasQuad, "orientation " + std::to_string(orientation) + " must detect the sheet");
    ok &= require(result.status == "READY", "orientation " + std::to_string(orientation) + " must be capture-ready");
    ok &= require(result.frameWidth == 720 && result.frameHeight == 960,
      "result dimensions must use the upright resized coordinate space");
    ok &= require(result.detectedLeftDots >= 18 && result.detectedRightDots >= 18,
      "padded Y-plane stride must preserve both timing rails (detected " +
        std::to_string(result.detectedLeftDots) + "/" + std::to_string(result.detectedRightDots) + ")");
    if (orientation == 0) {
      baseline = result;
    } else if (baseline.hasQuad && result.hasQuad) {
      ok &= require(quadDifference(baseline.quad, result.quad) <= 0.05,
        "all orientation values must produce the same upright quad");
    }
  }
  return ok;
}

bool runStabilityResetCases() {
  const cv::Mat upright = makeUprightFrame();
  omr::OmrLiveEngine engine;
  engine.configure(templateJson(), optionsJson(2));

  bool ok = true;
  ok &= require(detect(engine, upright, 0, 2000).stableFrames == 1,
    "first valid frame must start stability at one");
  ok &= require(detect(engine, upright, 0, 2001).stableFrames == 2,
    "same orientation and analysis size must accumulate stability");
  ok &= require(detect(engine, upright, 90, 2002).stableFrames == 1,
    "an orientation change must reset stability");
  ok &= require(detect(engine, upright, 90, 2003).stableFrames == 2,
    "stability must resume after an orientation change");

  engine.configure(templateJson(), optionsJson(2));
  ok &= require(detect(engine, upright, 0, 2100).stableFrames == 1, "configured engine must start at one");
  ok &= require(detect(engine, upright, 0, 2101).stableFrames == 2, "unchanged frame must become stable");
  cv::Mat taller;
  cv::copyMakeBorder(upright, taller, 0, 20, 0, 0, cv::BORDER_CONSTANT, cv::Scalar(218));
  const omr::OmrLiveResult resized = detect(engine, taller, 0, 2102);
  ok &= require(resized.hasQuad && resized.frameWidth == 720 && resized.frameHeight == 975,
    "changed upright aspect ratio must change the analysis dimensions");
  ok &= require(resized.stableFrames == 1, "analysis dimension change must reset stability");
  return ok;
}

bool runInvalidInputCases() {
  const cv::Mat upright = makeUprightFrame();
  const PaddedPlane plane = paddedPlane(upright, 19);
  omr::OmrLiveEngine unconfiguredEngine;
  const omr::OmrLiveResult unconfigured = unconfiguredEngine.detectYPlane(
    plane.bytes.data(), plane.width, plane.height, plane.stride, 0, 2999
  );
  omr::OmrLiveEngine engine;
  engine.configure(templateJson(), optionsJson(2));

  bool ok = true;
  ok &= require(
    unconfigured.status == "NO_SHEET" && unconfigured.reason == "engine_not_configured",
    "live detection must reject calls made before a physical template is configured"
  );
  detect(engine, upright, 0, 3000);
  detect(engine, upright, 0, 3001);
  const omr::OmrLiveResult badOrientation = engine.detectYPlane(
    plane.bytes.data(), plane.width, plane.height, plane.stride, 45, 3002
  );
  ok &= require(badOrientation.status == "NO_SHEET" && badOrientation.reason == "invalid_orientation",
    "unsupported orientation must be rejected explicitly");
  ok &= require(!badOrientation.hasQuad && !badOrientation.canCapture,
    "invalid orientation must never expose a capture candidate");
  ok &= require(detect(engine, upright, 0, 3003).stableFrames == 1,
    "invalid orientation must not leave stale stability");

  const omr::OmrLiveResult badStride = engine.detectYPlane(
    plane.bytes.data(), plane.width, plane.height, plane.width - 1, 0, 3004
  );
  ok &= require(badStride.status == "NO_SHEET" && badStride.reason == "invalid_row_stride",
    "rowStride smaller than width must be rejected before wrapping the Y plane");
  ok &= require(detect(engine, upright, 0, 3005).stableFrames == 1,
    "invalid stride must reset stale stability");
  return ok;
}

} // namespace

int main() {
  bool ok = true;
  ok &= runAllOrientationsAndPaddedStride();
  ok &= runStabilityResetCases();
  ok &= runInvalidInputCases();
  if (!ok) {
    return 1;
  }
  std::cout << "OmrLive orientation regression tests passed\n";
  return 0;
}
