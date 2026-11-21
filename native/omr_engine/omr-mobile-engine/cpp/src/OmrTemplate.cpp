#include "omr/OmrTemplate.hpp"

#include <algorithm>
#include <cmath>
#include <regex>
#include <set>
#include <stdexcept>
#include "utils/Json.hpp"

namespace omr {

namespace {

void assignFloat(const std::string& json, const std::string& key, float& target) {
  if (const auto value = json::numberForKey(json, key); value.has_value()) {
    target = static_cast<float>(*value);
  }
}

void assignInt(const std::string& json, const std::string& key, int& target) {
  if (const auto value = json::intForKey(json, key); value.has_value()) {
    target = *value;
  }
}

void assignBool(const std::string& json, const std::string& key, bool& target) {
  if (const auto value = json::boolForKey(json, key); value.has_value()) {
    target = *value;
  }
}

void parseMachineCodeTemplate(const std::string& json, OmrBarcodeTemplate& target) {
  assignBool(json, "enabled", target.enabled);
  assignBool(json, "required", target.required);
  if (const auto placement = json::stringForKey(json, "placement"); placement.has_value()) {
    target.placement = *placement;
  }
  if (const auto roi = json::objectForKey(json, "roi"); roi.has_value()) {
    if (const auto rect = json::rectForObject(*roi); rect.has_value()) {
      target.roi = *rect;
    }
  }
  if (const auto regex = json::stringForKey(json, "expectedPayloadRegex"); regex.has_value()) {
    target.expectedPayloadRegex = *regex;
  }
  assignBool(json, "quietZoneRequired", target.quietZoneRequired);
  assignFloat(json, "minModulePxAfterWarp", target.minModulePxAfterWarp);
  assignBool(json, "decodeAfterPerspectiveCorrection", target.decodeAfterPerspectiveCorrection);
  const auto formats = json::stringArrayForKey(json, "preferredFormats");
  if (!formats.empty()) {
    target.preferredFormats = formats;
  }
  const auto fallbackObjects = json::objectArrayForKey(json, "fallbackRois");
  if (!fallbackObjects.empty()) {
    target.fallbackRois.clear();
    for (const auto& fallbackObject : fallbackObjects) {
      if (const auto rect = json::rectForObject(fallbackObject); rect.has_value()) {
        target.fallbackRois.push_back(*rect);
      }
    }
  }
}

bool isQrFormatName(const std::string& format) {
  return format == "QR_CODE" || format == "QRCode" || format == "QR Code";
}

bool rectIsInsideReference(const Rect& rect, float width, float height) {
  return rect.x >= 0.0f && rect.y >= 0.0f && rect.width > 0.0f && rect.height > 0.0f &&
    rect.x + rect.width <= width && rect.y + rect.height <= height;
}

bool rectContains(const Rect& outer, const Rect& inner) {
  constexpr float epsilon = 0.01f;
  return inner.x + epsilon >= outer.x &&
    inner.y + epsilon >= outer.y &&
    inner.x + inner.width <= outer.x + outer.width + epsilon &&
    inner.y + inner.height <= outer.y + outer.height + epsilon;
}

void parseLiveOptions(const std::string& json, OmrLiveOptions& options) {
  assignFloat(json, "minBrightness", options.minBrightness);
  assignFloat(json, "maxBrightness", options.maxBrightness);
  assignFloat(json, "minSharpness", options.minSharpness);
  assignFloat(json, "maxGlare", options.maxGlare);
  assignFloat(json, "minContrast", options.minContrast);
  assignFloat(json, "minCoverage", options.minCoverage);
  assignFloat(json, "maxCoverage", options.maxCoverage);
  assignFloat(json, "minRailScore", options.minRailScore);
  assignFloat(json, "minAngleScore", options.minAngleScore);
  assignInt(json, "minDotsPerSideForLive", options.minDotsPerSideForLive);
  assignInt(json, "minDotsPerSideForNormalize", options.minDotsPerSideForNormalize);
  assignInt(json, "stableFrameCount", options.stableFrameCount);
  assignInt(json, "uiUpdateEveryMs", options.uiUpdateEveryMs);
  assignBool(json, "debug", options.debug);
}

void validateLiveOptions(const OmrLiveOptions& options) {
  const auto unitValue = [](float value) { return value >= 0.0f && value <= 1.0f; };
  if (!unitValue(options.minBrightness) || !unitValue(options.maxBrightness) ||
      options.minBrightness >= options.maxBrightness) {
    throw std::invalid_argument("invalid_options: brightness bounds must satisfy 0 <= min < max <= 1");
  }
  if (!unitValue(options.minCoverage) || !unitValue(options.maxCoverage) ||
      options.minCoverage >= options.maxCoverage) {
    throw std::invalid_argument("invalid_options: coverage bounds must satisfy 0 <= min < max <= 1");
  }
  if (!unitValue(options.minSharpness) || !unitValue(options.maxGlare) ||
      !unitValue(options.minContrast) || !unitValue(options.minRailScore) ||
      !unitValue(options.minAngleScore)) {
    throw std::invalid_argument("invalid_options: quality thresholds must be in the 0..1 range");
  }
  if (options.minDotsPerSideForLive <= 0 || options.minDotsPerSideForNormalize <= 0) {
    throw std::invalid_argument("invalid_options: minimum rail-dot counts must be positive");
  }
  if (options.stableFrameCount <= 0 || options.uiUpdateEveryMs <= 0) {
    throw std::invalid_argument("invalid_options: timing values must be positive");
  }
}

void validateReadThresholds(const OmrAnswerReadThresholds& thresholds, const char* label) {
  const auto unitValue = [](float value) { return value >= 0.0f && value <= 1.0f; };
  if (!unitValue(thresholds.markedFillRatio) ||
      !unitValue(thresholds.blankMaxFillRatio) ||
      !unitValue(thresholds.multiMarkMinRatio) ||
      !unitValue(thresholds.ambiguousDelta)) {
    throw std::invalid_argument(std::string("invalid_template: ") + label + " thresholds must be in 0..1");
  }
  if (thresholds.blankMaxFillRatio >= thresholds.markedFillRatio) {
    throw std::invalid_argument(std::string("invalid_template: ") + label + " blank threshold must be below marked threshold");
  }
}

} // namespace

OmrTemplate OmrTemplate::fromJson(const std::string& json) {
  if (json.empty()) {
    throw std::invalid_argument("invalid_template: JSON is empty");
  }

  OmrTemplate tpl;

  assignInt(json, "version", tpl.version);
  if (tpl.version != 2) {
    throw std::invalid_argument("invalid_template: version must be 2");
  }
  if (const auto templateId = json::stringForKey(json, "templateId"); templateId.has_value()) {
    tpl.templateId = *templateId;
  }
  tpl.geometryId = json::topLevelStringForKey(json, "geometryId").value_or("");
  if (const auto sheetType = json::topLevelObjectForKey(json, "sheetType"); sheetType.has_value()) {
    if (const auto code = json::stringForKey(*sheetType, "code"); code.has_value()) {
      tpl.sheetTypeCode = *code;
    }
    assignInt(*sheetType, "questionCount", tpl.sheetTypeQuestionCount);
  }

  const auto coordinateSystem = json::topLevelObjectForKey(json, "coordinateSystem");
  if (const auto referenceRender = coordinateSystem.has_value()
        ? json::objectForKey(*coordinateSystem, "referenceRender")
        : json::objectForKey(json, "referenceRender"); referenceRender.has_value()) {
    assignFloat(*referenceRender, "width", tpl.width);
    assignFloat(*referenceRender, "height", tpl.height);
    assignFloat(*referenceRender, "dpi", tpl.dpi);
  }

  if (const auto examCode = json::topLevelObjectForKey(json, "examCode"); examCode.has_value()) {
    if (const auto box = json::objectForKey(*examCode, "box"); box.has_value()) {
      if (const auto rect = json::rectForObject(*box); rect.has_value()) {
        tpl.examCodeBox = *rect;
      }
    }
    const auto qrCode = json::objectForKey(*examCode, "qrCode");
    const auto legacyBarcode = json::objectForKey(*examCode, "barcode");
    if (qrCode.has_value() && legacyBarcode.has_value()) {
      throw std::invalid_argument("invalid_template: examCode must contain either qrCode or barcode, not both");
    }
    if (qrCode.has_value()) {
      tpl.barcode.qrCodeOnly = true;
      tpl.barcode.preferredFormats = {"QR_CODE"};
      tpl.barcode.fallbackRois.clear();
      parseMachineCodeTemplate(*qrCode, tpl.barcode);
    } else if (legacyBarcode.has_value()) {
      parseMachineCodeTemplate(*legacyBarcode, tpl.barcode);
    }
  }

  if (const auto liveDetection = json::topLevelObjectForKey(json, "liveDetection"); liveDetection.has_value()) {
    if (const auto registration = json::objectForKey(*liveDetection, "registration"); registration.has_value()) {
      assignFloat(*registration, "leftRailX", tpl.registration.leftRailX);
      assignFloat(*registration, "rightRailX", tpl.registration.rightRailX);
      assignFloat(*registration, "dotRadius", tpl.registration.dotRadius);
      assignInt(*registration, "expectedDotsPerSide", tpl.registration.expectedDotsPerSide);
      assignInt(*registration, "minDotsPerSideForLive", tpl.registration.minDotsPerSideForLive);
      assignInt(*registration, "minDotsPerSideForNormalize", tpl.registration.minDotsPerSideForNormalize);
      const auto rows = json::numberArrayForKey(*registration, "rowY");
      if (rows.size() >= 4) {
        tpl.registration.rowY.clear();
        for (const auto row : rows) {
          tpl.registration.rowY.push_back(static_cast<float>(row));
        }
      }
      for (const auto row : json::numberArrayForKey(*registration, "rightRowY")) {
        tpl.registration.rightRowY.push_back(static_cast<float>(row));
      }
    }
    if (const auto thresholds = json::objectForKey(*liveDetection, "thresholds"); thresholds.has_value()) {
      parseLiveOptions(*thresholds, tpl.liveOptions);
    }
    if (const auto regions = json::objectForKey(*liveDetection, "regions"); regions.has_value()) {
      if (const auto answersRegion = json::objectForKey(*regions, "answers"); answersRegion.has_value()) {
        if (const auto rect = json::rectForObject(*answersRegion); rect.has_value()) {
          tpl.answersRegion = *rect;
        }
      }
    }
  }
  tpl.liveOptions.minDotsPerSideForLive = tpl.registration.minDotsPerSideForLive;
  tpl.liveOptions.minDotsPerSideForNormalize = tpl.registration.minDotsPerSideForNormalize;

  if (const auto answers = json::topLevelObjectForKey(json, "answers"); answers.has_value()) {
    tpl.answers.enabled = true;
    assignInt(*answers, "questionCount", tpl.answers.questionCount);
    const auto options = json::stringArrayForKey(*answers, "options");
    if (!options.empty()) {
      tpl.answers.options = options;
    }
    if (const auto thresholds = json::objectForKey(*answers, "finalReadThresholds"); thresholds.has_value()) {
      assignFloat(*thresholds, "markedFillRatio", tpl.answers.thresholds.markedFillRatio);
      assignFloat(*thresholds, "blankMaxFillRatio", tpl.answers.thresholds.blankMaxFillRatio);
      assignFloat(*thresholds, "multiMarkMinRatio", tpl.answers.thresholds.multiMarkMinRatio);
      assignFloat(*thresholds, "ambiguousDelta", tpl.answers.thresholds.ambiguousDelta);
    }

    const auto blockObjects = json::objectArrayForKey(*answers, "blocks");
    if (!blockObjects.empty()) {
      tpl.answers.blocks.clear();
      for (const auto& blockObject : blockObjects) {
        OmrAnswerBlockTemplate block;
        if (const auto id = json::stringForKey(blockObject, "id"); id.has_value()) {
          block.id = *id;
        }
        assignInt(blockObject, "questionStart", block.questionStart);
        assignInt(blockObject, "questionCount", block.questionCount);
        assignFloat(blockObject, "bubbleRadius", block.bubbleRadius);
        if (const auto bbox = json::objectForKey(blockObject, "bbox"); bbox.has_value()) {
          if (const auto rect = json::rectForObject(*bbox); rect.has_value()) {
            block.bbox = *rect;
            block.hasBbox = true;
          }
        }
        block.yByRow.clear();
        for (const auto row : json::numberArrayForKey(blockObject, "yByRow")) {
          block.yByRow.push_back(static_cast<float>(row));
        }
        if (const auto xByOption = json::objectForKey(blockObject, "xByOption"); xByOption.has_value()) {
          for (const auto& option : tpl.answers.options) {
            if (const auto x = json::numberForKey(*xByOption, option); x.has_value()) {
              block.xByOption[option] = static_cast<float>(*x);
            }
          }
        }
        if (block.questionCount > 0 && !block.yByRow.empty() && !block.xByOption.empty()) {
          tpl.answers.blocks.push_back(block);
        }
      }
    }
  }

  if (const auto studentCode = json::topLevelObjectForKey(json, "studentCode"); studentCode.has_value()) {
    tpl.studentCode.enabled = true;
    assignInt(*studentCode, "columns", tpl.studentCode.columns);
    if (const auto layout = json::stringForKey(*studentCode, "layout"); layout.has_value()) {
      tpl.studentCode.layout = *layout;
    }
    if (const auto order = json::stringForKey(*studentCode, "columnReadOrder"); order.has_value()) {
      tpl.studentCode.columnReadOrder = *order;
    }
    if (const auto order = json::stringForKey(*studentCode, "positionReadOrder"); order.has_value()) {
      tpl.studentCode.positionReadOrder = *order;
    }
    const auto digitRows = json::stringArrayForKey(*studentCode, "digitRowsTopToBottom");
    if (!digitRows.empty()) {
      tpl.studentCode.digitRowsTopToBottom = digitRows;
    }
    tpl.studentCode.xByColumnLeftToRight.clear();
    for (const auto x : json::numberArrayForKey(*studentCode, "xByColumnLeftToRight")) {
      tpl.studentCode.xByColumnLeftToRight.push_back(static_cast<float>(x));
    }
    tpl.studentCode.yByDigitTopToBottom.clear();
    for (const auto y : json::numberArrayForKey(*studentCode, "yByDigitTopToBottom")) {
      tpl.studentCode.yByDigitTopToBottom.push_back(static_cast<float>(y));
    }
    tpl.studentCode.digitOptionsLeftToRight = json::stringArrayForKey(*studentCode, "digitOptionsLeftToRight");
    tpl.studentCode.xByDigitLeftToRight.clear();
    for (const auto x : json::numberArrayForKey(*studentCode, "xByDigitLeftToRight")) {
      tpl.studentCode.xByDigitLeftToRight.push_back(static_cast<float>(x));
    }
    tpl.studentCode.yByPositionTopToBottom.clear();
    for (const auto y : json::numberArrayForKey(*studentCode, "yByPositionTopToBottom")) {
      tpl.studentCode.yByPositionTopToBottom.push_back(static_cast<float>(y));
    }
    assignFloat(*studentCode, "bubbleRadius", tpl.studentCode.bubbleRadius);
    if (const auto thresholds = json::objectForKey(*studentCode, "finalReadThresholds"); thresholds.has_value()) {
      assignFloat(*thresholds, "markedFillRatio", tpl.studentCode.thresholds.markedFillRatio);
      assignFloat(*thresholds, "blankMaxFillRatio", tpl.studentCode.thresholds.blankMaxFillRatio);
      assignFloat(*thresholds, "multiMarkMinRatio", tpl.studentCode.thresholds.multiMarkMinRatio);
      assignFloat(*thresholds, "ambiguousDelta", tpl.studentCode.thresholds.ambiguousDelta);
    }
  }

  if (tpl.templateId.empty() || tpl.templateId == "unconfigured") {
    throw std::invalid_argument("invalid_template: templateId is required");
  }
  if (tpl.width <= 0.0f || tpl.height <= 0.0f || tpl.dpi <= 0.0f) {
    throw std::invalid_argument("invalid_template: reference render size and DPI must be positive");
  }
  if (
    tpl.registration.leftRailX < 0.0f ||
    tpl.registration.rightRailX > tpl.width ||
    tpl.registration.leftRailX >= tpl.registration.rightRailX
  ) {
    throw std::invalid_argument("invalid_template: rail x coordinates must be ordered inside the reference image");
  }
  if (tpl.registration.dotRadius <= 0.0f) {
    throw std::invalid_argument("invalid_template: rail dot radius must be positive");
  }
  if (
    tpl.registration.expectedDotsPerSide < 4 ||
    static_cast<int>(tpl.registration.rowY.size()) != tpl.registration.expectedDotsPerSide
  ) {
    throw std::invalid_argument("invalid_template: rail row count must match expectedDotsPerSide");
  }
  float previousRow = -1.0f;
  for (const float row : tpl.registration.rowY) {
    if (row < 0.0f || row > tpl.height || row <= previousRow) {
      throw std::invalid_argument("invalid_template: rail rows must be strictly increasing inside the reference image");
    }
    previousRow = row;
  }
  if (!tpl.registration.rightRowY.empty()) {
    if (tpl.registration.rightRowY.size() != tpl.registration.rowY.size()) {
      throw std::invalid_argument("invalid_template: right rail row count must match expectedDotsPerSide");
    }
    previousRow = -1.0f;
    for (const float row : tpl.registration.rightRowY) {
      if (!std::isfinite(row) || row < 0.0f || row > tpl.height || row <= previousRow) {
        throw std::invalid_argument("invalid_template: right rail rows must be strictly increasing inside the reference image");
      }
      previousRow = row;
    }
  }
  if (
    tpl.registration.minDotsPerSideForLive <= 0 ||
    tpl.registration.minDotsPerSideForNormalize <= 0 ||
    tpl.registration.minDotsPerSideForLive > tpl.registration.expectedDotsPerSide ||
    tpl.registration.minDotsPerSideForNormalize > tpl.registration.expectedDotsPerSide
  ) {
    throw std::invalid_argument("invalid_template: minimum rail-dot counts must be positive and not exceed the expected count");
  }
  validateLiveOptions(tpl.liveOptions);

  if (tpl.barcode.enabled && !rectIsInsideReference(tpl.examCodeBox, tpl.width, tpl.height)) {
    throw std::invalid_argument("invalid_template: exam code box must be inside the reference image");
  }
  if (tpl.barcode.enabled && !rectIsInsideReference(tpl.barcode.roi, tpl.width, tpl.height)) {
    throw std::invalid_argument("invalid_template: exam code ROI must be inside the reference image");
  }
  if (tpl.barcode.enabled && !rectContains(tpl.examCodeBox, tpl.barcode.roi)) {
    throw std::invalid_argument("invalid_template: exam code box must contain the QR ROI");
  }
  for (const auto& fallback : tpl.barcode.fallbackRois) {
    if (!rectIsInsideReference(fallback, tpl.width, tpl.height)) {
      throw std::invalid_argument("invalid_template: fallback QR ROI must be inside the reference image");
    }
  }
  if (tpl.barcode.qrCodeOnly && (
      tpl.barcode.preferredFormats.size() != 1 ||
      !isQrFormatName(tpl.barcode.preferredFormats.front())
    )) {
    throw std::invalid_argument("invalid_template: examCode.qrCode only supports QR_CODE");
  }
  if (tpl.answers.enabled && tpl.answers.blocks.empty()) {
    throw std::invalid_argument("invalid_template: answers.blocks must contain readable blocks");
  }
  if (tpl.answers.enabled) {
    if (tpl.answers.questionCount <= 0) {
      throw std::invalid_argument("invalid_template: answers.questionCount must be positive");
    }
    if (tpl.answers.options.size() < 2) {
      throw std::invalid_argument("invalid_template: at least two answer options are required");
    }
    const std::set<std::string> uniqueOptions(tpl.answers.options.begin(), tpl.answers.options.end());
    if (uniqueOptions.size() != tpl.answers.options.size()) {
      throw std::invalid_argument("invalid_template: answer options must be unique");
    }
    if (!rectIsInsideReference(tpl.answersRegion, tpl.width, tpl.height)) {
      throw std::invalid_argument("invalid_template: answers region must be inside the reference image");
    }
    validateReadThresholds(tpl.answers.thresholds, "answer");

    std::vector<int> questionCoverage(static_cast<size_t>(tpl.answers.questionCount + 1), 0);
    for (const auto& block : tpl.answers.blocks) {
      if (block.questionStart <= 0 || block.questionCount <= 0 ||
          block.questionStart + block.questionCount - 1 > tpl.answers.questionCount) {
        throw std::invalid_argument("invalid_template: answer block question range is outside answers.questionCount");
      }
      if (static_cast<int>(block.yByRow.size()) != block.questionCount) {
        throw std::invalid_argument("invalid_template: answer block y rows must match its question count");
      }
      if (block.xByOption.size() != tpl.answers.options.size() || block.bubbleRadius <= 0.0f) {
        throw std::invalid_argument("invalid_template: every answer block must define all options and a positive radius");
      }
      if (block.hasBbox && !rectIsInsideReference(block.bbox, tpl.width, tpl.height)) {
        throw std::invalid_argument("invalid_template: answer block bounding box must be inside the reference image");
      }
      for (const auto& option : tpl.answers.options) {
        const auto x = block.xByOption.find(option);
        if (x == block.xByOption.end() || x->second < 0.0f || x->second > tpl.width) {
          throw std::invalid_argument("invalid_template: answer option x coordinate is missing or outside the reference image");
        }
      }
      float previousAnswerRow = -1.0f;
      for (const float row : block.yByRow) {
        if (row < 0.0f || row > tpl.height || row <= previousAnswerRow) {
          throw std::invalid_argument("invalid_template: answer rows must be strictly increasing inside the reference image");
        }
        previousAnswerRow = row;
      }
      for (int question = block.questionStart;
           question < block.questionStart + block.questionCount;
           ++question) {
        questionCoverage[static_cast<size_t>(question)] += 1;
      }
    }
    for (int question = 1; question <= tpl.answers.questionCount; ++question) {
      if (questionCoverage[static_cast<size_t>(question)] != 1) {
        throw std::invalid_argument("invalid_template: answer blocks must cover every question exactly once");
      }
    }
  }
  if (!tpl.sheetTypeCode.empty()) {
    if (tpl.sheetTypeQuestionCount <= 0) {
      throw std::invalid_argument("invalid_template: sheetType.questionCount must be positive");
    }
    if (
      tpl.answers.enabled &&
      tpl.answers.questionCount != tpl.sheetTypeQuestionCount
    ) {
      throw std::invalid_argument("invalid_template: sheetType question count must match answers.questionCount");
    }
  }
  if (tpl.studentCode.enabled) {
    validateReadThresholds(tpl.studentCode.thresholds, "student-code");
    if (tpl.studentCode.columns <= 0) {
      throw std::invalid_argument("invalid_template: studentCode.columns must be positive");
    }
    if (tpl.studentCode.layout == "rows_horizontal") {
      if (static_cast<int>(tpl.studentCode.yByPositionTopToBottom.size()) != tpl.studentCode.columns) {
        throw std::invalid_argument("invalid_template: studentCode horizontal positions must match columns");
      }
      if (
        tpl.studentCode.xByDigitLeftToRight.empty() ||
        tpl.studentCode.xByDigitLeftToRight.size() != tpl.studentCode.digitOptionsLeftToRight.size()
      ) {
        throw std::invalid_argument("invalid_template: studentCode horizontal digits must match x positions");
      }
      if (
        tpl.studentCode.positionReadOrder != "top_to_bottom" &&
        tpl.studentCode.positionReadOrder != "bottom_to_top"
      ) {
        throw std::invalid_argument("invalid_template: unsupported studentCode.positionReadOrder");
      }
    } else if (tpl.studentCode.layout == "columns_vertical") {
      if (static_cast<int>(tpl.studentCode.xByColumnLeftToRight.size()) != tpl.studentCode.columns) {
        throw std::invalid_argument("invalid_template: studentCode x columns must match columns");
      }
      if (tpl.studentCode.yByDigitTopToBottom.size() != tpl.studentCode.digitRowsTopToBottom.size()) {
        throw std::invalid_argument("invalid_template: studentCode digit rows must match y rows");
      }
      if (tpl.studentCode.columnReadOrder != "ltr" && tpl.studentCode.columnReadOrder != "rtl") {
        throw std::invalid_argument("invalid_template: unsupported studentCode.columnReadOrder");
      }
    } else {
      throw std::invalid_argument("invalid_template: unsupported studentCode.layout");
    }
  }

  if (tpl.barcode.enabled && !tpl.barcode.expectedPayloadRegex.empty()) {
    try {
      (void)std::regex(tpl.barcode.expectedPayloadRegex);
    } catch (const std::regex_error&) {
      throw std::invalid_argument("invalid_template: expectedPayloadRegex is invalid");
    }
  }

  const auto variants = json::objectArrayForKey(json, "geometryVariants");
  if (variants.size() > 4) {
    throw std::invalid_argument("invalid_template: at most four geometry variants are supported");
  }
  std::set<std::string> geometryIds{tpl.geometryId};
  for (const auto& variantJson : variants) {
    if (!json::objectArrayForKey(variantJson, "geometryVariants").empty()) {
      throw std::invalid_argument("invalid_template: nested geometry variants are not supported");
    }
    OmrTemplate variant = OmrTemplate::fromJson(variantJson);
    if (tpl.sheetTypeCode.empty() || variant.templateId != tpl.templateId ||
        variant.sheetTypeCode != tpl.sheetTypeCode ||
        variant.sheetTypeQuestionCount != tpl.sheetTypeQuestionCount ||
        variant.width != tpl.width || variant.height != tpl.height || variant.dpi != tpl.dpi ||
        variant.answers.questionCount != tpl.answers.questionCount ||
        variant.answers.options != tpl.answers.options ||
        variant.answers.blocks.size() != tpl.answers.blocks.size() ||
        variant.registration.expectedDotsPerSide != tpl.registration.expectedDotsPerSide ||
        variant.geometryId.empty() || !geometryIds.insert(variant.geometryId).second) {
      throw std::invalid_argument("invalid_template: geometry variant must preserve the physical sheet binding");
    }
    for (std::size_t index = 0; index < tpl.answers.blocks.size(); ++index) {
      if (variant.answers.blocks[index].questionStart != tpl.answers.blocks[index].questionStart ||
          variant.answers.blocks[index].questionCount != tpl.answers.blocks[index].questionCount) {
        throw std::invalid_argument("invalid_template: geometry variant must preserve question ordering");
      }
    }
    // Geometry cannot change which credentials or thresholds are accepted.
    variant.examCodeBox = tpl.examCodeBox;
    variant.barcode = tpl.barcode;
    variant.studentCode = tpl.studentCode;
    variant.answers.enabled = tpl.answers.enabled;
    variant.answers.thresholds = tpl.answers.thresholds;
    variant.liveOptions = tpl.liveOptions;
    (void)OmrTemplate::optionsFromJson("", variant);
    tpl.geometryVariants.push_back(std::move(variant));
  }
  return tpl;
}

OmrLiveOptions OmrTemplate::optionsFromJson(const std::string& json, const OmrTemplate& tpl) {
  OmrLiveOptions options = tpl.liveOptions;
  if (!json.empty()) {
    parseLiveOptions(json, options);
  }
  validateLiveOptions(options);
  if (
    options.minDotsPerSideForLive > tpl.registration.expectedDotsPerSide ||
    options.minDotsPerSideForNormalize > tpl.registration.expectedDotsPerSide
  ) {
    throw std::invalid_argument("invalid_options: minimum rail-dot counts cannot exceed the template row count");
  }

  return options;
}

} // namespace omr
