#include "omr/OmrLiveEngine.hpp"

#include <chrono>
#include <opencv2/imgproc.hpp>
#include "decision/LiveDecisionEngine.hpp"
#include "geometry/SheetQuadEstimator.hpp"
#include "quality/QualityAnalyzer.hpp"
#include "rails/SideDotRailDetector.hpp"
#include "utils/Timer.hpp"

namespace omr {

namespace {

constexpr int kMaximumAnalysisWidth = 720;

bool isValidOrientation(int orientation) {
  return orientation == 0 || orientation == 90 || orientation == 180 || orientation == 270;
}

cv::Mat makeUprightGray(
  const uint8_t* yData,
  int width,
  int height,
  int rowStride,
  int orientation
) {
  cv::Mat raw(height, width, CV_8UC1, const_cast<uint8_t*>(yData), static_cast<size_t>(rowStride));
  cv::Mat upright = raw.clone();
  if (orientation == 90) {
    cv::rotate(upright, upright, cv::ROTATE_90_CLOCKWISE);
  } else if (orientation == 180) {
    cv::rotate(upright, upright, cv::ROTATE_180);
  } else if (orientation == 270) {
    cv::rotate(upright, upright, cv::ROTATE_90_COUNTERCLOCKWISE);
  }

  if (upright.cols <= kMaximumAnalysisWidth) {
    return upright;
  }

  const double scale = static_cast<double>(kMaximumAnalysisWidth) /
    static_cast<double>(upright.cols);
  cv::Mat resized;
  cv::resize(upright, resized, cv::Size(), scale, scale, cv::INTER_AREA);
  return resized;
}

OmrLiveResult invalidInputResult(
  const char* reason,
  int frameWidth,
  int frameHeight,
  int64_t timestampMs,
  double processingTimeMs
) {
  OmrLiveResult error;
  error.status = "NO_SHEET";
  error.action = "SEARCH_SHEET";
  error.messageKey = "search_sheet";
  error.reason = reason;
  error.frameWidth = frameWidth;
  error.frameHeight = frameHeight;
  error.timestampMs = timestampMs;
  error.processingTimeMs = processingTimeMs;
  return error;
}

} // namespace

OmrLiveEngine::OmrLiveEngine() = default;

OmrLiveEngine::~OmrLiveEngine() = default;

void OmrLiveEngine::configure(const std::string& templateJson, const std::string& optionsJson) {
  const OmrTemplate parsedTemplate = OmrTemplate::fromJson(templateJson);
  const OmrLiveOptions parsedOptions = OmrTemplate::optionsFromJson(optionsJson, parsedTemplate);
  std::lock_guard<std::mutex> lock(mutex_);
  template_ = parsedTemplate;
  options_ = parsedOptions;
  configured_ = true;
  ++stateEpoch_;
  stability_.reset();
  hasAnalysisGeometry_ = false;
}

OmrLiveResult OmrLiveEngine::detectYPlane(
  const uint8_t* yData,
  int width,
  int height,
  int rowStride,
  int orientation,
  int64_t timestampMs
) {
  Timer timer;

  OmrTemplate tpl;
  OmrLiveOptions options;
  bool configured = false;
  std::uint64_t stateEpoch = 0;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    tpl = template_;
    options = options_;
    configured = configured_;
    stateEpoch = stateEpoch_;
  }

  if (timestampMs <= 0) {
    const auto now = std::chrono::system_clock::now().time_since_epoch();
    timestampMs = std::chrono::duration_cast<std::chrono::milliseconds>(now).count();
  }

  if (!configured) {
    return invalidInputResult("engine_not_configured", 0, 0, timestampMs, timer.elapsedMs());
  }

  if (!isValidOrientation(orientation)) {
    std::lock_guard<std::mutex> lock(mutex_);
    ++stateEpoch_;
    stability_.reset();
    hasAnalysisGeometry_ = false;
    return invalidInputResult("invalid_orientation", 0, 0, timestampMs, timer.elapsedMs());
  }
  if (yData == nullptr || width <= 0 || height <= 0 || rowStride <= 0) {
    std::lock_guard<std::mutex> lock(mutex_);
    ++stateEpoch_;
    stability_.reset();
    hasAnalysisGeometry_ = false;
    return invalidInputResult("invalid_frame", 0, 0, timestampMs, timer.elapsedMs());
  }
  if (rowStride < width) {
    std::lock_guard<std::mutex> lock(mutex_);
    ++stateEpoch_;
    stability_.reset();
    hasAnalysisGeometry_ = false;
    return invalidInputResult("invalid_row_stride", 0, 0, timestampMs, timer.elapsedMs());
  }

  cv::Mat gray = makeUprightGray(yData, width, height, rowStride, orientation);
  OmrLiveMetrics metrics = QualityAnalyzer::analyzeGray(gray);

  SideDotRailDetector railDetector;
  const RailDetection rails = railDetector.detect(gray, tpl, options.minDotsPerSideForLive);
  SheetQuadEstimator quadEstimator;
  SheetDetection sheet = quadEstimator.estimate(rails, tpl, gray.cols, gray.rows);

  metrics.coverage = sheet.coverage;
  metrics.angleScore = sheet.angleScore;
  metrics.railScore = sheet.railScore;

  const bool qualityOk =
    sheet.found &&
    sheet.allCornersInside &&
    metrics.coverage >= options.minCoverage &&
    metrics.coverage <= options.maxCoverage &&
    metrics.brightness >= options.minBrightness &&
    metrics.brightness <= options.maxBrightness &&
    metrics.contrast >= options.minContrast &&
    metrics.glare <= options.maxGlare &&
    metrics.sharpness >= options.minSharpness &&
    metrics.angleScore >= options.minAngleScore &&
    metrics.railScore >= options.minRailScore;

  int stableFrames = 0;
  OmrLiveResult result;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!configured_ || stateEpoch_ != stateEpoch) {
      return invalidInputResult(
        "engine_state_changed",
        gray.cols,
        gray.rows,
        timestampMs,
        timer.elapsedMs()
      );
    }
    if (!hasAnalysisGeometry_ ||
        lastOrientation_ != orientation ||
        lastAnalysisWidth_ != gray.cols ||
        lastAnalysisHeight_ != gray.rows) {
      stability_.reset();
      hasAnalysisGeometry_ = true;
      lastOrientation_ = orientation;
      lastAnalysisWidth_ = gray.cols;
      lastAnalysisHeight_ = gray.rows;
    }
    stableFrames = stability_.update(sheet.quad, qualityOk, gray.cols, gray.rows, options.stableFrameCount);
    metrics.stability = stability_.score(options.stableFrameCount);
    result = LiveDecisionEngine::decide(
      metrics,
      sheet,
      options,
      stableFrames,
      gray.cols,
      gray.rows,
      timestampMs
    );
  }

  result.detectedLeftDots = static_cast<int>(rails.left.dots.size());
  result.detectedRightDots = static_cast<int>(rails.right.dots.size());
  result.stableFrames = stableFrames;
  result.processingTimeMs = timer.elapsedMs();
  return result;
}

void OmrLiveEngine::reset() {
  std::lock_guard<std::mutex> lock(mutex_);
  ++stateEpoch_;
  stability_.reset();
  hasAnalysisGeometry_ = false;
}

OmrTemplate OmrLiveEngine::currentTemplate() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return template_;
}

OmrLiveOptions OmrLiveEngine::currentOptions() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return options_;
}

} // namespace omr
