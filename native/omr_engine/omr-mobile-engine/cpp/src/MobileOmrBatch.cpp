#include "omr/MobileOmrBatch.hpp"

#include <algorithm>
#include <fstream>
#include <future>
#include <memory>
#include <numeric>
#include <sstream>
#include <stdexcept>
#include <utility>
#include "EngineTaskQueues.hpp"
#include "EngineProgress.hpp"
#include "omr/NormalizeEngine.hpp"
#include "omr/HeaderEngine.hpp"
#include "omr/QuestionEngines.hpp"
#include "omr/OmrTemplate.hpp"
#include "omr/QrSheetType.hpp"
#include "utils/Json.hpp"
#include "utils/Timer.hpp"

namespace omr {

namespace {

detail::ProgressRegistry progressRegistry;

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

void writePipeline(std::ostream& out, const NormalizeResult& sheet) {
  out << ",\"pipeline\":{\"headerEngine\":";
  writeJsonStringOrNull(out, sheet.headerEngineRuns ? "header-v1" : "");
  out << ",\"answerEngine\":";
  writeJsonStringOrNull(out, sheet.answerEngine);
  out << ",\"headerEngineRuns\":" << sheet.headerEngineRuns
      << ",\"answerEngineRuns\":" << sheet.answerEngineRuns
      << ",\"answerQrDecodeCalls\":" << sheet.answerQrDecodeCalls
      << ",\"headerProcessingTimeMs\":" << sheet.headerProcessingTimeMs
      << ",\"answerProcessingTimeMs\":" << sheet.answerProcessingTimeMs
      << ",\"headerStartedMs\":" << sheet.headerStartedMs
      << ",\"headerFinishedMs\":" << sheet.headerFinishedMs
      << ",\"answerQueuedMs\":" << sheet.answerQueuedMs
      << ",\"answerStartedMs\":" << sheet.answerStartedMs
      << ",\"answerFinishedMs\":" << sheet.answerFinishedMs
      << ",\"headerQueueWaitMs\":" << sheet.headerQueueWaitMs
      << ",\"answerQueueWaitMs\":" << sheet.answerQueueWaitMs
      << ",\"headerWorkerThread\":";
  writeJsonStringOrNull(out, sheet.headerWorkerThread);
  out << ",\"answerWorkerThread\":";
  writeJsonStringOrNull(out, sheet.answerWorkerThread);
  out
      << ",\"headerRegion\":{\"x\":" << sheet.headerScannedRegion.x
      << ",\"y\":" << sheet.headerScannedRegion.y
      << ",\"width\":" << sheet.headerScannedRegion.width
      << ",\"height\":" << sheet.headerScannedRegion.height << "}}";
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

void writeSheetType(std::ostream& out, const OmrQrSheetType& sheetType) {
  out << "{"
      << "\"detected\":" << (sheetType.detected ? "true" : "false") << ","
      << "\"code\":";
  writeJsonStringOrNull(out, sheetType.code);
  out << ",\"templateId\":";
  writeJsonStringOrNull(out, sheetType.templateId);
  out << ",\"questionCount\":";
  if (sheetType.questionCount > 0) {
    out << sheetType.questionCount;
  } else {
    out << "null";
  }
  out << ",\"status\":";
  writeJsonString(out, sheetType.status);
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
  out << ",\"sheetType\":";
  writeSheetType(out, sheet.sheetType);
  writePipeline(out, sheet);
  out << ",\"activeQuestionCount\":" << sheet.activeQuestionCount;
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
  const std::string& status,
  double wallTimeMs
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
      << "\"processingTimeMs\":" << wallTimeMs << ","
      << "\"sheetProcessingTimeMs\":" << processingTimeMs << ","
      << "\"execution\":{\"mode\":\"header-and-three-answer-queues\","
         "\"headerWorkers\":1,\"answerWorkers\":3},"
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
  out << ",\"sheetType\":";
  writeSheetType(out, sheet.sheetType);
  writePipeline(out, sheet);
  out << ",\"activeQuestionCount\":" << sheet.activeQuestionCount;
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
      << "\"sheetType\":{\"detected\":false,\"code\":null,\"templateId\":null,\"questionCount\":null,\"status\":\"QR_MISSING\"},"
      << "\"activeQuestionCount\":0,"
      << "\"studentCode\":null,"
      << "\"questions\":[]}";
  return out.str();
}

} // namespace

namespace {

struct ConfiguredTemplate {
  OmrTemplate tpl;
  OmrLiveOptions options;
};

std::string workerThreadId() {
  std::ostringstream out;
  out << std::this_thread::get_id();
  return out.str();
}

struct PageTask : std::enable_shared_from_this<PageTask> {
  MobileOmrPageInput page;
  std::shared_ptr<const MobileOmrBatchRequest> request;
  std::shared_ptr<const std::vector<ConfiguredTemplate>> templates;
  Timer batchTimer;
  Timer pageTimer;
  HeaderScanResult header;
  NormalizeResult trace;
  std::promise<NormalizeResult> completion;
  double submittedMs = 0;
  std::shared_ptr<detail::BatchProgress> progress;
  size_t progressIndex = 0;

