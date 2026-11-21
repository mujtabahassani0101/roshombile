#include <cmath>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <regex>
#include <sstream>
#include <string>

#include "omr/OmrTemplate.hpp"

namespace {

bool near(float actual, float expected, float tolerance = 0.01f) {
  return std::abs(actual - expected) <= tolerance;
}

bool require(bool condition, const std::string& message) {
  if (!condition) {
    std::cerr << "FAIL: " << message << '\n';
  }
  return condition;
}

std::string readTextFile(const std::string& path) {
  std::ifstream input(path);
  std::ostringstream contents;
  contents << input.rdbuf();
  return contents.str();
}

} // namespace

int main() {
  const omr::OmrTemplate tpl = omr::OmrTemplate::fromJson(readTextFile(OMR_TEST_TEMPLATE_PATH));
  bool ok = true;

  ok &= require(near(tpl.width, 1653.0f) && near(tpl.height, 2339.0f), "reference render must match the supplied 200-DPI PDF");
  ok &= require(near(tpl.registration.leftRailX, 68.0f), "left rail x must match the supplied PDF");
  ok &= require(near(tpl.registration.rightRailX, 1585.0f), "right rail x must match the supplied PDF");
  ok &= require(tpl.registration.expectedDotsPerSide == 43, "current sheet must have 43 dots per rail");
  ok &= require(tpl.registration.rowY.size() == 43, "registration template must contain all 43 rail rows");
  ok &= require(near(tpl.registration.rowY.front(), 104.167f), "first rail row must match the PDF");
  ok &= require(near(tpl.registration.rowY[7], 551.389f), "first student-code rail row must match the PDF");
  ok &= require(near(tpl.registration.rowY[12], 745.833f), "sixth student-code rail row must match the PDF");
  ok &= require(near(tpl.registration.rowY[13], 896.0f), "first answer rail row must match the PDF");
  ok &= require(near(tpl.registration.rowY.back(), 2104.0f), "last answer rail row must match the PDF");

  ok &= require(tpl.studentCode.usesHorizontalRows(), "student code must use six horizontal rows");
  ok &= require(tpl.studentCode.columns == 6, "student code must contain six logical positions");
  ok &= require(tpl.studentCode.digitOptionsLeftToRight.size() == 10, "each student row must contain ten digits");
  ok &= require(
    tpl.studentCode.digitOptionsLeftToRight.front() == "9" &&
    tpl.studentCode.digitOptionsLeftToRight.back() == "0",
    "student digits must run from 9 at the left to 0 at the right"
  );
  const omr::Point firstStudentBubble = tpl.studentCode.bubbleCenter(0, 0);
  const omr::Point lastStudentBubble = tpl.studentCode.bubbleCenter(5, 9);
  ok &= require(
    near(firstStudentBubble.x, 626.389f) && near(firstStudentBubble.y, 551.389f),
    "top-left student bubble must match the PDF"
  );
  ok &= require(
    near(lastStudentBubble.x, 1026.389f) && near(lastStudentBubble.y, 745.833f),
    "bottom-right student bubble must match the PDF"
  );

  ok &= require(tpl.answers.questionCount == 120, "template must expose 120 questions");
  ok &= require(tpl.answers.blocks.size() == 4, "template must expose four answer blocks");
  int answerBubbleCount = 0;
  for (const auto& block : tpl.answers.blocks) {
    answerBubbleCount += block.questionCount * static_cast<int>(block.xByOption.size());
    ok &= require(block.yByRow.size() == 30, "each answer block must contain 30 calibrated rows");
    ok &= require(near(block.yByRow.front(), 896.0f), "first bubble row must match the PDF");
    ok &= require(near(block.yByRow.back(), 2104.0f), "last bubble row must match the PDF");
  }
  ok &= require(answerBubbleCount == 480, "template must contain all 480 answer bubbles");

  ok &= require(tpl.barcode.qrCodeOnly && tpl.barcode.required, "exam code must be a required QR code");
  ok &= require(
    tpl.barcode.roi.x <= 222.0f && tpl.barcode.roi.y <= 119.0f &&
    tpl.barcode.roi.x + tpl.barcode.roi.width >= 414.0f &&
    tpl.barcode.roi.y + tpl.barcode.roi.height >= 311.0f,
    "QR ROI must contain the complete QR symbol from the supplied PDF"
  );
  ok &= require(
    std::regex_match(std::string("Hello :)"), std::regex(tpl.barcode.expectedPayloadRegex)),
    "QR validation must accept the payload encoded in the supplied PDF"
  );

  return ok ? EXIT_SUCCESS : EXIT_FAILURE;
}
