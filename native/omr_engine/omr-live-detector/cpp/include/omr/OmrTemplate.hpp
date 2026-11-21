#pragma once

#include <cstddef>
#include <map>
#include <string>
#include <vector>
#include "Geometry.hpp"
#include "OmrLiveOptions.hpp"

namespace omr {

struct OmrRegistrationTemplate {
  float leftRailX = 67.6f;
  float rightRailX = 1584.3f;
  float dotRadius = 15.1f;
  int expectedDotsPerSide = 47;
  int minDotsPerSideForLive = 16;
  int minDotsPerSideForNormalize = 28;
  std::vector<float> rowY;
};

struct OmrBarcodeTemplate {
  bool enabled = true;
  bool required = false;
  bool qrCodeOnly = false;
  std::string placement = "under_exam_code_box";
  std::vector<std::string> preferredFormats = {"QR_CODE", "DATA_MATRIX", "CODE_128"};
  Rect roi = {191.0f, 235.0f, 390.0f, 170.0f};
  std::vector<Rect> fallbackRois;
  std::string expectedPayloadRegex = "^([A-Za-z0-9_-]{2,80}|EXAM:[A-Za-z0-9_-]{2,80})$";
  bool quietZoneRequired = true;
  float minModulePxAfterWarp = 3.0f;
  bool decodeAfterPerspectiveCorrection = true;
};

struct OmrAnswerBlockTemplate {
  std::string id;
  int questionStart = 1;
  int questionCount = 0;
  std::map<std::string, float> xByOption;
  std::vector<float> yByRow;
  float bubbleRadius = 15.0f;
  Rect bbox;
  bool hasBbox = false;
};

struct OmrAnswerReadThresholds {
  float markedFillRatio = 0.38f;
  // Calibrated above the maximum clean-sheet annulus noise (0.2857) while
  // remaining well below genuinely filled bubbles in the reference scans.
  float blankMaxFillRatio = 0.35f;
  float multiMarkMinRatio = 0.40f;
  float ambiguousDelta = 0.14f;
};

struct OmrAnswerTemplate {
  bool enabled = false;
  int questionCount = 0;
  std::vector<std::string> options = {"1", "2", "3", "4"};
  std::vector<OmrAnswerBlockTemplate> blocks;
  OmrAnswerReadThresholds thresholds;
};

struct OmrStudentCodeTemplate {
  bool enabled = false;
  int columns = 0;
  // columns_vertical is the legacy layout: one code position per x coordinate
  // and digit choices running down the page. rows_horizontal matches the
  // supplied Lajvard sheet: one code position per y coordinate and digits
  // running horizontally across that row.
  std::string layout = "columns_vertical";
  std::string columnReadOrder = "ltr";
  std::string positionReadOrder = "top_to_bottom";
  std::vector<std::string> digitRowsTopToBottom = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"};
  std::vector<float> xByColumnLeftToRight;
  std::vector<float> yByDigitTopToBottom;
  std::vector<std::string> digitOptionsLeftToRight;
  std::vector<float> xByDigitLeftToRight;
  std::vector<float> yByPositionTopToBottom;
  float bubbleRadius = 15.0f;
  OmrAnswerReadThresholds thresholds;

  bool usesHorizontalRows() const {
    return layout == "rows_horizontal";
  }

  const std::vector<std::string>& digitLabelsByChoiceOrder() const {
    return usesHorizontalRows() ? digitOptionsLeftToRight : digitRowsTopToBottom;
  }

  const std::vector<float>& bubbleXs() const {
    return usesHorizontalRows() ? xByDigitLeftToRight : xByColumnLeftToRight;
  }

  const std::vector<float>& bubbleYs() const {
    return usesHorizontalRows() ? yByPositionTopToBottom : yByDigitTopToBottom;
  }

  Point bubbleCenter(std::size_t visualPositionIndex, std::size_t digitIndex) const {
    return usesHorizontalRows()
      ? Point{xByDigitLeftToRight[digitIndex], yByPositionTopToBottom[visualPositionIndex]}
      : Point{xByColumnLeftToRight[visualPositionIndex], yByDigitTopToBottom[digitIndex]};
  }
};

struct OmrTemplate {
  int version = 2;
  std::string templateId = "lajvard_exam_sheet_120q_barcode_v2";
  float width = 1656.0f;
  float height = 2339.0f;
  float dpi = 200.0f;
  Rect examCodeBox = {191.0f, 64.0f, 390.0f, 158.0f};
  OmrBarcodeTemplate barcode;
  OmrStudentCodeTemplate studentCode;
  OmrAnswerTemplate answers;
  OmrRegistrationTemplate registration;
  Rect answersRegion = {309.0f, 911.0f, 1038.0f, 1269.0f};

  static OmrTemplate fromJson(const std::string& json);
  static OmrLiveOptions optionsFromJson(const std::string& json, const OmrTemplate& tpl);
};

} // namespace omr
