#pragma once

#include <array>
#include <cstdint>
#include <map>
#include <string>
#include <vector>
#include "Geometry.hpp"

namespace omr {

struct OmrLiveMetrics {
  float brightness = 0.0f;
  float sharpness = 0.0f;
  float contrast = 0.0f;
  float glare = 0.0f;
  float coverage = 0.0f;
  float angleScore = 0.0f;
  float railScore = 0.0f;
  float stability = 0.0f;
};

struct OmrBarcodeResult {
  bool found = false;
  bool attempted = false;
  std::string status = "NOT_ATTEMPTED";
  std::string text;
  std::string format;
  float confidence = 0.0f;
  Quad quad;
  std::string error;
  std::string skippedReason;
};

struct OmrAnswerOptionScore {
  std::string option;
  double optionNumber = 0.0;
  bool hasOptionNumber = false;
  double fillRatio = 0.0;
  double rawScore = 0.0;
  double localBaseline = 0.0;
  double score = 0.0;
  double meanGray = 255.0;
  double expectedX = 0.0;
  double expectedY = 0.0;
  double blockAffineX = 0.0;
  double blockAffineY = 0.0;
  double rowCorrectedX = 0.0;
  double rowCorrectedY = 0.0;
  double refinedX = 0.0;
  double refinedY = 0.0;
  double refinementDx = 0.0;
  double refinementDy = 0.0;
  double refinementConfidence = 0.0;
  double rowConfidence = 0.0;
  std::string refinementMode;
  bool hasRefinement = false;
};

struct OmrAnswerQuestionResult {
  int questionNumber = 0;
  std::string status = "BLANK";
  std::string selectedOption;
  double selectedOptionNumber = 0.0;
  bool hasSelectedOption = false;
  bool hasSelectedOptionNumber = false;
  std::vector<std::string> selectedOptions;
  double confidence = 0.0;
  std::vector<OmrAnswerOptionScore> options;
};

struct OmrAnswerSummary {
  int total = 0;
  int marked = 0;
  int blank = 0;
  int multi = 0;
  int ambiguous = 0;
};

struct OmrAnswerReadResult {
  bool available = false;
  std::string reason;
  std::vector<OmrAnswerQuestionResult> questions;
  OmrAnswerSummary summary;
};

struct OmrStudentCodeDigitScore {
  std::string digit;
  double digitNumber = 0.0;
  bool hasDigitNumber = false;
  double fillRatio = 0.0;
  double rawScore = 0.0;
  double localBaseline = 0.0;
  double score = 0.0;
  double meanGray = 255.0;
  double expectedX = 0.0;
  double expectedY = 0.0;
  double blockAffineX = 0.0;
  double blockAffineY = 0.0;
  double rowCorrectedX = 0.0;
  double rowCorrectedY = 0.0;
  double refinedX = 0.0;
  double refinedY = 0.0;
  double refinementDx = 0.0;
  double refinementDy = 0.0;
  double refinementConfidence = 0.0;
  double rowConfidence = 0.0;
  std::string refinementMode;
  bool hasRefinement = false;
};

struct OmrStudentCodeColumnResult {
  int columnIndex = 0;
  int visualColumnIndex = 0;
  std::string status = "BLANK";
  std::string selectedDigit;
  double selectedDigitNumber = 0.0;
  bool hasSelectedDigit = false;
  bool hasSelectedDigitNumber = false;
  std::vector<std::string> selectedDigits;
  double confidence = 0.0;
  std::vector<OmrStudentCodeDigitScore> digits;
};

struct OmrStudentCodeSummary {
  int columns = 0;
  int marked = 0;
  int blank = 0;
  int multi = 0;
  int ambiguous = 0;
};

struct OmrStudentCodeReadResult {
  bool available = false;
  std::string reason;
  std::string status = "UNAVAILABLE";
  std::string value;
  std::string rawValue;
  bool complete = false;
  std::vector<OmrStudentCodeColumnResult> columns;
  OmrStudentCodeSummary summary;
};

struct OmrLiveResult {
  std::string status = "NO_SHEET";
  std::string action = "SEARCH_SHEET";
  bool canCapture = false;
  float confidence = 0.0f;
  std::string messageKey = "search_sheet";
  bool hasQuad = false;
  Quad quad;
  OmrLiveMetrics metrics;
  int frameWidth = 0;
  int frameHeight = 0;
  int64_t timestampMs = 0;
  std::string reason;
  int detectedLeftDots = 0;
  int detectedRightDots = 0;
  int stableFrames = 0;
  double processingTimeMs = 0.0;
};

struct OmrBubbleGridStats {
  std::string blockId;
  int expectedCentersCount = 0;
  int detectedCentersCount = 0;
  int usedCentersCount = 0;
  double detectedCoverage = 0.0;
  double meanDx = 0.0;
  double meanDy = 0.0;
  double appliedShiftRms = 0.0;
  double fitResidualRms = 99.0;
  // Compatibility alias for older diagnostics; equals appliedShiftRms.
  double rmsError = 0.0;
  double maxError = 0.0;
  double verticalDrift = 0.0;
  double horizontalDrift = 0.0;
  double confidence = 0.0;
  std::string mode = "template_fallback";
  std::string fallbackReason;
};

struct OmrAnswerRowResidual {
  std::string blockId;
  int rowIndex = 0;
  int questionNumber = 0;
  double expectedY = 0.0;
  double refinedYBefore = 0.0;
  double detectedMedianY = 0.0;
  double rowDy = 0.0;
  double rowDx = 0.0;
  double smoothedRowDy = 0.0;
  double smoothedRowDx = 0.0;
  int detectedCount = 0;
  double confidence = 0.0;
  double beforeRms = 0.0;
  double afterRms = 0.0;
};

struct OmrAnswerBlockRowResidualSummary {
  std::string blockId;
  double topRowsRmsBefore = 0.0;
  double topRowsRmsAfter = 0.0;
  double middleRowsRmsBefore = 0.0;
  double middleRowsRmsAfter = 0.0;
  double bottomRowsRmsBefore = 0.0;
  double bottomRowsRmsAfter = 0.0;
  double bottomRowsMaxBefore = 0.0;
  double bottomRowsMaxAfter = 0.0;
  double verticalDriftBefore = 0.0;
  double verticalDriftAfter = 0.0;
  bool bottomSystematicDrift = false;
};

struct OmrRailReprojectionStats {
  double meanError = 0.0;
  double rmsError = 0.0;
  double maxError = 0.0;
  double headerRms = 0.0;
  double studentCodeRms = 0.0;
  double answerRms = 0.0;
  int headerSamples = 0;
  int studentCodeSamples = 0;
  int answerSamples = 0;
  int sampleCount = 0;
};

struct NormalizeResult {
  bool success = false;
  std::string status = "ERROR";
  bool reviewRequired = false;
  std::vector<std::string> reviewReasons;
  std::string normalizedImagePath;
  bool examCodeUsesQr = false;
  OmrBarcodeResult barcode;
  OmrStudentCodeReadResult studentCode;
  OmrAnswerReadResult answers;
  std::string studentId;
  std::map<int, double> selectedAnswers;
  bool hasQuad = false;
  Quad quad;
  std::array<double, 9> homography = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0};
  OmrLiveMetrics metrics;
  std::string error;
  int detectedLeftDots = 0;
  int detectedRightDots = 0;
  int expectedDotsPerSide = 0;
  int rejectedCandidateCount = 0;
  std::string railDetectionMode;
  float railConfidence = 0.0f;
  float rowMatchScore = 0.0f;
  std::string homographyStrategyUsed;
  int leftDotsUsed = 0;
  int rightDotsUsed = 0;
  int matchedRowsUsed = 0;
  double reprojectionError = 0.0;
  OmrRailReprojectionStats railReprojection;
  bool fallbackUsed = false;
  bool barcodeAttempted = false;
  std::string barcodeSkippedReason;
  bool templateRefinementUsed = false;
  std::string bubbleRefinementMode = "template_fallback";
  std::vector<OmrBubbleGridStats> answerBlockRefinementStats;
  OmrBubbleGridStats studentCodeRefinementStats;
  std::vector<OmrAnswerRowResidual> answerRowResiduals;
  std::vector<OmrAnswerBlockRowResidualSummary> answerRowResidualSummaries;
  std::vector<std::string> warnings;
  double processingTimeMs = 0.0;
  std::vector<std::string> debugImagePaths;
};

} // namespace omr
