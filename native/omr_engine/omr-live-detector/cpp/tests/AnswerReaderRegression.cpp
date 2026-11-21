#include "omr/AnswerReader.hpp"

#include <algorithm>
#include <cmath>
#include <iostream>
#include <string>
#include <utility>
#include <vector>
#include <opencv2/imgproc.hpp>

namespace {

const cv::Scalar kPrintedRingBgr{150, 75, 150};

void drawPrintedBubble(cv::Mat& image, cv::Point center, int radius) {
  cv::circle(image, center, radius, kPrintedRingBgr, 2, cv::LINE_AA);
  cv::line(
    image,
    cv::Point(center.x - 2, center.y),
    cv::Point(center.x + 2, center.y),
    kPrintedRingBgr,
    1,
    cv::LINE_AA
  );
}

cv::Mat illuminatedCanvas(int rows, int cols) {
  cv::Mat image(rows, cols, CV_8UC3);
  for (int y = 0; y < rows; ++y) {
    cv::Vec3b* row = image.ptr<cv::Vec3b>(y);
    for (int x = 0; x < cols; ++x) {
      const double horizontal = 42.0 * static_cast<double>(x) / std::max(1, cols - 1);
      const double vertical = 18.0 * static_cast<double>(y) / std::max(1, rows - 1);
      const double softShadow = 54.0 * std::exp(
        -0.5 * std::pow((static_cast<double>(y) - rows * 0.52) / (rows * 0.12), 2.0)
      );
      const int paper = cvRound(std::clamp(248.0 - horizontal - vertical - softShadow, 126.0, 248.0));
      row[x] = cv::Vec3b(
        static_cast<uint8_t>(paper),
        static_cast<uint8_t>(paper),
        static_cast<uint8_t>(paper)
      );
    }
  }
  return image;
}

bool require(bool condition, const std::string& message) {
  if (!condition) {
    std::cerr << "FAIL: " << message << '\n';
  }
  return condition;
}

omr::OmrTemplate answerTemplate() {
  omr::OmrTemplate tpl;
  tpl.width = 360.0f;
  tpl.height = 300.0f;
  tpl.answers.enabled = true;
  tpl.answers.questionCount = 3;
  tpl.answers.options = {"1", "2", "3", "4"};
  tpl.answers.thresholds.markedFillRatio = 0.38f;
  tpl.answers.thresholds.blankMaxFillRatio = 0.35f;
  tpl.answers.thresholds.multiMarkMinRatio = 0.40f;
  tpl.answers.thresholds.ambiguousDelta = 0.14f;
  tpl.answersRegion = {50.0f, 70.0f, 250.0f, 170.0f};
  tpl.registration.rowY = {100.0f, 150.0f, 200.0f};

  omr::OmrAnswerBlockTemplate block;
  block.id = "test_answers";
  block.questionStart = 1;
  block.questionCount = 3;
  block.xByOption = {{"1", 100.0f}, {"2", 140.0f}, {"3", 180.0f}, {"4", 220.0f}};
  block.yByRow = tpl.registration.rowY;
  block.bubbleRadius = 15.0f;
  tpl.answers.blocks = {block};
  return tpl;
}

bool testAnswersAndUnsafeRefinement() {
  const omr::OmrTemplate tpl = answerTemplate();
  cv::Mat image(300, 360, CV_8UC3, cv::Scalar(245, 245, 245));
  for (const float y : tpl.registration.rowY) {
    for (const auto& [option, x] : tpl.answers.blocks.front().xByOption) {
      (void)option;
      drawPrintedBubble(image, cv::Point(cvRound(x), cvRound(y)), 15);
    }
  }
  cv::circle(image, cv::Point(140, 100), 11, cv::Scalar(30, 30, 30), -1, cv::LINE_AA);
  cv::circle(image, cv::Point(220, 200), 11, cv::Scalar(35, 35, 35), -1, cv::LINE_AA);

  omr::BubbleGridRefinement unsafe;
  omr::OmrBubbleGridStats stats;
  stats.blockId = "test_answers";
  stats.expectedCentersCount = 12;
  stats.detectedCentersCount = 3;
  stats.usedCentersCount = 3;
  stats.detectedCoverage = 0.25;
  stats.confidence = 0.30;
  stats.rmsError = 8.0;
  stats.verticalDrift = 7.0;
  unsafe.answerBlockStats.push_back(stats);
  for (int row = 0; row < 3; ++row) {
    for (const auto& [option, x] : tpl.answers.blocks.front().xByOption) {
      omr::RefinedAnswerBubbleCenter center;
      center.blockId = "test_answers";
      center.questionNumber = row + 1;
      center.rowIndex = row;
      center.option = option;
      center.expected = {x, tpl.registration.rowY[static_cast<size_t>(row)]};
      center.refined = {x + 8.0f, center.expected.y + 8.0f};
      center.confidence = 0.30f;
      unsafe.answerCenters.push_back(center);
    }
  }

  const omr::OmrAnswerReadResult result = omr::AnswerReader::read(image, tpl, &unsafe);
  bool ok = true;
  ok &= require(result.summary.marked == 2, "two genuinely filled answer bubbles must be marked");
  ok &= require(result.summary.blank == 1, "printed rings must leave the middle question blank");
  ok &= require(result.summary.multi == 0, "unsafe geometry must not create multiple marks");
  ok &= require(result.summary.ambiguous == 0, "unsafe geometry must not create ambiguity");
  ok &= require(result.questions.size() == 3, "three answer rows must be returned");
  if (result.questions.size() == 3) {
    ok &= require(result.questions[0].selectedOption == "2", "question 1 must select option 2");
    ok &= require(result.questions[1].status == "BLANK", "question 2 must stay blank");
    ok &= require(result.questions[2].selectedOption == "4", "question 3 must select option 4");
    ok &= require(
      result.questions[0].confidence >= 0.85,
      "a strong marked answer must retain high confidence"
    );
    ok &= require(
      result.questions[1].confidence >= 0.85,
      "a clean blank answer must report high confidence in the BLANK result"
    );
    ok &= require(
      result.questions[2].confidence >= 0.85,
      "a second strong marked answer must retain high confidence"
    );
    ok &= require(!result.questions[0].options.front().hasRefinement, "unsafe refinement must be rejected");
  }
  return ok;
}

bool testAccurateLargeTranslationRefinementIsAccepted() {
  const omr::OmrTemplate tpl = answerTemplate();
  cv::Mat image(300, 360, CV_8UC3, cv::Scalar(245, 245, 245));
  constexpr float kDx = 6.0f;
  constexpr float kDy = 5.0f;
  for (const float y : tpl.registration.rowY) {
    for (const auto& [option, x] : tpl.answers.blocks.front().xByOption) {
      (void)option;
      drawPrintedBubble(image, cv::Point(cvRound(x + kDx), cvRound(y + kDy)), 15);
    }
  }
  cv::circle(image, cv::Point(146, 105), 11, cv::Scalar(30, 30, 30), -1, cv::LINE_AA);
  cv::circle(image, cv::Point(226, 205), 11, cv::Scalar(35, 35, 35), -1, cv::LINE_AA);

  omr::BubbleGridRefinement refinement;
  refinement.templateRefinementUsed = true;
  omr::OmrBubbleGridStats stats;
  stats.blockId = "test_answers";
  stats.expectedCentersCount = 12;
  stats.detectedCentersCount = 12;
  stats.usedCentersCount = 12;
  stats.detectedCoverage = 1.0;
  stats.confidence = 0.95;
  stats.appliedShiftRms = std::sqrt(kDx * kDx + kDy * kDy);
  stats.fitResidualRms = 0.35;
  stats.rmsError = stats.appliedShiftRms;
  stats.maxError = stats.appliedShiftRms;
  refinement.answerBlockStats.push_back(stats);
  for (int row = 0; row < 3; ++row) {
    for (const auto& [option, x] : tpl.answers.blocks.front().xByOption) {
      omr::RefinedAnswerBubbleCenter center;
      center.blockId = "test_answers";
      center.questionNumber = row + 1;
      center.rowIndex = row;
      center.option = option;
      center.expected = {x, tpl.registration.rowY[static_cast<size_t>(row)]};
      center.refined = {center.expected.x + kDx, center.expected.y + kDy};
      center.confidence = 0.95f;
      refinement.answerCenters.push_back(center);
    }
  }

  const omr::OmrAnswerReadResult result = omr::AnswerReader::read(image, tpl, &refinement);
  bool ok = true;
  ok &= require(result.summary.marked == 2, "accurate 5-8px refinement must preserve both marks");
  ok &= require(result.summary.blank == 1, "accurate large translation must preserve the blank row");
  if (!result.questions.empty() && !result.questions.front().options.empty()) {
    ok &= require(
      result.questions.front().options.front().hasRefinement,
      "safe refinement must be accepted based on fit residual, not rejected for its shift magnitude"
    );
  }
  return ok;
}

bool testSoftShadowEdgeDoesNotCreateInk() {
  const omr::OmrTemplate tpl = answerTemplate();
  cv::Mat image(300, 360, CV_8UC3);
  for (int y = 0; y < image.rows; ++y) {
    cv::Vec3b* row = image.ptr<cv::Vec3b>(y);
    for (int x = 0; x < image.cols; ++x) {
      // A broad illumination ramp crosses the legacy absolute ink cutoff near
      // the first option, but has no bubble-scale discontinuity or ink.
      const uint8_t paper = static_cast<uint8_t>(std::clamp(10 + x, 70, 245));
      row[x] = cv::Vec3b(paper, paper, paper);
    }
  }
  for (const float y : tpl.registration.rowY) {
    for (const auto& [option, x] : tpl.answers.blocks.front().xByOption) {
      (void)option;
      drawPrintedBubble(image, cv::Point(cvRound(x), cvRound(y)), 15);
    }
  }
  cv::GaussianBlur(image, image, cv::Size(5, 5), 1.0);

  const omr::OmrAnswerReadResult result = omr::AnswerReader::read(image, tpl);
  bool ok = true;
  ok &= require(result.summary.blank == 3, "a soft illumination edge must leave every empty row blank");
  ok &= require(result.summary.marked == 0, "dark paper without local ink contrast must never become a mark");
  ok &= require(result.summary.multi == 0, "a soft illumination edge must not create multiple marks");
  ok &= require(result.summary.ambiguous == 0, "a soft illumination edge must not create ambiguity");
  return ok;
}

bool testBubbleScaleShadowCannotAutoAccept() {
  const omr::OmrTemplate tpl = answerTemplate();
  bool ok = true;
  for (const auto& [sigma, amplitude] : std::vector<std::pair<double, double>>{{20.0, 60.0}, {40.0, 115.0}}) {
    cv::Mat image(300, 360, CV_8UC3, cv::Scalar(245, 245, 245));
    for (const float y : tpl.registration.rowY) {
      for (const auto& [option, x] : tpl.answers.blocks.front().xByOption) {
        (void)option;
        drawPrintedBubble(image, cv::Point(cvRound(x), cvRound(y)), 15);
      }
    }
    for (int y = 0; y < image.rows; ++y) {
      cv::Vec3b* row = image.ptr<cv::Vec3b>(y);
      for (int x = 0; x < image.cols; ++x) {
        const double dx = static_cast<double>(x) - 140.0;
        const double dy = static_cast<double>(y) - 100.0;
        const double shadow = amplitude * std::exp(-(dx * dx + dy * dy) / (2.0 * sigma * sigma));
        for (int channel = 0; channel < 3; ++channel) {
          row[x][channel] = cv::saturate_cast<uint8_t>(static_cast<double>(row[x][channel]) - shadow);
        }
      }
    }

    const omr::OmrAnswerReadResult result = omr::AnswerReader::read(image, tpl);
    ok &= require(!result.questions.empty(), "bubble-scale shadow case must return question 1");
    if (!result.questions.empty()) {
      const auto& question = result.questions.front();
      const bool autoAccepted = question.status == "MARKED" && question.confidence >= 0.85;
      if (autoAccepted) {
        std::cerr << "INFO: shadow sigma=" << sigma << " amplitude=" << amplitude
                  << " status=" << question.status << " confidence=" << question.confidence << '\n';
      }
      ok &= require(!autoAccepted, "a smooth shadow without ink must not become an auto-accepted answer");
    }
  }
  return ok;
}

bool testFaintPencilAndBlueInkUnderUnevenLight() {
  const omr::OmrTemplate tpl = answerTemplate();
  cv::Mat image = illuminatedCanvas(300, 360);
  for (const float y : tpl.registration.rowY) {
    for (const auto& [option, x] : tpl.answers.blocks.front().xByOption) {
      (void)option;
      drawPrintedBubble(image, cv::Point(cvRound(x), cvRound(y)), 15);
    }
  }

  // A light graphite mark is much brighter than the old global cutoff (118),
  // while the saturated blue mark is deliberately non-neutral. Both are common
  // on phone captures and must be judged against the paper immediately around
  // the bubble rather than against a page-wide absolute threshold.
  cv::circle(image, cv::Point(140, 100), 11, cv::Scalar(158, 158, 158), -1, cv::LINE_AA);
  cv::circle(image, cv::Point(180, 150), 11, cv::Scalar(188, 88, 34), -1, cv::LINE_AA);
  cv::GaussianBlur(image, image, cv::Size(3, 3), 0.65);

  const omr::OmrAnswerReadResult result = omr::AnswerReader::read(image, tpl);
  bool ok = true;
  ok &= require(result.questions.size() == 3, "uneven-light case must return all answer rows");
  ok &= require(result.summary.marked == 2, "faint pencil and blue ink must both be read as marks");
  ok &= require(result.summary.blank == 1, "an unmarked bubble row inside a soft shadow must stay blank");
  if (result.questions.size() == 3) {
    ok &= require(
      result.questions[0].status == "MARKED" && result.questions[0].selectedOption == "2",
      "faint graphite must select option 2"
    );
    ok &= require(
      result.questions[1].status == "MARKED" && result.questions[1].selectedOption == "3",
      "blue ink must select option 3"
    );
    ok &= require(result.questions[2].status == "BLANK", "soft illumination variation must not create a mark");
  }
  return ok;
}

bool testAmbiguousCandidateIsNeverPromoted() {
  const omr::OmrTemplate tpl = answerTemplate();
  cv::Mat image(300, 360, CV_8UC3, cv::Scalar(245, 245, 245));
  for (const float y : tpl.registration.rowY) {
    for (const auto& [option, x] : tpl.answers.blocks.front().xByOption) {
      (void)option;
      drawPrintedBubble(image, cv::Point(cvRound(x), cvRound(y)), 15);
    }
  }

  // This radius covers enough of the scoring annulus to be non-blank, but not
  // enough to cross the blank boundary, but not the production marked/gap
  // boundaries together.
  cv::ellipse(
    image,
    cv::Point(140, 100),
    cv::Size(7, 5),
    0.0,
    0.0,
    360.0,
    cv::Scalar(28, 28, 28),
    -1,
    cv::LINE_AA
  );
  cv::circle(image, cv::Point(140, 106), 1, cv::Scalar(28, 28, 28), -1, cv::LINE_AA);
  const omr::OmrAnswerReadResult result = omr::AnswerReader::read(image, tpl);

  bool ok = true;
  ok &= require(!result.questions.empty(), "ambiguous candidate case must return question 1");
  if (!result.questions.empty()) {
    const auto& question = result.questions.front();
    if (question.status != "AMBIGUOUS") {
      std::cerr << "INFO: partial-fill status=" << question.status;
      for (const auto& option : question.options) {
        std::cerr << " [" << option.option << " score=" << option.score << " raw=" << option.rawScore << ']';
      }
      std::cerr << '\n';
    }
    ok &= require(question.status == "AMBIGUOUS", "partial fill must remain ambiguous");
    ok &= require(!question.hasSelectedOption, "ambiguous candidate must not expose a selected option");
    ok &= require(!question.hasSelectedOptionNumber, "ambiguous candidate must not enter numeric selected output");
    ok &= require(question.selectedOptions.empty(), "ambiguous candidate must not enter selected-options output");
    ok &= require(question.confidence < 0.80, "boundary-level evidence must not report high confidence");
  }
  return ok;
}

bool testStrongMultipleMarksSurviveBaselineCalibration() {
  const omr::OmrTemplate tpl = answerTemplate();
  cv::Mat image(300, 360, CV_8UC3, cv::Scalar(245, 245, 245));
  for (const float y : tpl.registration.rowY) {
    for (const auto& [option, x] : tpl.answers.blocks.front().xByOption) {
      (void)option;
      drawPrintedBubble(image, cv::Point(cvRound(x), cvRound(y)), 15);
    }
  }
  cv::circle(image, cv::Point(140, 100), 11, cv::Scalar(25, 25, 25), -1, cv::LINE_AA);
  cv::circle(image, cv::Point(180, 100), 11, cv::Scalar(25, 25, 25), -1, cv::LINE_AA);

  const omr::OmrAnswerReadResult result = omr::AnswerReader::read(image, tpl);
  bool ok = true;
  ok &= require(!result.questions.empty(), "strong multi-mark case must return question 1");
  if (!result.questions.empty()) {
    const auto& question = result.questions.front();
    ok &= require(question.status == "MULTI", "two strong marks must remain a multiple selection");
    ok &= require(question.selectedOptions.size() == 2, "both strong marks must be retained for review");
    ok &= require(!question.hasSelectedOption, "multiple marks must never expose one canonical answer");
  }
  return ok;
}

bool testAllAnswerChoicesFilledStayMultiple() {
  const omr::OmrTemplate tpl = answerTemplate();
  cv::Mat image(300, 360, CV_8UC3, cv::Scalar(245, 245, 245));
  for (const float y : tpl.registration.rowY) {
    for (const auto& [option, x] : tpl.answers.blocks.front().xByOption) {
      (void)option;
      drawPrintedBubble(image, cv::Point(cvRound(x), cvRound(y)), 15);
    }
  }
  for (const auto& [option, x] : tpl.answers.blocks.front().xByOption) {
    (void)option;
    cv::circle(image, cv::Point(cvRound(x), 100), 11, cv::Scalar(24, 24, 24), -1, cv::LINE_AA);
  }

  const omr::OmrAnswerReadResult result = omr::AnswerReader::read(image, tpl);
  bool ok = true;
  ok &= require(!result.questions.empty(), "all-filled answer case must return question 1");
  if (!result.questions.empty()) {
    ok &= require(result.questions.front().status == "MULTI", "four filled choices must never collapse to BLANK");
    ok &= require(result.questions.front().selectedOptions.size() == 4, "all four filled choices must be retained");
  }
  return ok;
}

bool testBroadPurpleFillIsReadable() {
  const omr::OmrTemplate tpl = answerTemplate();
  cv::Mat image(300, 360, CV_8UC3, cv::Scalar(245, 245, 245));
  for (const float y : tpl.registration.rowY) {
    for (const auto& [option, x] : tpl.answers.blocks.front().xByOption) {
      (void)option;
      drawPrintedBubble(image, cv::Point(cvRound(x), cvRound(y)), 15);
    }
  }
  // Same hue as the printed form, but spread broadly through the scoring
  // annulus like a real pen fill rather than a thin ring or central glyph.
  cv::circle(image, cv::Point(140, 100), 11, kPrintedRingBgr, -1, cv::LINE_AA);

  const omr::OmrAnswerReadResult result = omr::AnswerReader::read(image, tpl);
  bool ok = true;
  ok &= require(!result.questions.empty(), "purple-fill case must return question 1");
  if (!result.questions.empty()) {
    ok &= require(
      result.questions.front().status == "MARKED" && result.questions.front().selectedOption == "2",
      "a broad purple pen fill must be distinguishable from the thin printed form ink"
    );
  }
  return ok;
}

bool testAllStudentDigitsFilledStayMultiple() {
  omr::OmrTemplate tpl;
  tpl.width = 120.0f;
  tpl.height = 450.0f;
  tpl.studentCode.enabled = true;
  tpl.studentCode.columns = 1;
  tpl.studentCode.columnReadOrder = "ltr";
  tpl.studentCode.bubbleRadius = 15.0f;
  tpl.studentCode.thresholds.markedFillRatio = 0.60f;
  tpl.studentCode.thresholds.blankMaxFillRatio = 0.42f;
  tpl.studentCode.thresholds.multiMarkMinRatio = 0.60f;
  tpl.studentCode.thresholds.ambiguousDelta = 0.18f;
  tpl.studentCode.xByColumnLeftToRight = {60.0f};
  tpl.studentCode.digitRowsTopToBottom.clear();
  tpl.studentCode.yByDigitTopToBottom.clear();
  for (int digit = 0; digit < 10; ++digit) {
    tpl.studentCode.digitRowsTopToBottom.push_back(std::to_string(digit));
    tpl.studentCode.yByDigitTopToBottom.push_back(45.0f + digit * 40.0f);
  }

  cv::Mat image(450, 120, CV_8UC3, cv::Scalar(245, 245, 245));
  for (const float y : tpl.studentCode.yByDigitTopToBottom) {
    drawPrintedBubble(image, cv::Point(60, cvRound(y)), 15);
    cv::circle(image, cv::Point(60, cvRound(y)), 11, cv::Scalar(24, 24, 24), -1, cv::LINE_AA);
  }

  const omr::OmrStudentCodeReadResult result = omr::AnswerReader::readStudentCode(image, tpl);
  bool ok = true;
  ok &= require(!result.complete, "a column with all digits filled must not produce a complete ID");
  ok &= require(result.summary.multi == 1 && result.summary.blank == 0,
    "a column with all digits filled must remain MULTI rather than collapse to BLANK");
  if (!result.columns.empty()) {
    ok &= require(result.columns.front().selectedDigits.size() == 10, "all ten filled digits must be retained for review");
  }
  return ok;
}

bool testStudentCodeLeftToRight(
  int columnCount,
  const std::string& expected,
  const std::string& caseName
) {
  if (static_cast<int>(expected.size()) != columnCount) {
    return require(false, caseName + ": expected value must match the configured column count");
  }

  omr::OmrTemplate tpl;
  tpl.width = 70.0f + static_cast<float>(std::max(0, columnCount - 1)) * 37.0f;
  tpl.height = 250.0f;
  tpl.studentCode.enabled = true;
  tpl.studentCode.columns = columnCount;
  tpl.studentCode.columnReadOrder = "ltr";
  tpl.studentCode.bubbleRadius = 8.0f;
  tpl.studentCode.thresholds.markedFillRatio = 0.60f;
  tpl.studentCode.thresholds.blankMaxFillRatio = 0.42f;
  tpl.studentCode.thresholds.multiMarkMinRatio = 0.60f;
  tpl.studentCode.thresholds.ambiguousDelta = 0.18f;
  for (int column = 0; column < columnCount; ++column) {
    tpl.studentCode.xByColumnLeftToRight.push_back(35.0f + column * 37.0f);
  }
  for (int digit = 0; digit < 10; ++digit) {
    tpl.studentCode.yByDigitTopToBottom.push_back(25.0f + digit * 21.0f);
  }

  cv::Mat image(
    static_cast<int>(tpl.height),
    static_cast<int>(tpl.width),
    CV_8UC3,
    cv::Scalar(245, 245, 245)
  );
  for (const float x : tpl.studentCode.xByColumnLeftToRight) {
    for (const float y : tpl.studentCode.yByDigitTopToBottom) {
      drawPrintedBubble(image, cv::Point(cvRound(x), cvRound(y)), 8);
    }
  }

  for (size_t column = 0; column < expected.size(); ++column) {
    const int digit = expected[column] - '0';
    cv::circle(
      image,
      cv::Point(
        cvRound(tpl.studentCode.xByColumnLeftToRight[column]),
        cvRound(tpl.studentCode.yByDigitTopToBottom[static_cast<size_t>(digit)])
      ),
      6,
      cv::Scalar(25, 25, 25),
      -1,
      cv::LINE_AA
    );
  }

  const omr::OmrStudentCodeReadResult result = omr::AnswerReader::readStudentCode(image, tpl);
  bool ok = true;
  ok &= require(result.complete, caseName + ": all left-to-right student-code columns must be complete");
  ok &= require(result.value == expected, caseName + ": student code must preserve left-to-right order");
  ok &= require(result.summary.columns == columnCount, caseName + ": summary must preserve the configured column count");
  ok &= require(result.summary.marked == columnCount, caseName + ": exactly one digit per student-code column must be marked");
  ok &= require(result.summary.multi == 0, caseName + ": printed student-code rings must not create multiple marks");
  return ok;
}

bool testStudentCodeHorizontalRows() {
  constexpr int kPositions = 5;
  const std::string expected = "90731";
  omr::OmrTemplate tpl;
  tpl.width = 480.0f;
  tpl.height = 230.0f;
  tpl.studentCode.enabled = true;
  tpl.studentCode.columns = kPositions;
  tpl.studentCode.layout = "rows_horizontal";
  tpl.studentCode.positionReadOrder = "top_to_bottom";
  tpl.studentCode.bubbleRadius = 8.0f;
  tpl.studentCode.thresholds.markedFillRatio = 0.60f;
  tpl.studentCode.thresholds.blankMaxFillRatio = 0.42f;
  tpl.studentCode.thresholds.multiMarkMinRatio = 0.60f;
  tpl.studentCode.thresholds.ambiguousDelta = 0.18f;
  tpl.studentCode.digitOptionsLeftToRight = {"9", "8", "7", "6", "5", "4", "3", "2", "1", "0"};
  for (int digitColumn = 0; digitColumn < 10; ++digitColumn) {
    tpl.studentCode.xByDigitLeftToRight.push_back(30.0f + digitColumn * 44.0f);
  }
  for (int position = 0; position < kPositions; ++position) {
    tpl.studentCode.yByPositionTopToBottom.push_back(30.0f + position * 40.0f);
  }

  cv::Mat image(
    static_cast<int>(tpl.height),
    static_cast<int>(tpl.width),
    CV_8UC3,
    cv::Scalar(245, 245, 245)
  );
  for (const float x : tpl.studentCode.xByDigitLeftToRight) {
    for (const float y : tpl.studentCode.yByPositionTopToBottom) {
      drawPrintedBubble(image, cv::Point(cvRound(x), cvRound(y)), 8);
    }
  }

  for (size_t position = 0; position < expected.size(); ++position) {
    const std::string digit(1, expected[position]);
    const auto digitIt = std::find(
      tpl.studentCode.digitOptionsLeftToRight.begin(),
      tpl.studentCode.digitOptionsLeftToRight.end(),
      digit
    );
    const size_t digitIndex = static_cast<size_t>(
      std::distance(tpl.studentCode.digitOptionsLeftToRight.begin(), digitIt)
    );
    const omr::Point center = tpl.studentCode.bubbleCenter(position, digitIndex);
    cv::circle(
      image,
      cv::Point(cvRound(center.x), cvRound(center.y)),
      6,
      cv::Scalar(25, 25, 25),
      -1,
      cv::LINE_AA
    );
  }

  const omr::OmrStudentCodeReadResult result = omr::AnswerReader::readStudentCode(image, tpl);
  bool ok = true;
  ok &= require(result.complete, "horizontal five-row student code must be complete");
  ok &= require(result.value == expected, "horizontal student code must be read from top row to bottom row");
  ok &= require(result.summary.columns == kPositions, "horizontal student-code summary must contain five positions");
  ok &= require(result.summary.marked == kPositions, "horizontal student-code rows must each contain one mark");
  ok &= require(result.summary.multi == 0, "horizontal printed rings must not create multiple marks");
  return ok;
}

bool testStudentCodeWithFaintMixedInk() {
  constexpr int kColumns = 5;
  const std::string expected = "50731";
  omr::OmrTemplate tpl;
  tpl.width = 230.0f;
  tpl.height = 250.0f;
  tpl.studentCode.enabled = true;
  tpl.studentCode.columns = kColumns;
  tpl.studentCode.columnReadOrder = "ltr";
  tpl.studentCode.bubbleRadius = 8.0f;
  tpl.studentCode.thresholds.markedFillRatio = 0.60f;
  tpl.studentCode.thresholds.blankMaxFillRatio = 0.42f;
  tpl.studentCode.thresholds.multiMarkMinRatio = 0.60f;
  tpl.studentCode.thresholds.ambiguousDelta = 0.18f;
  for (int column = 0; column < kColumns; ++column) {
    tpl.studentCode.xByColumnLeftToRight.push_back(35.0f + column * 39.0f);
  }
  for (int digit = 0; digit < 10; ++digit) {
    tpl.studentCode.yByDigitTopToBottom.push_back(25.0f + digit * 21.0f);
  }

  cv::Mat image = illuminatedCanvas(static_cast<int>(tpl.height), static_cast<int>(tpl.width));
  for (const float x : tpl.studentCode.xByColumnLeftToRight) {
    for (const float y : tpl.studentCode.yByDigitTopToBottom) {
      drawPrintedBubble(image, cv::Point(cvRound(x), cvRound(y)), 8);
    }
  }
  for (size_t column = 0; column < expected.size(); ++column) {
    const int digit = expected[column] - '0';
    const cv::Point center(
      cvRound(tpl.studentCode.xByColumnLeftToRight[column]),
      cvRound(tpl.studentCode.yByDigitTopToBottom[static_cast<size_t>(digit)])
    );
    const int localPaper = image.at<cv::Vec3b>(center.y, center.x)[0];
    const cv::Scalar ink = column % 2 == 0
      ? cv::Scalar(
          std::max(92, localPaper - 52),
          std::max(92, localPaper - 52),
          std::max(92, localPaper - 52)
        )
      : cv::Scalar(
          std::max(72, localPaper - 12),
          std::max(38, localPaper - 92),
          std::max(24, localPaper - 132)
        );
    cv::circle(
      image,
      center,
      6,
      ink,
      -1,
      cv::LINE_AA
    );
  }
  cv::GaussianBlur(image, image, cv::Size(3, 3), 0.55);

  const omr::OmrStudentCodeReadResult result = omr::AnswerReader::readStudentCode(image, tpl);
  bool ok = true;
  ok &= require(result.complete, "mixed faint student-code ink must remain complete");
  ok &= require(result.value == expected, "mixed faint student-code ink must preserve every digit");
  ok &= require(result.summary.marked == kColumns, "mixed faint student-code ink must mark exactly five columns");
  ok &= require(result.summary.multi == 0, "illumination variation must not create student-code multi marks");
  return ok;
}

bool testTwentyFilledQuestionsRetainEveryChoice() {
  constexpr int kQuestionCount = 20;
  omr::OmrTemplate tpl;
  tpl.width = 300.0f;
  tpl.height = 920.0f;
  tpl.answers.enabled = true;
  tpl.answers.questionCount = kQuestionCount;
  tpl.answers.options = {"1", "2", "3", "4"};
  tpl.answers.thresholds.markedFillRatio = 0.38f;
  tpl.answers.thresholds.blankMaxFillRatio = 0.35f;
  tpl.answers.thresholds.multiMarkMinRatio = 0.40f;
  tpl.answers.thresholds.ambiguousDelta = 0.14f;
  tpl.answersRegion = {45.0f, 35.0f, 210.0f, 850.0f};

  omr::OmrAnswerBlockTemplate block;
  block.id = "twenty_answers";
  block.questionStart = 1;
  block.questionCount = kQuestionCount;
  block.xByOption = {{"1", 75.0f}, {"2", 125.0f}, {"3", 175.0f}, {"4", 225.0f}};
  block.bubbleRadius = 12.0f;
  for (int question = 0; question < kQuestionCount; ++question) {
    block.yByRow.push_back(55.0f + question * 41.0f);
  }
  tpl.answers.blocks = {block};

  cv::Mat image(920, 300, CV_8UC3, cv::Scalar(245, 245, 245));
  for (int question = 0; question < kQuestionCount; ++question) {
    const float y = block.yByRow[static_cast<size_t>(question)];
    for (const auto& [option, x] : block.xByOption) {
      (void)option;
      drawPrintedBubble(image, cv::Point(cvRound(x), cvRound(y)), 12);
    }
    const int expectedOption = (question % 4) + 1;
    const float x = block.xByOption.at(std::to_string(expectedOption));
    cv::circle(
      image,
      cv::Point(cvRound(x), cvRound(y)),
      9,
      cv::Scalar(26, 26, 26),
      -1,
      cv::LINE_AA
    );
  }

  const omr::OmrAnswerReadResult result = omr::AnswerReader::read(image, tpl);
  bool ok = true;
  ok &= require(result.questions.size() == kQuestionCount, "twenty-answer fixture must return twenty rows");
  ok &= require(result.summary.marked == kQuestionCount, "all twenty filled questions must be marked");
  ok &= require(result.summary.blank == 0, "twenty-answer fixture must not lose a filled question");
  ok &= require(result.summary.multi == 0, "twenty single choices must not become multiple marks");
  for (int question = 0; question < kQuestionCount && question < static_cast<int>(result.questions.size()); ++question) {
    const std::string expectedOption = std::to_string((question % 4) + 1);
    ok &= require(
      result.questions[static_cast<size_t>(question)].status == "MARKED" &&
      result.questions[static_cast<size_t>(question)].selectedOption == expectedOption,
      "choice must be exact for filled question " + std::to_string(question + 1)
    );
  }
  return ok;
}

} // namespace

int main() {
  bool ok = true;
  ok &= testAnswersAndUnsafeRefinement();
  ok &= testAccurateLargeTranslationRefinementIsAccepted();
  ok &= testSoftShadowEdgeDoesNotCreateInk();
  ok &= testBubbleScaleShadowCannotAutoAccept();
  ok &= testFaintPencilAndBlueInkUnderUnevenLight();
  ok &= testAmbiguousCandidateIsNeverPromoted();
  ok &= testStrongMultipleMarksSurviveBaselineCalibration();
  ok &= testAllAnswerChoicesFilledStayMultiple();
  ok &= testBroadPurpleFillIsReadable();
  ok &= testAllStudentDigitsFilledStayMultiple();
  ok &= testStudentCodeLeftToRight(10, "1436523750", "legacy ten-column student code");
  ok &= testStudentCodeLeftToRight(5, "01234", "five-column student code");
  ok &= testStudentCodeHorizontalRows();
  ok &= testStudentCodeWithFaintMixedInk();
  ok &= testTwentyFilledQuestionsRetainEveryChoice();
  if (ok) {
    std::cout << "AnswerReader regression tests passed\n";
    return 0;
  }
  return 1;
}
