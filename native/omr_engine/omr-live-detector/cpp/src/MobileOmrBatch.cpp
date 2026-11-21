#include "omr/MobileOmrBatch.hpp"

#include <algorithm>
#include <fstream>
#include <numeric>
#include <sstream>
#include <stdexcept>
#include <utility>
#include "omr/NormalizeEngine.hpp"
#include "omr/OmrTemplate.hpp"
#include "utils/Json.hpp"

namespace omr {

namespace {

std::string readTextFile(const std::string& path) {
  std::ifstream input(json::stripFileUri(path));
  if (!input) {
    throw std::runtime_error("failed_to_open_file: " + path);
  }
  std::ostringstream buffer;
  buffer << input.rdbuf();
  return buffer.str();
}

std::string jsonEscape(const std::string& value) {
  std::ostringstream out;
  for (const char ch : value) {
    switch (ch) {
      case '\\': out << "\\\\"; break;
      case '"': out << "\\\""; break;
      case '\n': out << "\\n"; break;
      case '\r': out << "\\r"; break;
      case '\t': out << "\\t"; break;
      default:
        if (static_cast<unsigned char>(ch) < 0x20) {
          out << "\\u00";
          const char* hex = "0123456789abcdef";
          const auto byte = static_cast<unsigned char>(ch);
          out << hex[(byte >> 4) & 0x0f] << hex[byte & 0x0f];
        } else {
          out << ch;
        }
        break;
    }
  }
  return out.str();
}

void writeJsonString(std::ostream& out, const std::string& value) {
  out << "\"" << jsonEscape(value) << "\"";
}

void writeJsonStringOrNull(std::ostream& out, const std::string& value) {
  if (value.empty()) {
    out << "null";
    return;
  }
  writeJsonString(out, value);
}

bool hasUsableExamCode(const OmrBarcodeResult& code) {
  return code.found && code.status == "FOUND" && !code.text.empty();
}

bool isQrCodeFormat(const std::string& format) {
  return format == "QRCode" || format == "QR_CODE" || format == "QR Code";
}

std::string firstAvailableExamCode(const MobileOmrBatchRequest& request, const std::vector<NormalizeResult>& sheets) {
  if (!request.examCode.empty()) {
    return request.examCode;
  }
  for (const auto& sheet : sheets) {
    if (hasUsableExamCode(sheet.barcode)) {
      return sheet.barcode.text;
    }
  }
  return {};
}

std::string examCodeSource(const MobileOmrBatchRequest& request, const std::vector<NormalizeResult>& sheets) {
  if (!request.examCode.empty()) {
    return "override";
  }
  for (const auto& sheet : sheets) {
    if (hasUsableExamCode(sheet.barcode)) {
      return isQrCodeFormat(sheet.barcode.format) ? "qr_code" : "barcode";
    }
  }
  return "none";
}

void writeStringArray(std::ostream& out, const std::vector<std::string>& values) {
  out << "[";
  for (size_t index = 0; index < values.size(); ++index) {
    if (index > 0) {
      out << ",";
    }
    writeJsonString(out, values[index]);
  }
  out << "]";
}

void writeAnswerSummary(std::ostream& out, const OmrAnswerSummary& summary) {
  out << "{"
      << "\"total\":" << summary.total << ","
      << "\"marked\":" << summary.marked << ","
      << "\"blank\":" << summary.blank << ","
      << "\"multi\":" << summary.multi << ","
      << "\"ambiguous\":" << summary.ambiguous
      << "}";
}

void writeStudentCodeSummary(std::ostream& out, const OmrStudentCodeSummary& summary) {
  out << "{"
      << "\"columns\":" << summary.columns << ","
      << "\"marked\":" << summary.marked << ","
      << "\"blank\":" << summary.blank << ","
      << "\"multi\":" << summary.multi << ","
      << "\"ambiguous\":" << summary.ambiguous
      << "}";
}

void writeBarcode(std::ostream& out, const OmrBarcodeResult& barcode) {
  out << "{"
      << "\"found\":" << (barcode.found ? "true" : "false") << ","
      << "\"attempted\":" << (barcode.attempted ? "true" : "false") << ","
      << "\"status\":";
  writeJsonString(out, barcode.status);
  out << ",\"text\":";
  writeJsonStringOrNull(out, barcode.text);
  out << ",\"format\":";
  writeJsonStringOrNull(out, barcode.format);
  out << ",\"confidence\":" << barcode.confidence << ",\"error\":";
  writeJsonString(out, barcode.error);
  out << ",\"skippedReason\":";
  writeJsonString(out, barcode.skippedReason);
  out << "}";
}

void writeStudentCode(std::ostream& out, const OmrStudentCodeReadResult& code) {
  out << "{"
      << "\"available\":" << (code.available ? "true" : "false") << ","
      << "\"complete\":" << (code.complete ? "true" : "false") << ","
      << "\"status\":";
  writeJsonString(out, code.status);
  out << ",\"value\":";
  writeJsonStringOrNull(out, code.value);
  out << ",\"rawValue\":";
  writeJsonStringOrNull(out, code.rawValue);
  out << ",\"reason\":";
  writeJsonString(out, code.reason);
  out << ",\"summary\":";
  writeStudentCodeSummary(out, code.summary);
  out << "}";
}

void writeSelectedAnswersObject(std::ostream& out, const OmrAnswerReadResult& answers) {
  out << "{";
  bool first = true;
  for (const auto& question : answers.questions) {
    if (question.status != "MARKED" || !question.hasSelectedOptionNumber) {
      continue;
    }
    if (!first) {
      out << ",";
    }
    first = false;
    writeJsonString(out, std::to_string(question.questionNumber));
    out << ":" << question.selectedOptionNumber;
  }
  out << "}";
}

void writeQuestion(std::ostream& out, const OmrAnswerQuestionResult& question) {
  out << "{"
      << "\"question\":" << question.questionNumber << ","
      << "\"status\":";
  writeJsonString(out, question.status);
  out << ",\"selectedOption\":";
  writeJsonStringOrNull(out, question.hasSelectedOption ? question.selectedOption : "");
  out << ",\"selectedOptionNumber\":";
  if (question.hasSelectedOptionNumber) {
    out << question.selectedOptionNumber;
  } else {
    out << "null";
  }
  out << ",\"selectedOptions\":";
  writeStringArray(out, question.selectedOptions);
  out << ",\"rawFillRatios\":[";
  for (int optionNumber = 1; optionNumber <= 4; ++optionNumber) {
    if (optionNumber > 1) {
      out << ",";
    }
    const auto option = std::find_if(
      question.options.begin(),
      question.options.end(),
      [optionNumber](const OmrAnswerOptionScore& candidate) {
        return candidate.hasOptionNumber &&
          static_cast<int>(candidate.optionNumber) == optionNumber;
      }
    );
    out << (option == question.options.end() ? 0.0 : option->fillRatio);
  }
  out << "]";
  out << ",\"confidence\":" << question.confidence
      << "}";
}

void writeQuestions(std::ostream& out, const OmrAnswerReadResult& answers) {
  out << "[";
  for (size_t index = 0; index < answers.questions.size(); ++index) {
    if (index > 0) {
      out << ",";
    }
    writeQuestion(out, answers.questions[index]);
  }
  out << "]";
}

void writeMinimalBarcode(std::ostream& out, const OmrBarcodeResult& barcode) {
  out << "{"
      << "\"found\":" << (barcode.found ? "true" : "false") << ","
      << "\"status\":";
  writeJsonString(out, barcode.status);
  out << ",\"text\":";
  writeJsonStringOrNull(out, barcode.text);
  out << ",\"format\":";
  writeJsonStringOrNull(out, barcode.format);
  out << ",\"confidence\":" << barcode.confidence
      << "}";
}

void writeMinimalStudentCode(std::ostream& out, const OmrStudentCodeReadResult& code) {
  out << "{"
      << "\"available\":" << (code.available ? "true" : "false") << ","
      << "\"complete\":" << (code.complete ? "true" : "false") << ","
      << "\"status\":";
  writeJsonString(out, code.status);
  out << ",\"value\":";
  writeJsonStringOrNull(out, code.value);
  out << "}";
}

void writeMinimalQuestion(std::ostream& out, const OmrAnswerQuestionResult& question) {
  out << "{"
      << "\"question\":" << question.questionNumber << ","
      << "\"status\":";
  writeJsonString(out, question.status);
  out << ",\"selectedCount\":" << question.selectedOptions.size()
      << ",\"selectedOption\":";
  writeJsonStringOrNull(out, question.hasSelectedOption ? question.selectedOption : "");
  out << ",\"selectedOptions\":";
  writeStringArray(out, question.selectedOptions);
  out << ",\"confidence\":" << question.confidence
      << "}";
}

void writeMinimalQuestions(std::ostream& out, const OmrAnswerReadResult& answers) {
  out << "[";
  for (size_t index = 0; index < answers.questions.size(); ++index) {
    if (index > 0) {
      out << ",";
    }
    writeMinimalQuestion(out, answers.questions[index]);
  }
  out << "]";
}

void writeSheet(
  std::ostream& out,
  const NormalizeResult& sheet,
  int pageNumber,
  const std::string& batchExamCode,
  bool examCodeOverrideProvided,
  bool includeQuestions
) {
  const std::string sheetExamCode = examCodeOverrideProvided
    ? batchExamCode
    : (hasUsableExamCode(sheet.barcode) ? sheet.barcode.text : batchExamCode);

  out << "{"
      << "\"page\":" << pageNumber << ","
      << "\"success\":" << (sheet.success ? "true" : "false") << ","
      << "\"status\":";
  writeJsonString(out, sheet.status);
  out << ",\"reviewRequired\":" << (sheet.reviewRequired ? "true" : "false");
  out << ",\"reviewReasons\":";
  writeStringArray(out, sheet.reviewReasons);
  out << ",\"error\":";
  writeJsonString(out, sheet.error);
  out << ",\"studentId\":";
  writeJsonStringOrNull(out, sheet.studentId);
  out << ",\"examCode\":";
  writeJsonStringOrNull(out, sheetExamCode);
  out << ",\"normalizedImagePath\":";
  writeJsonStringOrNull(out, sheet.normalizedImagePath);
  out << ",\"qrCode\":";
  if (sheet.examCodeUsesQr) {
    writeBarcode(out, sheet.barcode);
  } else {
    out << "null";
  }
  // Deprecated compatibility alias for existing native-module consumers.
  out << ",\"barcode\":";
  writeBarcode(out, sheet.barcode);
  out << ",\"studentCode\":";
  writeStudentCode(out, sheet.studentCode);
  out << ",\"answerSummary\":";
  writeAnswerSummary(out, sheet.answers.summary);
  out << ",\"selectedAnswers\":";
  writeSelectedAnswersObject(out, sheet.answers);
  out << ",\"questions\":";
  if (includeQuestions) {
    writeQuestions(out, sheet.answers);
  } else {
    out << "[]";
  }
  out << ",\"warnings\":";
  writeStringArray(out, sheet.warnings);
  out << ",\"processingTimeMs\":" << sheet.processingTimeMs
      << "}";
}

std::string makeBatchJson(
  const MobileOmrBatchRequest& request,
  const std::vector<MobileOmrPageInput>& pages,
  const std::vector<NormalizeResult>& sheets,
  const std::vector<std::string>& errors,
  const std::string& status
) {
  const std::string batchExamCode = firstAvailableExamCode(request, sheets);
  const int reviewSheetCount = static_cast<int>(std::count_if(
    sheets.begin(),
    sheets.end(),
    [](const NormalizeResult& sheet) { return sheet.reviewRequired; }
  ));
  const int successfulSheetCount = static_cast<int>(std::count_if(
    sheets.begin(),
    sheets.end(),
    [](const NormalizeResult& sheet) { return sheet.success; }
  ));
  const double processingTimeMs = std::accumulate(
    sheets.begin(),
    sheets.end(),
    0.0,
    [](double total, const NormalizeResult& sheet) { return total + sheet.processingTimeMs; }
  );
  std::ostringstream out;
  out << "{"
      << "\"schemaVersion\":2,"
      << "\"success\":" << (status == "OK" ? "true" : "false") << ","
      << "\"status\":";
  writeJsonString(out, status);
  out << ",\"examCode\":";
  writeJsonStringOrNull(out, batchExamCode);
  out << ",\"examCodeSource\":";
  writeJsonString(out, examCodeSource(request, sheets));
  out << ",\"sheetCount\":" << sheets.size() << ","
      << "\"successfulSheetCount\":" << successfulSheetCount << ","
      << "\"failedSheetCount\":" << (static_cast<int>(sheets.size()) - successfulSheetCount) << ","
      << "\"reviewRequired\":" << (reviewSheetCount > 0 ? "true" : "false") << ","
      << "\"reviewSheetCount\":" << reviewSheetCount << ","
      << "\"compact\":" << (request.includeQuestions ? "false" : "true") << ","
      << "\"processingTimeMs\":" << processingTimeMs << ","
      << "\"errors\":";
  writeStringArray(out, errors);
  out << ",\"sheets\":[";
  for (size_t index = 0; index < sheets.size(); ++index) {
    if (index > 0) {
      out << ",";
    }
    const int pageNumber = index < pages.size() ? pages[index].pageNumber : static_cast<int>(index + 1);
    writeSheet(
      out,
      sheets[index],
      pageNumber,
      batchExamCode,
      !request.examCode.empty(),
      request.includeQuestions
    );
  }
  out << "]}";
  return out.str();
}

std::string makeMinimalJson(const NormalizeResult& sheet) {
  std::ostringstream out;
  out << "{"
      << "\"schemaVersion\":1,"
      << "\"success\":" << (sheet.success ? "true" : "false") << ","
      << "\"status\":";
  writeJsonString(out, sheet.status);
  out << ",\"error\":";
  writeJsonStringOrNull(out, sheet.error);
  out << ",\"reviewRequired\":" << (sheet.reviewRequired ? "true" : "false")
      << ",\"reviewReasons\":";
  writeStringArray(out, sheet.reviewReasons);
  out << ",\"processingTimeMs\":" << sheet.processingTimeMs
      << ",\"qrCode\":";
  writeMinimalBarcode(out, sheet.barcode);
  out << ",\"studentCode\":";
  writeMinimalStudentCode(out, sheet.studentCode);
  out << ",\"questions\":";
  writeMinimalQuestions(out, sheet.answers);
  out << "}";
  return out.str();
}

MobileOmrPageInput parsePageObject(const std::string& pageJson, int fallbackPageNumber) {
  MobileOmrPageInput page;
  page.pageNumber = json::intForKey(pageJson, "pageNumber")
    .value_or(json::intForKey(pageJson, "page").value_or(fallbackPageNumber));
  page.imagePath = json::stringForKey(pageJson, "imagePath")
    .value_or(json::stringForKey(pageJson, "uri").value_or(""));

  const std::vector<double> flatAnchors = json::numberArrayForKey(pageJson, "anchorPointsPercent");
  for (size_t index = 0; index + 1 < flatAnchors.size(); index += 2) {
    page.anchorPointsPercent.push_back({
      static_cast<float>(flatAnchors[index]),
      static_cast<float>(flatAnchors[index + 1]),
    });
  }

  const std::vector<std::string> anchorObjects = json::objectArrayForKey(pageJson, "anchors");
  if (!anchorObjects.empty()) {
    page.anchorPointsPercent.clear();
    for (const auto& anchor : anchorObjects) {
      const auto x = json::numberForKey(anchor, "x");
      const auto y = json::numberForKey(anchor, "y");
      if (x.has_value() && y.has_value()) {
        page.anchorPointsPercent.push_back({static_cast<float>(*x), static_cast<float>(*y)});
      }
    }
  }

  return page;
}

std::string errorJson(const std::string& error, bool compact = false) {
  std::ostringstream out;
  out << "{\"schemaVersion\":2,"
      << "\"success\":false,"
      << "\"status\":\"ERROR\","
      << "\"examCode\":null,"
      << "\"examCodeSource\":\"none\","
      << "\"sheetCount\":0,"
      << "\"successfulSheetCount\":0,"
      << "\"failedSheetCount\":0,"
      << "\"reviewRequired\":false,"
      << "\"reviewSheetCount\":0,"
      << "\"compact\":" << (compact ? "true" : "false") << ","
      << "\"processingTimeMs\":0,"
      << "\"error\":";
  writeJsonString(out, error);
  out << ",\"errors\":[";
  writeJsonString(out, error);
  out << "],\"sheets\":[]}";
  return out.str();
}

std::string minimalErrorJson(const std::string& error) {
  std::ostringstream out;
  out << "{\"schemaVersion\":1,"
      << "\"success\":false,"
      << "\"status\":\"ERROR\","
      << "\"error\":";
  writeJsonString(out, error);
  out << ",\"reviewRequired\":true,"
      << "\"reviewReasons\":[";
  writeJsonString(out, error);
  out << "],\"processingTimeMs\":0,"
      << "\"qrCode\":null,"
      << "\"studentCode\":null,"
      << "\"questions\":[]}";
  return out.str();
}

} // namespace

MobileOmrBatchResult MobileOmrBatchProcessor::process(const MobileOmrBatchRequest& request) {
  MobileOmrBatchResult result;
  if (request.templateJson.empty()) {
    result.errors.push_back("template_json_missing");
    result.json = request.minimalResult
      ? minimalErrorJson("template_json_missing")
      : errorJson("template_json_missing", !request.includeQuestions);
    return result;
  }
  if (request.pages.empty()) {
    result.errors.push_back("pages_empty");
    result.json = request.minimalResult
      ? minimalErrorJson("pages_empty")
      : errorJson("pages_empty", !request.includeQuestions);
    return result;
  }
  if (request.minimalResult && request.pages.size() != 1) {
    result.errors.push_back("single_image_required");
    result.json = minimalErrorJson("single_image_required");
    return result;
  }

  OmrTemplate tpl;
  OmrLiveOptions options;
  try {
    tpl = OmrTemplate::fromJson(request.templateJson);
    options = OmrTemplate::optionsFromJson(request.optionsJson, tpl);
  } catch (const std::exception& error) {
    result.errors.push_back(error.what());
    result.json = request.minimalResult
      ? minimalErrorJson(error.what())
      : errorJson(error.what(), !request.includeQuestions);
    return result;
  }

  NormalizeEngine engine;
  engine.configure(tpl, options);

  for (const auto& page : request.pages) {
    if (page.imagePath.empty()) {
      NormalizeResult sheet;
      sheet.status = "ERROR";
      sheet.error = "page_image_path_missing";
      result.sheets.push_back(sheet);
      result.errors.push_back("page_" + std::to_string(page.pageNumber) + ":page_image_path_missing");
      continue;
    }

    NormalizeRequest normalizeRequest;
    normalizeRequest.imagePath = page.imagePath;
    normalizeRequest.outputDirectory = request.outputDirectory;
    normalizeRequest.returnDebugImages = request.returnDebugImages;
    normalizeRequest.persistNormalizedImage = request.returnNormalizedImages;
    normalizeRequest.maxInputDimension = request.maxInputDimension;
    normalizeRequest.qrOnly = request.qrOnly;
    // The mobile JSON contract does not expose brightness/sharpness metrics and
    // still-image OMR decisions never consume them. Avoid a full-frame
    // histogram, Laplacian, and glare pass for both minimal and batch scans.
    normalizeRequest.collectQualityMetrics = false;
    normalizeRequest.anchorPointsPercent = page.anchorPointsPercent;

    try {
      NormalizeResult sheet = engine.normalize(normalizeRequest);
      if (!sheet.success) {
        result.errors.push_back("page_" + std::to_string(page.pageNumber) + ":" + sheet.error);
      }
      result.sheets.push_back(std::move(sheet));
    } catch (const std::exception& error) {
      NormalizeResult sheet;
      sheet.status = "ERROR";
      sheet.error = error.what();
      result.sheets.push_back(sheet);
      result.errors.push_back("page_" + std::to_string(page.pageNumber) + ":" + error.what());
    }
  }

  const int successfulSheetCount = static_cast<int>(std::count_if(
    result.sheets.begin(),
    result.sheets.end(),
    [](const auto& sheet) { return sheet.success; }
  ));
  result.success = !result.sheets.empty() && successfulSheetCount == static_cast<int>(result.sheets.size());
  result.status = result.success ? "OK" : (successfulSheetCount > 0 ? "PARTIAL" : "ERROR");
  result.json = request.minimalResult
    ? makeMinimalJson(result.sheets.front())
    : makeBatchJson(request, request.pages, result.sheets, result.errors, result.status);
  return result;
}

MobileOmrBatchResult MobileOmrBatchProcessor::processJson(const std::string& requestJson) {
  MobileOmrBatchResult result;
  bool minimalResult = false;
  try {
    MobileOmrBatchRequest request;
    request.minimalResult =
      json::stringForKey(requestJson, "resultMode").value_or("") == "minimal" ||
      json::boolForKey(requestJson, "minimalResult").value_or(false);
    minimalResult = request.minimalResult;
    request.templateJson = json::stringForKey(requestJson, "templateJson").value_or("");
    if (request.templateJson.empty()) {
      if (const auto templatePath = json::stringForKey(requestJson, "templateJsonPath"); templatePath.has_value()) {
        request.templateJson = readTextFile(*templatePath);
      } else if (const auto templatePath = json::stringForKey(requestJson, "templatePath"); templatePath.has_value()) {
        request.templateJson = readTextFile(*templatePath);
      }
    }

    request.optionsJson = json::stringForKey(requestJson, "optionsJson").value_or("");
    if (request.optionsJson.empty()) {
      if (const auto optionsPath = json::stringForKey(requestJson, "optionsJsonPath"); optionsPath.has_value()) {
        request.optionsJson = readTextFile(*optionsPath);
      } else if (const auto optionsPath = json::stringForKey(requestJson, "optionsPath"); optionsPath.has_value()) {
        request.optionsJson = readTextFile(*optionsPath);
      }
    }

    request.outputDirectory = json::stringForKey(requestJson, "outputDirectory").value_or("");
    request.examCode = json::stringForKey(requestJson, "examCode").value_or("");
    request.returnDebugImages = json::boolForKey(requestJson, "returnDebugImages").value_or(false);
    request.returnNormalizedImages = json::boolForKey(requestJson, "returnNormalizedImages").value_or(false);
    request.qrOnly = json::boolForKey(requestJson, "qrOnly").value_or(false);
    request.maxInputDimension = std::clamp(
      json::intForKey(requestJson, "maxInputDimension").value_or(2600),
      1800,
      4096
    );
    const bool compactResult = json::boolForKey(requestJson, "compactResult").value_or(false);
    request.includeQuestions = json::boolForKey(requestJson, "includeQuestions").value_or(!compactResult);

    // A nested pages[0].imagePath is not a single-image request. Searching
    // recursively here silently discarded every remaining batch page.
    const std::string imagePath = json::topLevelStringForKey(requestJson, "imagePath")
      .value_or(json::topLevelStringForKey(requestJson, "imageUri").value_or(""));
    if (!imagePath.empty()) {
      MobileOmrPageInput page;
      page.imagePath = imagePath;
      request.pages.push_back(std::move(page));
    } else {
      const std::vector<std::string> pages = json::objectArrayForKey(requestJson, "pages");
      request.pages.reserve(pages.size());
      for (size_t index = 0; index < pages.size(); ++index) {
        request.pages.push_back(parsePageObject(pages[index], static_cast<int>(index + 1)));
      }
    }

    return process(request);
  } catch (const std::exception& error) {
    result.errors.push_back(error.what());
    result.json = minimalResult ? minimalErrorJson(error.what()) : errorJson(error.what());
    return result;
  }
}

} // namespace omr
