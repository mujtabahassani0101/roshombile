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
#if __has_include(<ZXing/MultiFormatWriter.h>)
#include <ZXing/BarcodeFormat.h>
#include <ZXing/BitMatrix.h>
#include <ZXing/MultiFormatWriter.h>
#else
#include <BarcodeFormat.h>
#include <BitMatrix.h>
#include <MultiFormatWriter.h>
#endif

namespace {

constexpr int kTemplateWidth = 1750;
constexpr int kTemplateHeight = 2480;
const cv::Scalar kPaperBgr{245, 245, 245};
const cv::Scalar kPrintedRingBgr{75, 3, 182};
const cv::Scalar kMarkedBgr{24, 24, 24};
const std::string kQrPayload = "T1:12345678:S2000000000000000000";
#if defined(__ANDROID__)
const std::string kOutputDirectory = "/data/local/tmp/omr-bench";
#else
const std::string kOutputDirectory = "/tmp";
#endif
const std::string kInputPath = kOutputDirectory + "/omr_normalize_accuracy_regression_input.jpg";

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

omr::OmrTemplate accuracyTemplate() {
  std::ifstream input(OMR_TEST_TEMPLATE_PATH);
  std::ostringstream json;
  json << input.rdbuf();
  return omr::OmrTemplate::fromJson(json.str());
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
  ZXing::MultiFormatWriter writer(ZXing::BarcodeFormat::QRCode);
  writer.setMargin(4).setEccLevel(2);
  ZXing::BitMatrix matrix = writer.encode(kQrPayload, roi.width, roi.height);

  cv::Mat qr(roi.height, roi.width, CV_8UC3, cv::Scalar(255, 255, 255));
  for (int y = 0; y < matrix.height(); ++y) {
    for (int x = 0; x < matrix.width(); ++x) {
      const std::uint8_t value = matrix.get(x, y) ? 0 : 255;
      qr.at<cv::Vec3b>(y, x) = cv::Vec3b(value, value, value);
    }
  }
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
  int& studentRingCount
) {
  cv::Mat page(kTemplateHeight, kTemplateWidth, CV_8UC3, kPaperBgr);

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
  for (const float x : tpl.studentCode.xByColumnLeftToRight) {
    for (const float y : tpl.studentCode.yByDigitTopToBottom) {
      drawPrintedRing(page, x, y, tpl.studentCode.bubbleRadius);
      studentRingCount += 1;
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
    {83, "1"},
    {58, "2"},
    {33, "3"},
    {8, "4"},
  };

  int answerRingCount = 0;
  int studentRingCount = 0;
  const cv::Mat canonical = canonicalPage(tpl, expectedAnswers, answerRingCount, studentRingCount);
  const cv::Mat capture = distortedCapture(canonical);

  bool ok = true;
  ok &= require(tpl.registration.rowY.size() == 29, "T1 template must contain 29 timing rows per rail");
  ok &= require(answerRingCount == 400, "T1 canonical page must contain 400 answer rings");
  ok &= require(studentRingCount == 0, "T1 canonical page must not contain student-code rings");
  ok &= require(
    cv::imwrite(kInputPath, capture, {cv::IMWRITE_JPEG_QUALITY, 86}),
    "distorted JPEG input must be written to the test output directory"
  );
  if (!ok) {
    return false;
  }

  const omr::OmrLiveOptions options = omr::OmrTemplate::optionsFromJson("", tpl);
  omr::NormalizeEngine unconfiguredEngine;
  const omr::NormalizeResult unconfiguredResult = unconfiguredEngine.normalize({});
  ok &= require(
    !unconfiguredResult.success && unconfiguredResult.error == "engine_not_configured",
    "normalization must reject calls made before a physical template is configured"
  );
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
  ok &= require(result.detectedLeftDots >= 18, "left timing rail must meet the T1 normalization minimum");
  ok &= require(result.detectedRightDots >= 18, "right timing rail must meet the T1 normalization minimum");
  ok &= require(
    result.homographyStrategyUsed == "all_29_registration_rows" && !result.fallbackUsed,
    "matching T1 geometry must use the full registration profile"
  );
  ok &= require(result.matchedRowsUsed >= 25, "full-page normalization must retain broad T1 row coverage");
  ok &= require(result.railReprojection.headerSamples >= 6, "QR/header geometry must have direct rail evidence");
  ok &= require(result.railReprojection.studentCodeSamples == 0, "T1 must not report student-code rail samples");
  ok &= require(result.railReprojection.answerSamples >= 50, "answer geometry must have direct rail evidence");
  ok &= require(result.railReprojection.headerRms <= 2.0, "QR/header reprojection RMS must stay below 2px");
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

  ok &= require(!result.studentCode.available, "T1 must report student code as unavailable");
  ok &= require(result.studentId.empty(), "T1 must not invent a student ID");

  ok &= require(result.answers.available, "answer data must be available");
  ok &= require(result.answers.summary.total == 100, "all 100 T1 questions must be returned");
  ok &= require(result.answers.summary.marked == 4, "exactly four known answers must be marked");
  ok &= require(result.answers.summary.blank == 96, "all non-truth T1 answer rows must remain blank");
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
  ok &= require(!mobileSizedResult.studentCode.available, "1800px T1 input must keep student code unavailable");
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
    ok &= require(!sidewaysResult.studentCode.available, "auto-rotation must not invent a student code");
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
      !portraitSidewaysResult.studentCode.available,
      "portrait-canvas correction must not invent a student code"
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
    ok &= require(!upsideDownResult.studentCode.available, "180-degree correction must not invent a student code");
    ok &= require(
      upsideDownResult.selectedAnswers == result.selectedAnswers,
      "180-degree correction must preserve every selected answer"
    );
  }

  // Exposure review remains mandatory in the fast production mode which skips
  // optional full-page metrics. This is a synthetic clipping control.
  cv::Mat clipped = canonical.clone();
  for (int y = 0; y < clipped.rows; ++y) {
    auto* pixels = clipped.ptr<cv::Vec3b>(y);
    for (int x = 0; x < clipped.cols; ++x) {
      if (pixels[x][0] >= 240 && pixels[x][1] >= 240 && pixels[x][2] >= 240) {
        pixels[x] = cv::Vec3b(255,255,255);
      }
    }
  }
  request.imagePath = kOutputDirectory + "/omr_clipped_paper.png";
  request.maxInputDimension = 0;
  request.collectQualityMetrics = false;
  request.persistNormalizedImage = false;
  ok &= require(cv::imwrite(request.imagePath, clipped), "clipped fixture must be written");
  const auto clippedResult = engine.normalize(request);
  ok &= require(clippedResult.success && clippedResult.reviewRequired &&
    std::find(clippedResult.reviewReasons.begin(), clippedResult.reviewReasons.end(),
      "answer_exposure_clipped") != clippedResult.reviewReasons.end(),
    "clipping must request review even without optional quality metrics");
  ok &= require(clippedResult.answers.summary.marked == 4 &&
    clippedResult.answers.summary.blank == 96 && clippedResult.selectedAnswers == result.selectedAnswers,
    "exposure review must preserve surviving answers and never invent marks on white paper");
  return ok;
}

} // namespace

int main() {
  try {
    if (runAccuracyRegression()) {
      std::cout << "Normalize accuracy regression tests passed\n";
      return 0;
    }
  } catch (const std::exception& error) {
    std::cerr << "FAIL: unexpected exception: " << error.what() << '\n';
  }
  return 1;
}
