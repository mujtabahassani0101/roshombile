#include "omr/AnswerReader.hpp"

#include <algorithm>
#include <cmath>
#include <iostream>
#include <string>
#include <utility>
#include <vector>
#include <opencv2/imgcodecs.hpp>
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

bool testQuestionLimitFromQrSheetType() {
  const omr::OmrTemplate tpl = answerTemplate();
  cv::Mat image(300, 360, CV_8UC3, cv::Scalar(245, 245, 245));
  for (const float y : tpl.registration.rowY) {
    for (const auto& [option, x] : tpl.answers.blocks.front().xByOption) {
      (void)option;
      drawPrintedBubble(image, cv::Point(cvRound(x), cvRound(y)), 15);
    }
  }
  cv::circle(image, cv::Point(140, 100), 11, cv::Scalar(30, 30, 30), -1, cv::LINE_AA);
  cv::circle(image, cv::Point(180, 150), 11, cv::Scalar(30, 30, 30), -1, cv::LINE_AA);
  cv::circle(image, cv::Point(220, 200), 11, cv::Scalar(30, 30, 30), -1, cv::LINE_AA);

  const omr::OmrAnswerReadResult result = omr::AnswerReader::read(
    image,
    tpl,
    nullptr,
    2
  );
  bool ok = true;
  ok &= require(result.summary.total == 2, "QR question limit must replace the template total");
  ok &= require(result.questions.size() == 2, "questions above the QR limit must not be returned");
  ok &= require(result.summary.marked == 2, "only active marked questions must be summarized");
  if (result.questions.size() == 2) {
    ok &= require(result.questions[0].questionNumber == 1, "limited output must start at question 1");
    ok &= require(result.questions[1].questionNumber == 2, "limited output must end at the requested limit");
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

bool testNonBlankPartialFillSelectsHighestPercentage() {
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
    ok &= require(question.status == "MARKED", "a non-blank partial fill must select one option");
    ok &= require(question.hasSelectedOption && question.selectedOption == "2", "highest partial fill must select option 2");
    ok &= require(question.hasSelectedOptionNumber, "single answer must expose its number");
    ok &= require(question.selectedOptions.size() == 1, "partial fill must expose exactly one answer");
    ok &= require(question.confidence < 0.80, "boundary-level evidence must not report high confidence");
  }
  return ok;
}

bool testEqualStrongMarksSelectOneDeterministicAnswer() {
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
    ok &= require(question.status == "MARKED", "two strong marks must produce a single answer");
    ok &= require(question.selectedOptions.size() == 1, "only one strong mark must be selected");
    ok &= require(question.hasSelectedOption && question.selectedOption == "2", "exact ties must choose the first option number");
    ok &= require(question.confidence < 0.85, "equal fills must retain honest low separation confidence");
  }
  return ok;
}

bool testSingleSelectionAcrossInkAndCameraVariants() {
  const omr::OmrTemplate tpl = answerTemplate();
  bool ok = true;
  for (const int paper : {245, 170, 110}) {
    for (const bool grayscale : {false, true}) {
      for (const bool jpeg : {false, true}) {
        for (const double gamma : {0.7, 1.0, 1.5, 2.0}) {
        for (const int darkChoices : {1, 2, 3, 4}) {
          cv::Mat image(300, 360, CV_8UC3, cv::Scalar(paper, paper, paper));
          for (const float y : tpl.registration.rowY) {
            for (const auto& [option, x] : tpl.answers.blocks.front().xByOption) {
              (void)option;
              drawPrintedBubble(image, cv::Point(cvRound(x), cvRound(y)), 15);
            }
          }
          // Row 1: broad faint/erased ink beside one or several dark answers.
          // Coverage is almost identical; the second dark fill is deliberately
          // lighter than the first so only truly pale residue can be discarded.
          for (int option = 1; option <= 4; ++option) {
            const int ink = cvRound(paper * (option <= darkChoices ? (option == 1 ? 0.22 : 0.55) : 0.78));
            cv::circle(image, cv::Point(60 + option * 40, 100), 11,
                       cv::Scalar(ink, ink, ink), -1, cv::LINE_AA);
          }
          // Row 2: a lone faint answer must remain readable.
          const int paleInk = cvRound(paper * 0.78);
          cv::circle(image, cv::Point(180, 150), 11,
                     cv::Scalar(paleInk, paleInk, paleInk), -1, cv::LINE_AA);
          // Row 3 remains blank.
          cv::Mat gammaLut(1, 256, CV_8UC1);
          for (int value = 0; value < 256; ++value) {
            gammaLut.at<uint8_t>(0, value) = cv::saturate_cast<uint8_t>(
              255.0 * std::pow(value / 255.0, gamma));
          }
          cv::LUT(image, gammaLut, image);
          if (grayscale) cv::cvtColor(image, image, cv::COLOR_BGR2GRAY);
          if (jpeg) {
            std::vector<uchar> encoded;
            cv::imencode(".jpg", image, encoded, {cv::IMWRITE_JPEG_QUALITY, 82});
            image = cv::imdecode(encoded, cv::IMREAD_UNCHANGED);
          }
          const auto result = omr::AnswerReader::read(image, tpl);
          const std::string context = " paper=" + std::to_string(paper) +
            " gray=" + std::to_string(grayscale) + " jpeg=" + std::to_string(jpeg) +
            " gamma=" + std::to_string(gamma) +
            " darkChoices=" + std::to_string(darkChoices);
          ok &= require(result.questions.size() == 3, "faint-secondary rows returned" + context);
          if (result.questions.size() != 3) continue;
          const auto& first = result.questions[0];
          ok &= require(first.status == "MARKED" && first.hasSelectedOption,
                        "every non-blank row must produce one answer" + context);
          ok &= require(first.selectedOptions.size() == 1,
                        "several fills must never produce multiple answers" + context);
          const auto winner = std::find_if(first.options.begin(), first.options.end(), [&](const auto& option) {
            return option.option == first.selectedOption;
          });
          const double maxFill = std::max_element(first.options.begin(), first.options.end(),
            [](const auto& lhs, const auto& rhs) { return lhs.fillRatio < rhs.fillRatio; })->fillRatio;
          ok &= require(winner != first.options.end() && winner->fillRatio == maxFill,
                        "selected percentage must be the maximum" + context);
          ok &= require(result.questions[1].status == "MARKED" && result.questions[1].selectedOption == "3",
                        "lone faint pencil must remain readable" + context);
          ok &= require(result.questions[2].status == "BLANK", "empty row remains blank" + context);
        }
        }
      }
    }
  }
  return ok;
}

