#pragma once

namespace omr {

struct OmrLiveOptions {
  float minBrightness = 0.28f;
  float maxBrightness = 0.92f;
  float minSharpness = 0.42f;
  float maxGlare = 0.08f;
  float minContrast = 0.18f;
  float minCoverage = 0.58f;
  float maxCoverage = 0.94f;
  float minRailScore = 0.65f;
  float minAngleScore = 0.72f;
  int minDotsPerSideForLive = 16;
  int minDotsPerSideForNormalize = 28;
  int stableFrameCount = 6;
  int uiUpdateEveryMs = 120;
  bool debug = false;
};

} // namespace omr
