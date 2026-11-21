#include "omr/NormalizeEngine.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <exception>
#include <fstream>
#include <iostream>
#include <map>
#include <sstream>
#include <string>
#include <utility>
#include <vector>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/objdetect.hpp>

namespace {

constexpr int kTemplateWidth = 1656;
constexpr int kTemplateHeight = 2339;
const cv::Scalar kPaperBgr{245, 245, 245};
const cv::Scalar kPrintedRingBgr{75, 3, 182};
const cv::Scalar kMarkedBgr{24, 24, 24};
const std::string kQrPayload = "12345678";
#if defined(__ANDROID__)
const std::string kOutputDirectory = "/data/local/tmp/omr-bench";
#else
const std::string kOutputDirectory = "/tmp";
#endif
const std::string kInputPath = kOutputDirectory + "/omr_normalize_accuracy_regression_input.jpg";
const std::string kTwentyAnswerInputPath = kOutputDirectory + "/omr_twenty_answers_input.jpg";
const std::string kProductionTwentyAnswerInputPath =
  kOutputDirectory + "/omr_production_twenty_answers_input.jpg";

struct ExpectedAnswer {
  int question = 0;
  std::string option;
};

bool require(bool condition, const std::string& message) {
  if (!condition) {
    std::cerr << "FAIL: " << message << '\n';
  }
  return condition;
}

std::vector<float> registrationRows() {
  return {
    100.8f, 139.7f, 178.7f, 217.5f, 256.5f, 295.3f, 334.3f,
    487.0f, 525.9f, 564.8f, 603.7f, 642.6f, 681.5f, 720.3f, 759.3f, 798.1f, 839.8f,
    937.0f, 978.7f, 1020.3f, 1062.0f, 1103.7f, 1145.3f, 1187.0f, 1228.7f, 1270.3f,
    1312.0f, 1353.7f, 1395.3f, 1437.0f, 1478.7f, 1520.3f, 1562.0f, 1603.7f, 1645.3f,
    1687.0f, 1728.7f, 1770.3f, 1812.0f, 1853.7f, 1895.3f, 1937.0f, 1978.7f, 2020.3f,
    2062.0f, 2103.7f, 2145.3f,
  };
}

omr::OmrAnswerBlockTemplate answerBlock(
  const std::string& id,
  int questionStart,
  std::map<std::string, float> xByOption,
  const std::vector<float>& rowY,
  const omr::Rect& bbox
) {
  omr::OmrAnswerBlockTemplate block;
  block.id = id;
  block.questionStart = questionStart;
  block.questionCount = 30;
  block.xByOption = std::move(xByOption);
  block.yByRow = rowY;
  block.bubbleRadius = 15.0f;
  block.bbox = bbox;
  block.hasBbox = true;
  return block;
}

omr::OmrTemplate accuracyTemplate() {
  omr::OmrTemplate tpl;
  tpl.version = 2;
  tpl.templateId = "normalize_accuracy_qr_5_column";
  tpl.width = static_cast<float>(kTemplateWidth);
  tpl.height = static_cast<float>(kTemplateHeight);
  tpl.dpi = 200.0f;

  tpl.registration.leftRailX = 67.6f;
  tpl.registration.rightRailX = 1584.3f;
  tpl.registration.dotRadius = 15.1f;
  tpl.registration.expectedDotsPerSide = 47;
  tpl.registration.minDotsPerSideForLive = 16;
  tpl.registration.minDotsPerSideForNormalize = 28;
  tpl.registration.rowY = registrationRows();

  tpl.examCodeBox = {167.0f, 61.0f, 492.0f, 303.0f};
  tpl.barcode.enabled = true;
  tpl.barcode.required = true;
  tpl.barcode.qrCodeOnly = true;
  tpl.barcode.placement = "inside_exam_code_box";
  tpl.barcode.preferredFormats = {"QR_CODE"};
  tpl.barcode.roi = {194.5f, 88.9f, 247.2f, 247.2f};
  tpl.barcode.fallbackRois.clear();
  tpl.barcode.expectedPayloadRegex = "^[0-9]{8}$";
  tpl.barcode.quietZoneRequired = true;
  tpl.barcode.minModulePxAfterWarp = 3.0f;
  tpl.barcode.decodeAfterPerspectiveCorrection = true;

  tpl.studentCode.enabled = true;
  tpl.studentCode.columns = 5;
  tpl.studentCode.columnReadOrder = "ltr";
  tpl.studentCode.digitRowsTopToBottom = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"};
  tpl.studentCode.xByColumnLeftToRight = {720.5f, 773.1f, 826.0f, 878.5f, 931.5f};
  tpl.studentCode.yByDigitTopToBottom = {
    487.0f, 525.9f, 564.9f, 603.5f, 642.5f,
    681.5f, 720.5f, 759.5f, 798.1f, 837.0f,
  };
  tpl.studentCode.bubbleRadius = 15.0f;
  tpl.studentCode.thresholds.markedFillRatio = 0.60f;
  tpl.studentCode.thresholds.blankMaxFillRatio = 0.42f;
  tpl.studentCode.thresholds.multiMarkMinRatio = 0.60f;
  tpl.studentCode.thresholds.ambiguousDelta = 0.18f;

  tpl.answers.enabled = true;
  tpl.answers.questionCount = 120;
  tpl.answers.options = {"1", "2", "3", "4"};
  tpl.answers.thresholds.markedFillRatio = 0.38f;
  tpl.answers.thresholds.blankMaxFillRatio = 0.35f;
  tpl.answers.thresholds.multiMarkMinRatio = 0.40f;
  tpl.answers.thresholds.ambiguousDelta = 0.14f;
  tpl.answersRegion = {309.0f, 911.0f, 1038.0f, 1269.0f};

  const std::vector<float> answerRows(
    tpl.registration.rowY.end() - 30,
    tpl.registration.rowY.end()
  );
  tpl.answers.blocks = {
    answerBlock(
      "q091_120",
      91,
      {{"1", 467.5f}, {"2", 426.1f}, {"3", 384.1f}, {"4", 342.5f}},
      answerRows,
      {309.0f, 911.0f, 238.0f, 1269.0f}
    ),
    answerBlock(
      "q061_090",
      61,
      {{"1", 734.1f}, {"2", 692.5f}, {"3", 651.1f}, {"4", 609.1f}},
      answerRows,
      {575.0f, 911.0f, 239.0f, 1269.0f}
    ),
    answerBlock(
      "q031_060",
      31,
      {{"1", 1001.1f}, {"2", 959.1f}, {"3", 917.5f}, {"4", 876.1f}},
      answerRows,
      {842.0f, 911.0f, 238.0f, 1269.0f}
    ),
    answerBlock(
      "q001_030",
      1,
      {{"1", 1267.5f}, {"2", 1226.1f}, {"3", 1184.1f}, {"4", 1142.5f}},
      answerRows,
      {1109.0f, 911.0f, 238.0f, 1269.0f}
    ),
  };
  return tpl;
}

void drawPrintedRing(cv::Mat& page, float x, float y, float radius) {
  cv::circle(
    page,
    cv::Point(cvRound(x), cvRound(y)),
    cvRound(radius),
    kPrintedRingBgr,
    2,
    cv::LINE_AA
  );
}

void drawMarkedBubble(cv::Mat& page, float x, float y, int radius = 11) {
  cv::circle(
    page,
    cv::Point(cvRound(x), cvRound(y)),
    radius,
    kMarkedBgr,
    cv::FILLED,
    cv::LINE_AA
  );
}

cv::Rect roundedRect(const omr::Rect& rect) {
  const int left = cvRound(rect.x);
  const int top = cvRound(rect.y);
  const int right = cvRound(rect.x + rect.width);
  const int bottom = cvRound(rect.y + rect.height);
  return {left, top, right - left, bottom - top};
}

void drawQr(cv::Mat& page, const omr::OmrTemplate& tpl) {
  const cv::Rect roi = roundedRect(tpl.barcode.roi);
  cv::QRCodeEncoder::Params parameters;
  parameters.correction_level = cv::QRCodeEncoder::CORRECT_LEVEL_Q;
  parameters.mode = cv::QRCodeEncoder::MODE_NUMERIC;
  cv::Mat modules;
  cv::QRCodeEncoder::create(parameters)->encode(kQrPayload, modules);

  // OpenCV returns the module matrix without a quiet zone. Add the four
  // modules required by the production template, then scale with nearest
  // neighbour interpolation so the synthetic code remains pixel-sharp.
  cv::Mat withQuietZone;
  cv::copyMakeBorder(
    modules,
    withQuietZone,
    4,
    4,
    4,
    4,
    cv::BORDER_CONSTANT,
    cv::Scalar(255)
  );
  cv::Mat scaled;
  cv::resize(withQuietZone, scaled, roi.size(), 0.0, 0.0, cv::INTER_NEAREST);
  cv::Mat qr;
  cv::cvtColor(scaled, qr, cv::COLOR_GRAY2BGR);
  qr.copyTo(page(roi));
}

const omr::OmrAnswerBlockTemplate* blockForQuestion(const omr::OmrTemplate& tpl, int question) {
  for (const auto& block : tpl.answers.blocks) {
    if (question >= block.questionStart && question < block.questionStart + block.questionCount) {
      return &block;
    }
  }
  return nullptr;
}

cv::Mat canonicalPage(
  const omr::OmrTemplate& tpl,
  const std::vector<ExpectedAnswer>& expectedAnswers,
  int& answerRingCount,
  int& studentRingCount,
  bool weakFirstStudentMark = false
) {
  cv::Mat page(cvRound(tpl.height), cvRound(tpl.width), CV_8UC3, kPaperBgr);

  for (const float rowY : tpl.registration.rowY) {
    cv::circle(
      page,
      cv::Point(cvRound(tpl.registration.leftRailX), cvRound(rowY)),
      cvRound(tpl.registration.dotRadius),
      cv::Scalar(0, 0, 0),
      cv::FILLED,
      cv::LINE_AA
    );
    cv::circle(
      page,
      cv::Point(cvRound(tpl.registration.rightRailX), cvRound(rowY)),
      cvRound(tpl.registration.dotRadius),
      cv::Scalar(0, 0, 0),
      cv::FILLED,
      cv::LINE_AA
    );
  }

  answerRingCount = 0;
  for (const auto& block : tpl.answers.blocks) {
    for (const float rowY : block.yByRow) {
      for (const auto& option : tpl.answers.options) {
        const auto x = block.xByOption.find(option);
        if (x == block.xByOption.end()) {
          continue;
        }
        drawPrintedRing(page, x->second, rowY, block.bubbleRadius);
        answerRingCount += 1;
      }
    }
  }

  studentRingCount = 0;
  const auto& digitLabels = tpl.studentCode.digitLabelsByChoiceOrder();
  for (int position = 0; position < tpl.studentCode.columns; ++position) {
    for (size_t digitIndex = 0; digitIndex < digitLabels.size(); ++digitIndex) {
      const omr::Point center = tpl.studentCode.bubbleCenter(
        static_cast<size_t>(position),
        digitIndex
      );
      drawPrintedRing(page, center.x, center.y, tpl.studentCode.bubbleRadius);
      studentRingCount += 1;
    }
  }

  const std::string studentCode = std::string("0123456789").substr(
    0,
    static_cast<size_t>(std::min(10, tpl.studentCode.columns))
  );
  for (size_t position = 0; position < studentCode.size(); ++position) {
    const std::string digit(1, studentCode[position]);
    const auto digitIt = std::find(digitLabels.begin(), digitLabels.end(), digit);
    if (digitIt == digitLabels.end()) {
      continue;
    }
    const size_t digitIndex = static_cast<size_t>(
      std::distance(digitLabels.begin(), digitIt)
    );
    const omr::Point bubble = tpl.studentCode.bubbleCenter(position, digitIndex);
    const cv::Point center(
      cvRound(bubble.x),
      cvRound(bubble.y)
    );
    if (weakFirstStudentMark && position == 0) {
      cv::ellipse(page, center, cv::Size(7, 6), 0.0, 0.0, 360.0, kMarkedBgr, cv::FILLED, cv::LINE_AA);
    } else {
      drawMarkedBubble(page, center.x, center.y);
    }
  }

  for (const auto& expected : expectedAnswers) {
    const omr::OmrAnswerBlockTemplate* block = blockForQuestion(tpl, expected.question);
    if (block == nullptr) {
      continue;
    }
    const int row = expected.question - block->questionStart;
    const auto x = block->xByOption.find(expected.option);
    if (x == block->xByOption.end() || row < 0 || row >= static_cast<int>(block->yByRow.size())) {
      continue;
    }
    drawMarkedBubble(page, x->second, block->yByRow[static_cast<size_t>(row)]);
  }

  drawQr(page, tpl);
  return page;
}

cv::Mat distortedCapture(const cv::Mat& canonical) {
  const cv::Size captureSize(1800, 2490);
  const std::array<cv::Point2f, 4> source = {
    cv::Point2f(0.0f, 0.0f),
    cv::Point2f(static_cast<float>(canonical.cols - 1), 0.0f),
    cv::Point2f(static_cast<float>(canonical.cols - 1), static_cast<float>(canonical.rows - 1)),
    cv::Point2f(0.0f, static_cast<float>(canonical.rows - 1)),
  };
  const std::array<cv::Point2f, 4> destination = {
    cv::Point2f(82.0f, 72.0f),
    cv::Point2f(1712.0f, 105.0f),
    cv::Point2f(1732.0f, 2418.0f),
    cv::Point2f(64.0f, 2387.0f),
  };
  const cv::Mat perspective = cv::getPerspectiveTransform(source.data(), destination.data());

  cv::Mat warped;
  cv::warpPerspective(
    canonical,
    warped,
    perspective,
    captureSize,
    cv::INTER_LINEAR,
    cv::BORDER_CONSTANT,
    kPaperBgr
  );

  for (int y = 0; y < warped.rows; ++y) {
    cv::Vec3b* row = warped.ptr<cv::Vec3b>(y);
    const float vertical = static_cast<float>(y) / static_cast<float>(std::max(1, warped.rows - 1));
    for (int x = 0; x < warped.cols; ++x) {
      const float horizontal = static_cast<float>(x) / static_cast<float>(std::max(1, warped.cols - 1));
      const float illumination = 0.90f + horizontal * 0.08f + vertical * 0.03f;
      for (int channel = 0; channel < 3; ++channel) {
        row[x][channel] = cv::saturate_cast<std::uint8_t>(
          static_cast<float>(row[x][channel]) * illumination
        );
      }
    }
  }

  cv::Mat blurred;
  cv::GaussianBlur(warped, blurred, cv::Size(3, 3), 0.65, 0.65, cv::BORDER_REPLICATE);
  return blurred;
}

const omr::OmrAnswerQuestionResult* resultForQuestion(
  const omr::OmrAnswerReadResult& answers,
  int question
) {
  for (const auto& result : answers.questions) {
    if (result.questionNumber == question) {
      return &result;
    }
  }
  return nullptr;
}

bool runAccuracyRegression() {
  const omr::OmrTemplate tpl = accuracyTemplate();
  const std::vector<ExpectedAnswer> expectedAnswers = {
    {91, "1"},
    {68, "2"},
    {45, "3"},
    {22, "4"},
  };

  int answerRingCount = 0;
  int studentRingCount = 0;
  const cv::Mat canonical = canonicalPage(tpl, expectedAnswers, answerRingCount, studentRingCount);
  const cv::Mat capture = distortedCapture(canonical);

  bool ok = true;
  ok &= require(tpl.registration.rowY.size() == 47, "template must contain 47 timing rows per rail");
  ok &= require(answerRingCount == 480, "canonical page must contain 480 answer rings");
  ok &= require(studentRingCount == 50, "canonical page must contain 50 student-code rings");
  ok &= require(
    cv::imwrite(kInputPath, capture, {cv::IMWRITE_JPEG_QUALITY, 86}),
    "distorted JPEG input must be written to the test output directory"
  );
  if (!ok) {
    return false;
  }

  omr::OmrLiveOptions options;
  options.minDotsPerSideForNormalize = tpl.registration.minDotsPerSideForNormalize;
  omr::NormalizeEngine engine;
  engine.configure(tpl, options);

  omr::NormalizeRequest request;
  request.imagePath = kInputPath;
  request.outputDirectory = kOutputDirectory;
  request.returnDebugImages = false;
  request.maxInputDimension = 0;
  const omr::NormalizeResult result = engine.normalize(request);
  std::cout << "PERF exact_ms=" << result.processingTimeMs << '\n';

  ok &= require(result.success, "distorted page must normalize successfully");
  ok &= require(result.status == "NORMALIZED", "distorted page must return NORMALIZED status");
  ok &= require(result.detectedLeftDots >= 28, "left timing rail must meet the normalization minimum");
  ok &= require(result.detectedRightDots >= 28, "right timing rail must meet the normalization minimum");
  ok &= require(
    result.homographyStrategyUsed == "all_47_registration_rows" && !result.fallbackUsed,
    "matching five-column timing geometry must use the full registration profile"
  );
  ok &= require(result.matchedRowsUsed >= 40, "full-page normalization must retain broad row coverage");
  ok &= require(result.railReprojection.headerSamples >= 12, "QR/header geometry must have direct rail evidence");
  ok &= require(result.railReprojection.studentCodeSamples >= 16, "student-code geometry must have direct rail evidence");
  ok &= require(result.railReprojection.answerSamples >= 50, "answer geometry must have direct rail evidence");
  ok &= require(result.railReprojection.headerRms <= 2.0, "QR/header reprojection RMS must stay below 2px");
  ok &= require(result.railReprojection.studentCodeRms <= 2.0, "student-code reprojection RMS must stay below 2px");
  ok &= require(result.railReprojection.answerRms <= 2.0, "answer reprojection RMS must stay below 2px");

  ok &= require(result.barcode.found, "exam QR must be found after perspective correction");
  ok &= require(result.barcode.status == "FOUND", "exam QR status must be FOUND");
  ok &= require(result.barcode.text == kQrPayload, "exam QR payload must be exact");

  omr::NormalizeRequest qrOnlyRequest = request;
  qrOnlyRequest.persistNormalizedImage = false;
  qrOnlyRequest.qrOnly = true;
  const omr::NormalizeResult qrOnlyResult = engine.normalize(qrOnlyRequest);
  std::cout << "PERF qr_only_ms=" << qrOnlyResult.processingTimeMs << '\n';
  ok &= require(qrOnlyResult.success, "QR-only page must normalize successfully");
  ok &= require(qrOnlyResult.barcode.found, "QR-only scan must find the exam QR");
  ok &= require(qrOnlyResult.barcode.text == kQrPayload, "QR-only payload must be exact");
  ok &= require(!qrOnlyResult.studentCode.available, "QR-only scan must skip student-code reading");
  ok &= require(qrOnlyResult.answers.questions.empty(), "QR-only scan must skip answer reading");
  ok &= require(qrOnlyResult.selectedAnswers.empty(), "QR-only scan must not emit selected answers");
  ok &= require(qrOnlyResult.normalizedImagePath.empty(), "QR-only scan must not persist a normalized image");

  ok &= require(result.studentCode.complete, "five-column student code must be complete");
  ok &= require(result.studentCode.value == "01234", "student-code value must preserve leading zero");
  ok &= require(result.studentId == "01234", "student ID must exactly match the five-column code");
  ok &= require(result.studentCode.summary.marked == 5, "exactly five student-code columns must be marked");

  ok &= require(result.answers.available, "answer data must be available");
  ok &= require(result.answers.summary.total == 120, "all 120 questions must be returned");
  ok &= require(result.answers.summary.marked == 4, "exactly four known answers must be marked");
  ok &= require(result.answers.summary.blank == 116, "all non-truth answer rows must remain blank");
  ok &= require(result.answers.summary.multi == 0, "known answers must not create multiple marks");
  ok &= require(result.answers.summary.ambiguous == 0, "known answers must not be ambiguous");
  ok &= require(result.selectedAnswers.size() == expectedAnswers.size(), "selected-answer map must contain only the four truth answers");

  for (const auto& expected : expectedAnswers) {
    const omr::OmrAnswerQuestionResult* question = resultForQuestion(result.answers, expected.question);
    ok &= require(question != nullptr, "truth question must be present: " + std::to_string(expected.question));
    if (question == nullptr) {
      continue;
    }
    ok &= require(question->status == "MARKED", "truth question must be MARKED: " + std::to_string(expected.question));
    ok &= require(
      question->selectedOption == expected.option,
      "truth option must be exact for question " + std::to_string(expected.question)
    );
    const auto selected = result.selectedAnswers.find(expected.question);
    ok &= require(selected != result.selectedAnswers.end(), "selected-answer map must contain truth question");
    if (selected != result.selectedAnswers.end()) {
      ok &= require(
        selected->second == std::stod(expected.option),
        "numeric selected answer must match truth for question " + std::to_string(expected.question)
      );
    }
  }

  ok &= require(!result.reviewRequired, "exact distorted capture must not require manual review");
  ok &= require(result.reviewReasons.empty(), "exact distorted capture must not emit review reasons");

  omr::NormalizeRequest mobileSizedRequest = request;
  mobileSizedRequest.persistNormalizedImage = false;
  mobileSizedRequest.maxInputDimension = 1800;
  const omr::NormalizeResult mobileSizedResult = engine.normalize(mobileSizedRequest);
  std::cout << "PERF mobile_1800_ms=" << mobileSizedResult.processingTimeMs << '\n';
  ok &= require(mobileSizedResult.success, "1800px mobile input must normalize successfully");
  ok &= require(mobileSizedResult.barcode.text == result.barcode.text, "1800px input must preserve the exact QR payload");
  ok &= require(mobileSizedResult.studentCode.value == result.studentCode.value, "1800px input must preserve the student code");
  ok &= require(
    mobileSizedResult.selectedAnswers == result.selectedAnswers,
    "1800px input must preserve every selected answer"
  );
  ok &= require(
    mobileSizedResult.answers.summary.marked == result.answers.summary.marked &&
    mobileSizedResult.answers.summary.blank == result.answers.summary.blank &&
    mobileSizedResult.answers.summary.multi == result.answers.summary.multi &&
    mobileSizedResult.answers.summary.ambiguous == result.answers.summary.ambiguous,
    "1800px input must preserve answer classifications"
  );

  // Guard the real classroom case that exposed the Flutter display bug: the
  // first twenty questions are all answered, with choices cycling 1..4.
  std::vector<ExpectedAnswer> twentyExpectedAnswers;
  for (int question = 1; question <= 20; ++question) {
    twentyExpectedAnswers.push_back({question, std::to_string(((question - 1) % 4) + 1)});
  }
  int twentyAnswerRingCount = 0;
  int twentyStudentRingCount = 0;
  const cv::Mat twentyCanonical = canonicalPage(
    tpl,
    twentyExpectedAnswers,
    twentyAnswerRingCount,
    twentyStudentRingCount
  );
  const cv::Mat twentyCapture = distortedCapture(twentyCanonical);
  ok &= require(
    cv::imwrite(kTwentyAnswerInputPath, twentyCapture, {cv::IMWRITE_JPEG_QUALITY, 86}),
    "twenty-answer JPEG fixture must be written"
  );
  if (ok) {
    omr::NormalizeRequest twentyRequest = request;
    twentyRequest.imagePath = kTwentyAnswerInputPath;
    twentyRequest.persistNormalizedImage = false;
    twentyRequest.maxInputDimension = 1800;
    const omr::NormalizeResult twentyResult = engine.normalize(twentyRequest);
    std::cout << "PERF twenty_answers_1800_ms=" << twentyResult.processingTimeMs << '\n';
    ok &= require(twentyResult.success, "twenty-answer mobile fixture must normalize successfully");
    ok &= require(twentyResult.answers.summary.total == 120, "twenty-answer fixture must return all 120 questions");
    ok &= require(twentyResult.answers.summary.marked == 20, "all twenty filled questions must be marked");
    ok &= require(twentyResult.answers.summary.blank == 100, "the remaining one hundred questions must be blank");
    ok &= require(twentyResult.selectedAnswers.size() == 20, "selected-answer map must retain twenty entries");
    for (const auto& expected : twentyExpectedAnswers) {
      const omr::OmrAnswerQuestionResult* question = resultForQuestion(
        twentyResult.answers,
        expected.question
      );
      ok &= require(
        question != nullptr && question->status == "MARKED" && question->selectedOption == expected.option,
        "twenty-answer choice must be exact for question " + std::to_string(expected.question)
      );
    }
  }

  cv::Mat sidewaysCapture;
  cv::rotate(capture, sidewaysCapture, cv::ROTATE_90_COUNTERCLOCKWISE);
  ok &= require(
    cv::imwrite(kInputPath, sidewaysCapture, {cv::IMWRITE_JPEG_QUALITY, 86}),
    "sideways scanner fixture must be written"
  );
  if (ok) {
    request.persistNormalizedImage = false;
    const omr::NormalizeResult sidewaysResult = engine.normalize(request);
    std::cout << "PERF sideways_ms=" << sidewaysResult.processingTimeMs << '\n';
    const bool reportedAutoRotation = std::find_if(
      sidewaysResult.warnings.begin(),
      sidewaysResult.warnings.end(),
      [](const std::string& warning) {
        return warning.rfind("input_auto_rotated_90_", 0) == 0;
      }
    ) != sidewaysResult.warnings.end();
    ok &= require(sidewaysResult.success, "sideways scanner output must normalize automatically");
    ok &= require(reportedAutoRotation, "sideways input must report automatic orientation correction");
    ok &= require(sidewaysResult.barcode.text == kQrPayload, "auto-rotation must preserve the exact QR payload");
    ok &= require(sidewaysResult.studentCode.value == "01234", "auto-rotation must preserve the student code");
    ok &= require(
      sidewaysResult.selectedAnswers == result.selectedAnswers,
      "auto-rotation must preserve every selected answer"
    );
    ok &= require(
      sidewaysResult.answers.summary.marked == result.answers.summary.marked &&
      sidewaysResult.answers.summary.blank == result.answers.summary.blank &&
      sidewaysResult.answers.summary.multi == result.answers.summary.multi &&
      sidewaysResult.answers.summary.ambiguous == result.answers.summary.ambiguous,
      "auto-rotation must preserve answer classifications"
    );
  }

  // Android document scanners sometimes keep portrait JPEG dimensions while
  // placing a sideways sheet inside that canvas. Orientation must follow the
  // sheet content rather than the outer bitmap aspect ratio.
  const int portraitCanvasWidth = sidewaysCapture.cols + 120;
  const int portraitCanvasHeight = portraitCanvasWidth + 120;
  cv::Mat portraitCanvas(
    portraitCanvasHeight,
    portraitCanvasWidth,
    CV_8UC3,
    kPaperBgr
  );
  const int portraitOffsetX = (portraitCanvas.cols - sidewaysCapture.cols) / 2;
  const int portraitOffsetY = (portraitCanvas.rows - sidewaysCapture.rows) / 2;
  sidewaysCapture.copyTo(portraitCanvas(cv::Rect(
    portraitOffsetX,
    portraitOffsetY,
    sidewaysCapture.cols,
    sidewaysCapture.rows
  )));
  ok &= require(
    portraitCanvas.rows > portraitCanvas.cols,
    "scanner regression fixture must retain portrait JPEG dimensions"
  );
  ok &= require(
    cv::imwrite(kInputPath, portraitCanvas, {cv::IMWRITE_JPEG_QUALITY, 86}),
    "portrait-canvas sideways scanner fixture must be written"
  );
  if (ok) {
    const omr::NormalizeResult portraitSidewaysResult = engine.normalize(request);
    std::cout << "PERF portrait_canvas_sideways_ms="
              << portraitSidewaysResult.processingTimeMs << '\n';
    const bool reportedAutoRotation = std::find_if(
      portraitSidewaysResult.warnings.begin(),
      portraitSidewaysResult.warnings.end(),
      [](const std::string& warning) {
        return warning.rfind("input_auto_rotated_90_", 0) == 0;
      }
    ) != portraitSidewaysResult.warnings.end();
    ok &= require(
      portraitSidewaysResult.success,
      "sideways sheet inside a portrait canvas must normalize automatically"
    );
    ok &= require(
      reportedAutoRotation,
      "portrait canvas must report its content-based orientation correction"
    );
    ok &= require(
      portraitSidewaysResult.barcode.text == kQrPayload,
      "portrait-canvas correction must preserve the exact QR payload"
    );
    ok &= require(
      portraitSidewaysResult.studentCode.value == "01234",
      "portrait-canvas correction must preserve the student code"
    );
    ok &= require(
      portraitSidewaysResult.selectedAnswers == result.selectedAnswers,
      "portrait-canvas correction must preserve every selected answer"
    );
  }

  cv::Mat upsideDownCapture;
  cv::rotate(capture, upsideDownCapture, cv::ROTATE_180);
  ok &= require(
    cv::imwrite(kInputPath, upsideDownCapture, {cv::IMWRITE_JPEG_QUALITY, 86}),
    "upside-down scanner fixture must be written"
  );
  if (ok) {
    const omr::NormalizeResult upsideDownResult = engine.normalize(request);
    std::cout << "PERF upside_down_ms=" << upsideDownResult.processingTimeMs << '\n';
    const bool reportedAutoRotation = std::find(
      upsideDownResult.warnings.begin(),
      upsideDownResult.warnings.end(),
      "input_auto_rotated_180"
    ) != upsideDownResult.warnings.end();
    ok &= require(upsideDownResult.success, "upside-down scanner output must normalize automatically");
    ok &= require(reportedAutoRotation, "upside-down input must report automatic orientation correction");
    ok &= require(upsideDownResult.barcode.text == kQrPayload, "180-degree correction must preserve the QR payload");
    ok &= require(upsideDownResult.studentCode.value == "01234", "180-degree correction must preserve the student code");
    ok &= require(
      upsideDownResult.selectedAnswers == result.selectedAnswers,
      "180-degree correction must preserve every selected answer"
    );
  }

  int weakAnswerRingCount = 0;
  int weakStudentRingCount = 0;
  const cv::Mat weakCanonical = canonicalPage(
    tpl,
    expectedAnswers,
    weakAnswerRingCount,
    weakStudentRingCount,
    true
  );
  const cv::Mat weakCapture = distortedCapture(weakCanonical);
  ok &= require(
    cv::imwrite(kInputPath, weakCapture, {cv::IMWRITE_JPEG_QUALITY, 86}),
    "low-confidence student-code fixture must be written"
  );
  if (ok) {
    request.persistNormalizedImage = false;
    const omr::NormalizeResult weakResult = engine.normalize(request);
    std::cout << "PERF weak_ms=" << weakResult.processingTimeMs << '\n';
    const bool hasStudentConfidenceReason = std::find(
      weakResult.reviewReasons.begin(),
      weakResult.reviewReasons.end(),
      "student_code_confidence_low"
    ) != weakResult.reviewReasons.end();
    if (!hasStudentConfidenceReason) {
      std::cerr << "INFO: weak student columns";
      for (const auto& column : weakResult.studentCode.columns) {
        std::cerr << " [" << column.columnIndex << ':' << column.status
                  << " confidence=" << column.confidence << ']';
        if (column.columnIndex == 0) {
          for (const auto& digit : column.digits) {
            if (digit.digit == "0") {
              std::cerr << " selectedScore=" << digit.score << " raw=" << digit.rawScore;
            }
          }
        }
      }
      std::cerr << '\n';
    }
    ok &= require(weakResult.success, "low-confidence student-code sheet must still normalize");
    ok &= require(
      weakResult.normalizedImagePath.empty(),
      "mobile low-I/O mode must skip normalized image persistence"
    );
    ok &= require(weakResult.studentCode.complete, "boundary-level student mark must remain a readable candidate");
    ok &= require(weakResult.studentCode.value == "01234", "boundary-level student candidate must retain its value");
    ok &= require(weakResult.reviewRequired, "boundary-level student evidence must require review");
    ok &= require(hasStudentConfidenceReason, "student confidence below 0.85 must emit an explicit review reason");
  }
  return ok;
}

bool runProductionTemplateRegression() {
  std::ifstream templateInput(OMR_TEST_TEMPLATE_PATH);
  if (!templateInput) {
    return require(false, "production template must be readable");
  }
  std::ostringstream templateJson;
  templateJson << templateInput.rdbuf();
  const omr::OmrTemplate tpl = omr::OmrTemplate::fromJson(templateJson.str());

  std::vector<ExpectedAnswer> expectedAnswers;
  for (int question = 1; question <= 20; ++question) {
    expectedAnswers.push_back({question, std::to_string(((question - 1) % 4) + 1)});
  }

  int answerRingCount = 0;
  int studentRingCount = 0;
  const cv::Mat canonical = canonicalPage(
    tpl,
    expectedAnswers,
    answerRingCount,
    studentRingCount
  );
  const cv::Mat capture = distortedCapture(canonical);
  bool ok = true;
  ok &= require(answerRingCount == 480, "production fixture must contain 480 answer rings");
  ok &= require(studentRingCount == 60, "production fixture must contain 60 student-code rings");
  ok &= require(
    cv::imwrite(
      kProductionTwentyAnswerInputPath,
      capture,
      {cv::IMWRITE_JPEG_QUALITY, 86}
    ),
    "production twenty-answer JPEG fixture must be written"
  );
  if (!ok) {
    return false;
  }

  omr::NormalizeEngine engine;
  engine.configure(tpl, omr::OmrTemplate::optionsFromJson("", tpl));
  omr::NormalizeRequest request;
  request.imagePath = kProductionTwentyAnswerInputPath;
  request.outputDirectory = kOutputDirectory;
  request.persistNormalizedImage = false;
  request.maxInputDimension = 1800;
  request.collectQualityMetrics = false;
  const omr::NormalizeResult result = engine.normalize(request);
  std::cout << "PERF production_twenty_answers_1800_ms="
            << result.processingTimeMs << '\n';

  ok &= require(result.success, "production twenty-answer fixture must normalize successfully");
  ok &= require(result.barcode.text == kQrPayload, "production fixture must preserve its QR payload");
  ok &= require(result.studentCode.complete, "production fixture student code must be complete");
  ok &= require(result.studentCode.value == "012345", "production fixture student code must be exact");
  ok &= require(result.answers.summary.total == 120, "production fixture must return all 120 questions");
  ok &= require(result.answers.summary.marked == 20, "production fixture must retain twenty marks");
  ok &= require(result.answers.summary.blank == 100, "production fixture must retain one hundred blanks");
  ok &= require(result.answers.summary.multi == 0, "production fixture must not invent multiple marks");
  ok &= require(result.answers.summary.ambiguous == 0, "production fixture must not invent ambiguous marks");
  for (const auto& expected : expectedAnswers) {
    const omr::OmrAnswerQuestionResult* question = resultForQuestion(
      result.answers,
      expected.question
    );
    ok &= require(
      question != nullptr &&
        question->status == "MARKED" &&
        question->selectedOption == expected.option,
      "production choice must be exact for question " +
        std::to_string(expected.question)
    );
  }
  return ok;
}

} // namespace

int main() {
  try {
    const bool accuracyPassed = runAccuracyRegression();
    const bool productionPassed = runProductionTemplateRegression();
    if (accuracyPassed && productionPassed) {
      std::cout << "Normalize accuracy regression tests passed\n";
      return 0;
    }
  } catch (const std::exception& error) {
    std::cerr << "FAIL: unexpected exception: " << error.what() << '\n';
  }
  return 1;
}
