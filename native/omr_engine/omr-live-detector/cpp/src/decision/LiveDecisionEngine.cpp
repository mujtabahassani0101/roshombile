#include "decision/LiveDecisionEngine.hpp"

namespace omr {

namespace {

OmrLiveResult status(
  const char* statusValue,
  const char* action,
  const char* messageKey,
  const OmrLiveMetrics& metrics,
  const SheetDetection& sheet,
  int frameWidth,
  int frameHeight,
  int64_t timestampMs,
  const char* reason,
  bool canCapture = false
) {
  OmrLiveResult result;
  result.status = statusValue;
  result.action = action;
  result.messageKey = messageKey;
  result.canCapture = canCapture;
  result.confidence = canCapture ? 1.0f : clamp01((metrics.railScore + metrics.angleScore + metrics.coverage) / 3.0f);
  result.metrics = metrics;
  result.frameWidth = frameWidth;
  result.frameHeight = frameHeight;
  result.timestampMs = timestampMs;
  result.reason = reason;
  if (sheet.found) {
    result.hasQuad = true;
    result.quad = sheet.quad;
  }
  return result;
}

} // namespace

OmrLiveResult LiveDecisionEngine::decide(
  const OmrLiveMetrics& metrics,
  const SheetDetection& sheet,
  const OmrLiveOptions& options,
  int stableFrames,
  int frameWidth,
  int frameHeight,
  int64_t timestampMs
) {
  if (!sheet.found) {
    return status("NO_SHEET", "SEARCH_SHEET", "search_sheet", metrics, sheet, frameWidth, frameHeight, timestampMs, sheet.reason.c_str());
  }
  if (!sheet.allCornersInside) {
    return status("PARTIAL_SHEET", "SEARCH_SHEET", "partial_sheet", metrics, sheet, frameWidth, frameHeight, timestampMs, "partial_sheet");
  }
  if (metrics.coverage < options.minCoverage) {
    return status("TOO_FAR", "MOVE_CLOSER", "move_closer", metrics, sheet, frameWidth, frameHeight, timestampMs, "coverage_low");
  }
  if (metrics.coverage > options.maxCoverage) {
    return status("TOO_CLOSE", "MOVE_AWAY", "move_away", metrics, sheet, frameWidth, frameHeight, timestampMs, "coverage_high");
  }
  if (metrics.brightness < options.minBrightness) {
    return status("LOW_LIGHT", "INCREASE_LIGHT", "low_light", metrics, sheet, frameWidth, frameHeight, timestampMs, "brightness_low");
  }
  if (metrics.brightness > options.maxBrightness) {
    return status("TOO_BRIGHT", "REDUCE_GLARE", "too_bright", metrics, sheet, frameWidth, frameHeight, timestampMs, "brightness_high");
  }
  if (metrics.glare > options.maxGlare) {
    return status("GLARE", "REDUCE_GLARE", "glare", metrics, sheet, frameWidth, frameHeight, timestampMs, "glare_high");
  }
  if (metrics.contrast < options.minContrast) {
    return status("LOW_LIGHT", "INCREASE_LIGHT", "low_light", metrics, sheet, frameWidth, frameHeight, timestampMs, "contrast_low");
  }
  if (metrics.sharpness < options.minSharpness) {
    return status("BLURRY", "HOLD_STEADY", "blurry", metrics, sheet, frameWidth, frameHeight, timestampMs, "sharpness_low");
  }
  if (metrics.angleScore < options.minAngleScore) {
    return status("BAD_ANGLE", "ALIGN_CAMERA", "bad_angle", metrics, sheet, frameWidth, frameHeight, timestampMs, "angle_low");
  }
  if (metrics.railScore < options.minRailScore) {
    return status("NO_SHEET", "SEARCH_SHEET", "weak_rails", metrics, sheet, frameWidth, frameHeight, timestampMs, "weak_rails");
  }
  if (stableFrames < options.stableFrameCount) {
    return status("UNSTABLE", "HOLD_STEADY", "unstable", metrics, sheet, frameWidth, frameHeight, timestampMs, "unstable");
  }
  return status("READY", "CAPTURE_NOW", "ready", metrics, sheet, frameWidth, frameHeight, timestampMs, "ready", true);
}

} // namespace omr