  void finish(NormalizeResult sheet) {
    sheet.headerEngineRuns = trace.headerEngineRuns;
    sheet.headerProcessingTimeMs = header.processingTimeMs;
    sheet.headerScannedRegion = header.scannedRegion;
    sheet.headerStartedMs = trace.headerStartedMs;
    sheet.headerFinishedMs = trace.headerFinishedMs;
    sheet.headerQueueWaitMs = trace.headerQueueWaitMs;
    sheet.headerWorkerThread = trace.headerWorkerThread;
    sheet.answerEngine = trace.answerEngine;
    sheet.answerEngineRuns = trace.answerEngineRuns;
    sheet.answerQueuedMs = trace.answerQueuedMs;
    sheet.answerStartedMs = trace.answerStartedMs;
    sheet.answerFinishedMs = trace.answerFinishedMs;
    sheet.answerQueueWaitMs = trace.answerQueueWaitMs;
    sheet.answerWorkerThread = trace.answerWorkerThread;
    sheet.answerProcessingTimeMs = trace.answerEngineRuns
      ? trace.answerFinishedMs - trace.answerStartedMs : 0;
    sheet.processingTimeMs = pageTimer.elapsedMs();
    if (progress) progress->update(progressIndex, sheet.success ? "done" : "failed");
    completion.set_value(std::move(sheet));
  }

  void fail(const std::string& error) {
    if (trace.answerEngineRuns) trace.answerFinishedMs = batchTimer.elapsedMs();
    NormalizeResult sheet;
    sheet.status = "ERROR";
    sheet.error = error;
    finish(std::move(sheet));
  }

  void readAnswers(size_t selected) {
    try {
      const auto& config = templates->at(selected);
      if (progress) progress->update(progressIndex, "processing", config.tpl.sheetTypeCode);
      trace.answerStartedMs = batchTimer.elapsedMs();
      trace.answerQueueWaitMs = trace.answerStartedMs - trace.answerQueuedMs;
      trace.answerWorkerThread = workerThreadId();
      trace.answerEngineRuns = 1;
      NormalizeRequest input;
      input.imagePath = page.imagePath;
      input.outputDirectory = request->outputDirectory;
      input.returnDebugImages = request->returnDebugImages;
      input.persistNormalizedImage = request->returnNormalizedImages;
      input.maxInputDimension = request->maxInputDimension;
      input.collectQualityMetrics = false;
      input.anchorPointsPercent = page.anchorPointsPercent;
      NormalizeResult sheet;
      if (!request->knownSheetType.empty()) {
        input.knownSheetType = true;
        input.identityQrDecodeCalls = std::make_shared<int>(0);
        input.qrOnly = request->qrOnly;
        auto fixedTemplate = config.tpl;
        fixedTemplate.studentCode.enabled = false;
        for (auto& variant : fixedTemplate.geometryVariants) variant.studentCode.enabled = false;
        NormalizeEngine engine;
        engine.configure(fixedTemplate, config.options);
        sheet = engine.normalize(input);
        sheet.answerQrDecodeCalls = *input.identityQrDecodeCalls;
      } else if (header.sheetType.code == "T1") {
        sheet = T1AnswerEngine{}.scan(input, header, config.tpl, config.options);
      } else if (header.sheetType.code == "T2") {
        sheet = T2AnswerEngine{}.scan(input, header, config.tpl, config.options);
      } else {
        sheet = T3AnswerEngine{}.scan(input, header, config.tpl, config.options);
      }
      trace.answerFinishedMs = batchTimer.elapsedMs();
      finish(std::move(sheet));
    } catch (const std::exception& error) {
      fail(error.what());
    } catch (...) {
      fail("answer_engine_unknown_error");
    }
  }