bool testAllAnswerChoicesFilledSelectOne() {
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
    ok &= require(result.questions.front().status == "MARKED", "four filled choices must never collapse to BLANK");
    ok &= require(result.questions.front().selectedOptions.size() == 1, "four filled choices must select only one");
    ok &= require(result.questions.front().selectedOption == "1", "four exact ties must select option 1");
  }
  return ok;
}

bool testHighestPercentageWinsBeforeDarknessTieBreak() {
  const auto tpl = answerTemplate();
  cv::Mat image(300, 360, CV_8UC3, cv::Scalar(245, 245, 245));
  for (const float y : tpl.registration.rowY) {
    for (const auto& [option, x] : tpl.answers.blocks.front().xByOption) {
      (void)option;
      drawPrintedBubble(image, {cvRound(x), cvRound(y)}, 15);
    }
  }
  cv::circle(image, {140, 100}, 11, {180,180,180}, cv::FILLED, cv::LINE_AA);
  cv::ellipse(image, {180,100}, {8,7}, 0, 0, 360, {25,25,25}, cv::FILLED, cv::LINE_AA);
  cv::circle(image, {100, 150}, 11, {180,180,180}, cv::FILLED, cv::LINE_AA);
  cv::circle(image, {220, 150}, 11, {25,25,25}, cv::FILLED, cv::LINE_AA);
  const auto result = omr::AnswerReader::read(image, tpl);
  bool ok = true;
  ok &= require(result.questions.size() == 3, "percentage priority fixture returns all rows");
  if (result.questions.size() != 3) return false;
  const auto& first = result.questions[0];
  const auto& broad = first.options[1];
  const auto& dark = first.options[2];
  ok &= require(broad.fillRatio > dark.fillRatio && broad.inkDarkness < dark.inkDarkness,
                "fixture separates higher percentage from darker partial ink");
  ok &= require(first.status == "MARKED" && first.selectedOption == "2" && first.selectedOptions.size() == 1,
                "higher percentage must win even over darker ink");
  ok &= require(result.questions[1].selectedOption == "4" && result.questions[1].selectedOptions.size() == 1,
                "equal percentages must select the darker fill");
  ok &= require(result.questions[2].status == "BLANK", "empty row must not select its printed glyph");
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

bool testAnswerRankingContract() {
  omr::OmrAnswerOptionScore first;
  first.option = "10";
  first.hasOptionNumber = true;
  first.optionNumber = 10;
  first.fillRatio = 0.9;
  first.meanGray = 100;
  first.inkDarkness = 0.8;
  auto second = first;
  second.option = "2";
  second.optionNumber = 2;
  second.meanGray = 80;
  second.inkDarkness = 0.4;
  bool ok = require(omr::answerOptionRanksBefore(second, first),
    "equal fill must use lower meanGray, even against paper-relative darkness");
  second.meanGray = first.meanGray;
  ok &= require(omr::answerOptionRanksBefore(second, first),
    "equal fill and meanGray must use numeric option order");
  first.fillRatio += 1e-12;
  ok &= require(omr::answerOptionRanksBefore(first, second),
    "even the smallest unequal fill must win before tie-breaks");

  auto tpl = answerTemplate();
  cv::Mat image(300, 360, CV_8UC3, cv::Scalar(245,245,245));
  // Darker paper at the last choice reverses the two darkness measures.
  cv::rectangle(image, {200,70}, {250,125}, {150,150,150}, cv::FILLED);
  cv::circle(image, {100,100}, 11, {100,100,100}, cv::FILLED);
  cv::circle(image, {220,100}, 11, {80,80,80}, cv::FILLED);
  auto result = omr::AnswerReader::read(image, tpl);
  const auto& row = result.questions.front();
  ok &= require(row.options[0].fillRatio == row.options[3].fillRatio &&
    row.options[0].inkDarkness > row.options[3].inkDarkness &&
    row.options[0].meanGray > row.options[3].meanGray,
    "image fixture must distinguish meanGray from relative darkness");
  ok &= require(row.status == "MARKED" && row.selectedOption == "4" &&
    row.selectedOptions.size() == 1 && row.hasSelectedOptionNumber,
    "actual answer classification must follow meanGray tie-break");

  // A permissive template used to allow confidence above .79 for a close pair.
  tpl.answers.thresholds.ambiguousDelta = 0.001f;
  image.setTo(cv::Scalar(245,245,245));
  cv::circle(image, {100,100}, 11, {25,25,25}, cv::FILLED);
  cv::circle(image, {140,100}, 11, {25,25,25}, cv::FILLED);
  cv::rectangle(image, {139,99}, {141,101}, {245,245,245}, cv::FILLED);
  result = omr::AnswerReader::read(image, tpl);
  const auto& close = result.questions.front();
  const double gap = close.options[0].fillRatio - close.options[1].fillRatio;
  ok &= require(gap > 0 && gap < .10, "confidence fixture must have a close, unequal pair");
  ok &= require(close.status == "MARKED" && close.selectedOption == "1" &&
    close.confidence <= .79, "close-fill confidence cap must ignore template gap settings");
  return ok;
}

bool testSaturatedWhiteNeverInventsAnswer() {
  const auto tpl = answerTemplate();
  cv::Mat image(300, 360, CV_8UC3, cv::Scalar(255,255,255));
  for (const auto y : tpl.registration.rowY) {
    for (const auto& [option, x] : tpl.answers.blocks.front().xByOption) {
      (void)option;
      drawPrintedBubble(image, {cvRound(x),cvRound(y)}, 15);
    }
  }
  const auto result = omr::AnswerReader::read(image, tpl);
  bool ok = true;
  ok &= require(omr::AnswerReader::requiresExposureReview(result),
    "clipped white must request exposure review without manufacturing a mark");
  for (const auto& question : result.questions) {
    ok &= require(question.status == "BLANK" && !question.hasSelectedOption &&
      !question.hasSelectedOptionNumber && question.selectedOptions.empty(),
      "saturated white with no surviving ink cannot justify an answer");
  }
  image.setTo(cv::Scalar(245,245,245));
  const auto whitePaper = omr::AnswerReader::read(image, tpl);
  ok &= require(!omr::AnswerReader::requiresExposureReview(whitePaper),
    "ordinary white paper without clipping must not trigger exposure review");
  image.setTo(cv::Scalar(255,255,255));
  cv::circle(image, {140,100}, 11, {80,80,80}, cv::FILLED);
  const auto survivingInk = omr::AnswerReader::read(image, tpl);
  ok &= require(survivingInk.questions.front().status == "MARKED" &&
    survivingInk.questions.front().selectedOption == "2" &&
    omr::AnswerReader::requiresExposureReview(survivingInk),
    "remaining dark evidence must survive even while clipped regions require review");
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

} // namespace

int main() {
  bool ok = true;
  ok &= testAnswersAndUnsafeRefinement();
  ok &= testQuestionLimitFromQrSheetType();
  ok &= testAccurateLargeTranslationRefinementIsAccepted();
  ok &= testSoftShadowEdgeDoesNotCreateInk();
  ok &= testBubbleScaleShadowCannotAutoAccept();
  ok &= testFaintPencilAndBlueInkUnderUnevenLight();
  ok &= testNonBlankPartialFillSelectsHighestPercentage();
  ok &= testEqualStrongMarksSelectOneDeterministicAnswer();
  ok &= testSingleSelectionAcrossInkAndCameraVariants();
  ok &= testAllAnswerChoicesFilledSelectOne();
  ok &= testHighestPercentageWinsBeforeDarknessTieBreak();
  ok &= testAnswerRankingContract();
  ok &= testSaturatedWhiteNeverInventsAnswer();
  ok &= testBroadPurpleFillIsReadable();
  ok &= testAllStudentDigitsFilledStayMultiple();
  ok &= testStudentCodeLeftToRight(10, "1436523750", "legacy ten-column student code");
  ok &= testStudentCodeLeftToRight(5, "01234", "five-column student code");
  ok &= testStudentCodeHorizontalRows();
  ok &= testStudentCodeWithFaintMixedInk();
  if (ok) {
    std::cout << "AnswerReader regression tests passed\n";
    return 0;
  }
  return 1;
}
