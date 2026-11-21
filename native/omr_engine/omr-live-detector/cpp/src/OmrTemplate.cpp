#include "omr/OmrTemplate.hpp"

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

std::vector<float> defaultRows() {
  return {
    100.8f, 139.7f, 178.7f, 217.5f, 256.5f, 295.3f, 334.3f,
    459.3f, 498.2f, 537.0f, 575.8f, 614.7f, 653.7f, 692.5f, 731.5f, 770.3f, 812.0f,
    937.0f, 978.7f, 1020.3f, 1062.0f, 1103.7f, 1145.3f, 1187.0f, 1228.7f, 1270.3f,
    1312.0f, 1353.7f, 1395.3f, 1437.0f, 1478.7f, 1520.3f, 1562.0f, 1603.7f, 1645.3f,
    1687.0f, 1728.7f, 1770.3f, 1812.0f, 1853.7f, 1895.3f, 1937.0f, 1978.7f, 2020.3f,
    2062.0f, 2103.7f, 2145.3f
  };
}

} // namespace

OmrTemplate OmrTemplate::fromJson(const std::string& json) {
  OmrTemplate tpl;
  tpl.registration.rowY = defaultRows();

  if (json.empty()) {
    return tpl;
  }

  assignInt(json, "version", tpl.version);
  if (tpl.version != 2) {
    throw std::invalid_argument("invalid_template: version must be 2");
  }
  if (const auto templateId = json::stringForKey(json, "templateId"); templateId.has_value()) {
    tpl.templateId = *templateId;
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
    }
    if (const auto regions = json::objectForKey(*liveDetection, "regions"); regions.has_value()) {
      if (const auto answersRegion = json::objectForKey(*regions, "answers"); answersRegion.has_value()) {
        if (const auto rect = json::rectForObject(*answersRegion); rect.has_value()) {
          tpl.answersRegion = *rect;
        }
      }
    }
  }

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

  if (tpl.width <= 0.0f || tpl.height <= 0.0f) {
    throw std::invalid_argument("invalid_template: reference render size must be positive");
  }
  if (tpl.registration.leftRailX >= tpl.registration.rightRailX) {
    throw std::invalid_argument("invalid_template: left rail must be before right rail");
  }
  if (tpl.registration.rowY.size() < 4) {
    throw std::invalid_argument("invalid_template: at least four rail rows are required");
  }
  if (tpl.barcode.enabled && !rectIsInsideReference(tpl.barcode.roi, tpl.width, tpl.height)) {
    throw std::invalid_argument("invalid_template: exam code ROI must be inside the reference image");
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
  if (tpl.studentCode.enabled) {
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

  return tpl;
}

OmrLiveOptions OmrTemplate::optionsFromJson(const std::string& json, const OmrTemplate& tpl) {
  OmrLiveOptions options;
  options.minDotsPerSideForLive = tpl.registration.minDotsPerSideForLive;
  options.minDotsPerSideForNormalize = tpl.registration.minDotsPerSideForNormalize;

  if (json.empty()) {
    return options;
  }

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

  if (options.minBrightness >= options.maxBrightness) {
    throw std::invalid_argument("invalid_options: minBrightness must be less than maxBrightness");
  }
  if (options.minCoverage >= options.maxCoverage) {
    throw std::invalid_argument("invalid_options: minCoverage must be less than maxCoverage");
  }

  return options;
}

} // namespace omr