  void readHeader() {
    try {
      if (progress) progress->update(progressIndex, "header");
      if (page.imagePath.empty()) {
        fail("page_image_path_missing");
        return;
      }
      trace.headerStartedMs = batchTimer.elapsedMs();
      trace.headerQueueWaitMs = trace.headerStartedMs - submittedMs;
      trace.headerWorkerThread = workerThreadId();
      trace.headerEngineRuns = 1;
      header = HeaderEngine{}.scan(page.imagePath, request->maxInputDimension);
      trace.headerFinishedMs = batchTimer.elapsedMs();
      const auto selected = std::find_if(templates->begin(), templates->end(),
        [this](const ConfiguredTemplate& config) {
          return config.tpl.sheetTypeCode == header.sheetType.code &&
            config.tpl.templateId == header.sheetType.templateId;
        });
      NormalizeResult sheet;
      if (!header.valid() || selected == templates->end()) {
        sheet.barcode = header.qr;
        sheet.sheetType = header.sheetType;
        sheet.examCodeUsesQr = true;
        sheet.reviewRequired = true;
        const bool unsupported = header.sheetType.status == "UNSUPPORTED_PREFIX" || header.valid();
        sheet.status = header.error == "image_load_failed" ? "ERROR" :
          unsupported ? "UNSUPPORTED_SHEET_TYPE" : "SHEET_TYPE_REQUIRED";
        sheet.error = header.valid() ? "sheet_template_missing:" + header.sheetType.code : header.error;
        sheet.reviewReasons.push_back(header.error == "image_load_failed" ? "image_load_failed" :
          header.valid() ? "sheet_template_missing" :
          unsupported ? "sheet_type_qr_unsupported_prefix" : "sheet_type_qr_missing_or_invalid");
        finish(std::move(sheet));
      } else if (request->qrOnly) {
        sheet.success = true;
        sheet.status = "NORMALIZED";
        sheet.barcode = header.qr;
        sheet.sheetType = header.sheetType;
        sheet.activeQuestionCount = header.sheetType.questionCount;
        sheet.examCodeUsesQr = true;
        finish(std::move(sheet));
      } else {
        // Publish the immutable header to one family FIFO, then scan the next
        // header immediately while that family's worker reads the answers.
        trace.answerEngine = header.sheetType.code;
        trace.answerQueuedMs = batchTimer.elapsedMs();
        if (progress) progress->update(progressIndex, "queued", header.sheetType.code);
        const size_t family = header.sheetType.code == "T1" ? 0 :
          header.sheetType.code == "T2" ? 1 : 2;
        EngineTaskQueues::instance().answers(family,
          [self = shared_from_this(), index = size_t(selected - templates->begin())] {
            self->readAnswers(index);
          });
      }
    } catch (const std::exception& error) {
      trace.headerFinishedMs = batchTimer.elapsedMs();
      fail(error.what());
    } catch (...) {
      trace.headerFinishedMs = batchTimer.elapsedMs();
      fail("header_engine_unknown_error");
    }
  }
};

} // namespace

std::string MobileOmrBatchProcessor::progressJson(const std::string& id) {
  return progressRegistry.json(id);
}

void MobileOmrBatchProcessor::releaseProgress(const std::string& id) {
  progressRegistry.release(id);
}

MobileOmrBatchResult MobileOmrBatchProcessor::process(const MobileOmrBatchRequest& request) {
  Timer batchTimer;
  MobileOmrBatchResult result;
  if (request.templateJson.empty() && request.templateJsons.empty()) {
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

  std::vector<ConfiguredTemplate> configuredTemplates;
  try {
    if (!request.knownSheetType.empty() && !QrSheetTypeResolver::fromCode(request.knownSheetType).detected)
      throw std::invalid_argument("invalid_known_sheet_type");
    std::vector<std::string> candidateJsons;
    if (!request.templateJson.empty()) {
      candidateJsons.push_back(request.templateJson);
    }
    candidateJsons.insert(
      candidateJsons.end(),
      request.templateJsons.begin(),
      request.templateJsons.end()
    );
    configuredTemplates.reserve(candidateJsons.size());
    for (const auto& candidateJson : candidateJsons) {
      if (candidateJson.empty()) {
        continue;
      }
      OmrTemplate tpl = OmrTemplate::fromJson(candidateJson);
      const OmrQrSheetType binding = QrSheetTypeResolver::fromCode(tpl.sheetTypeCode);
      if (
        !binding.detected ||
        binding.templateId != tpl.templateId ||
        binding.questionCount != tpl.sheetTypeQuestionCount
      ) {
        throw std::invalid_argument(
          "invalid_template: unsupported sheet binding for " + tpl.templateId
        );
      }
      const bool duplicate = std::any_of(
        configuredTemplates.begin(),
        configuredTemplates.end(),
        [&tpl](const ConfiguredTemplate& candidate) {
          return candidate.tpl.templateId == tpl.templateId;
        }
      );
      if (duplicate) {
        continue;
      }
      configuredTemplates.push_back({tpl, OmrTemplate::optionsFromJson(request.optionsJson, tpl)});
    }
    if (configuredTemplates.empty()) {
      throw std::runtime_error("template_json_missing");
    }
    if (!request.knownSheetType.empty() && std::none_of(configuredTemplates.begin(), configuredTemplates.end(),
        [&](const ConfiguredTemplate& config) { return config.tpl.sheetTypeCode == request.knownSheetType; }))
      throw std::invalid_argument("sheet_template_missing:" + request.knownSheetType);
  } catch (const std::exception& error) {
    result.errors.push_back(error.what());
    result.json = request.minimalResult
      ? minimalErrorJson(error.what())
      : errorJson(error.what(), !request.includeQuestions);
    return result;
  }

  const auto sharedRequest = std::make_shared<const MobileOmrBatchRequest>(request);
  const auto sharedTemplates =
    std::make_shared<const std::vector<ConfiguredTemplate>>(std::move(configuredTemplates));
  std::vector<std::future<NormalizeResult>> pending;
  pending.reserve(request.pages.size());
  std::vector<int> pageNumbers;
  for (const auto& page : request.pages) pageNumbers.push_back(page.pageNumber);
  const auto progress = progressRegistry.create(request.progressId, pageNumbers);
  for (const auto& page : request.pages) {
    auto task = std::make_shared<PageTask>();
    task->page = page;
    task->request = sharedRequest;
    task->templates = sharedTemplates;
    task->batchTimer = batchTimer;
    task->submittedMs = batchTimer.elapsedMs();
    task->progress = progress;
    task->progressIndex = pending.size();
    pending.push_back(task->completion.get_future());
    if (request.knownSheetType.empty()) {
      EngineTaskQueues::instance().header([task] { task->readHeader(); });
    } else {
      const auto selected = std::find_if(sharedTemplates->begin(), sharedTemplates->end(),
        [&](const ConfiguredTemplate& config) { return config.tpl.sheetTypeCode == request.knownSheetType; });
      task->trace.answerEngine = request.knownSheetType;
      task->trace.answerQueuedMs = batchTimer.elapsedMs();
      if (progress) progress->update(task->progressIndex, "queued", request.knownSheetType);
      const size_t family = request.knownSheetType == "T1" ? 0 : request.knownSheetType == "T2" ? 1 : 2;
      EngineTaskQueues::instance().answers(family, [task, index = size_t(selected - sharedTemplates->begin())] {
        task->readAnswers(index);
      });
    }
  }
  // Wait in input order without blocking any worker. Failed pages keep their
  // original slot even when other families finish in a different order.
  for (size_t index = 0; index < pending.size(); ++index) {
    auto sheet = pending[index].get();
    if (!sheet.success) {
      result.errors.push_back("page_" + std::to_string(request.pages[index].pageNumber) + ":" + sheet.error);
    }
    result.sheets.push_back(std::move(sheet));
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
    : makeBatchJson(request, request.pages, result.sheets, result.errors, result.status, batchTimer.elapsedMs());
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
      if (const auto templateJsonPath = json::stringForKey(requestJson, "templateJsonPath"); templateJsonPath.has_value()) {
        request.templateJson = readTextFile(*templateJsonPath);
      } else if (const auto compatibilityTemplatePath = json::stringForKey(requestJson, "templatePath"); compatibilityTemplatePath.has_value()) {
        request.templateJson = readTextFile(*compatibilityTemplatePath);
      }
    }

    for (const auto& templatePath : json::stringArrayForKey(requestJson, "templateJsonPaths")) {
      request.templateJsons.push_back(readTextFile(templatePath));
    }

    request.optionsJson = json::stringForKey(requestJson, "optionsJson").value_or("");
    if (request.optionsJson.empty()) {
      if (const auto optionsJsonPath = json::stringForKey(requestJson, "optionsJsonPath"); optionsJsonPath.has_value()) {
        request.optionsJson = readTextFile(*optionsJsonPath);
      } else if (const auto compatibilityOptionsPath = json::stringForKey(requestJson, "optionsPath"); compatibilityOptionsPath.has_value()) {
        request.optionsJson = readTextFile(*compatibilityOptionsPath);
      }
    }

    request.outputDirectory = json::stringForKey(requestJson, "outputDirectory").value_or("");
    request.examCode = json::stringForKey(requestJson, "examCode").value_or("");
    request.progressId = json::topLevelStringForKey(requestJson, "progressId").value_or("");
    request.knownSheetType = json::topLevelStringForKey(requestJson, "knownSheetType").value_or("");
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

    // Nested pages[].imagePath must never select root single-image mode.
    // Preserve all pages, their numbers, and page-level failures in mixed batches.
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
