#include <cmath>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <regex>
#include <sstream>
#include <string>
#include <vector>

#include "omr/OmrTemplate.hpp"
#include "utils/Json.hpp"

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

std::string replaceOnce(std::string value, const std::string& from, const std::string& to) {
  const std::size_t position = value.find(from);
  if (position != std::string::npos) {
    value.replace(position, from.size(), to);
  }
  return value;
}

bool rejectsTemplate(const std::string& json, const std::string& message) {
  try {
    (void)omr::OmrTemplate::fromJson(json);
  } catch (const std::exception&) {
    return true;
  }
  std::cerr << "FAIL: " << message << '\n';
  return false;
}

bool validateCommon(
  const omr::OmrTemplate& tpl,
  const std::string& templateId,
  const std::string& sheetType,
  int questionCount,
  int expectedDotsPerSide,
  float leftRailX,
  float rightRailX,
  float firstAnswerY,
  float lastAnswerY
) {
  bool ok = true;
  ok &= require(tpl.templateId == templateId, sheetType + " template id must match");
  ok &= require(tpl.sheetTypeCode == sheetType, sheetType + " sheet binding must match");
  ok &= require(tpl.sheetTypeQuestionCount == questionCount, sheetType + " question count metadata must match");
  ok &= require(near(tpl.width, 1750.0f) && near(tpl.height, 2480.0f), sheetType + " must use the A5 300-DPI reference render");
  ok &= require(near(tpl.registration.leftRailX, leftRailX), sheetType + " left rail x must match the source PDF");
  ok &= require(near(tpl.registration.rightRailX, rightRailX), sheetType + " right rail x must match the source PDF");
  ok &= require(tpl.registration.expectedDotsPerSide == expectedDotsPerSide, sheetType + " rail-dot count must match");
  ok &= require(static_cast<int>(tpl.registration.rowY.size()) == expectedDotsPerSide, sheetType + " must contain every rail row");
  ok &= require(near(tpl.registration.rowY.front(), 177.924f), sheetType + " first header rail row must match");
  ok &= require(near(tpl.registration.rowY[3], 388.963f), sheetType + " fourth header rail row must match");
  ok &= require(near(tpl.registration.rowY[4], firstAnswerY), sheetType + " first answer rail row must match");
  ok &= require(near(tpl.registration.rowY.back(), lastAnswerY), sheetType + " last answer rail row must match");
  ok &= require(firstAnswerY > tpl.answersRegion.y + 40.0f,
    sheetType + " first answer row must clear the header/identity reprojection guard");
  ok &= require(!tpl.studentCode.enabled && tpl.studentCode.columns == 0, sheetType + " must not invent a student-code grid");
  ok &= require(tpl.answers.questionCount == questionCount, sheetType + " answer count must match");
  ok &= require(tpl.barcode.qrCodeOnly && tpl.barcode.required, sheetType + " QR must be required");
  ok &= require(
    tpl.examCodeBox.x <= tpl.barcode.roi.x &&
    tpl.examCodeBox.y <= tpl.barcode.roi.y &&
    tpl.examCodeBox.x + tpl.examCodeBox.width >= tpl.barcode.roi.x + tpl.barcode.roi.width &&
    tpl.examCodeBox.y + tpl.examCodeBox.height >= tpl.barcode.roi.y + tpl.barcode.roi.height,
    sheetType + " raw QR search box must contain its normalized QR ROI"
  );
  ok &= require(
    std::regex_match(sheetType + ":001234:S2000000000000000000", std::regex(tpl.barcode.expectedPayloadRegex)),
    sheetType + " QR contract must accept its complete uppercase credential"
  );
  ok &= require(
    std::regex_match(sheetType + ":213327:rZeGOHtv", std::regex(tpl.barcode.expectedPayloadRegex)),
    sheetType + " QR contract must accept its complete compact credential"
  );
  ok &= require(
    !std::regex_match(std::string("S2XMHKMQ7J33Y0NDB1XN"), std::regex(tpl.barcode.expectedPayloadRegex)),
    sheetType + " must reject the non-production sample QR prefix"
  );
  return ok;
}

bool validateBlocks(
  const omr::OmrTemplate& tpl,
  const std::vector<int>& starts,
  int rowsPerBlock,
  float firstAnswerY,
  float lastAnswerY,
  int expectedBubbleCount
) {
  bool ok = true;
  ok &= require(tpl.answers.blocks.size() == starts.size(), tpl.sheetTypeCode + " block count must match");
  int bubbleCount = 0;
  for (std::size_t i = 0; i < tpl.answers.blocks.size(); ++i) {
    const auto& block = tpl.answers.blocks[i];
    bubbleCount += block.questionCount * static_cast<int>(block.xByOption.size());
    ok &= require(block.questionStart == starts[i], tpl.sheetTypeCode + " physical block order must match");
    ok &= require(block.questionCount == rowsPerBlock, tpl.sheetTypeCode + " rows per block must match");
    ok &= require(static_cast<int>(block.yByRow.size()) == rowsPerBlock, tpl.sheetTypeCode + " y-row count must match");
    ok &= require(near(block.yByRow.front(), firstAnswerY), tpl.sheetTypeCode + " first bubble row must match");
    ok &= require(near(block.yByRow.back(), lastAnswerY), tpl.sheetTypeCode + " last bubble row must match");
  }
  ok &= require(bubbleCount == expectedBubbleCount, tpl.sheetTypeCode + " calibrated bubble count must match");
  return ok;
}

} // namespace

int main() {
  bool ok = true;

  const omr::OmrTemplate t1 = omr::OmrTemplate::fromJson(readTextFile(OMR_TEST_TEMPLATE_PATH));
  ok &= validateCommon(t1, "yse-omr-a5-t1-100q-v2", "T1", 100, 29, 96.26971f, 1650.498935f, 606.057f, 2277.655f);
  ok &= validateBlocks(t1, {76, 51, 26, 1}, 25, 606.057f, 2277.655f, 400);
  if (t1.answers.blocks.size() == 4) {
    ok &= require(
      near(t1.answers.blocks[0].xByOption.at("4"), 258.650f) &&
      near(t1.answers.blocks[3].xByOption.at("1"), 1438.642f),
      "T1 outer answer bubble centers must match the source PDF"
    );
  }

  const omr::OmrTemplate t2 = omr::OmrTemplate::fromJson(readTextFile(OMR_TEST_T2_TEMPLATE_PATH));
  ok &= validateCommon(t2, "yse-omr-a5-t2-60q-v1", "T2", 60, 24, 99.166767f, 1650.833538f, 607.062f, 2276.688f);
  ok &= validateBlocks(t2, {41, 21, 1}, 20, 607.062f, 2276.688f, 240);
  if (t2.answers.blocks.size() == 3) {
    ok &= require(
      near(t2.answers.blocks[0].xByOption.at("4"), 281.25f) &&
      near(t2.answers.blocks[2].xByOption.at("1"), 1397.5f),
      "T2 outer answer bubble centers must match the current 60-question PDF"
    );
  }
  const std::string t2Json = readTextFile(OMR_TEST_T2_TEMPLATE_PATH);
  const auto& rightRows = t2.registration.rowsForSide(false);
  ok &= require(rightRows.size() == 24 && near(rightRows[4], 590.3958f) &&
    near(rightRows.back(), 2260.0208f) && near(rightRows.front(), t2.registration.rowY.front()),
    "T2 must use its independently measured right answer dots without shifting header dots");
  const auto rightStart = t2Json.find('[', t2Json.find("\"rightRowY\""));
  const auto rightEnd = t2Json.find(']', rightStart);
  const auto withRightRows = [&](const std::string& rows) {
    return t2Json.substr(0, rightStart) + rows + t2Json.substr(rightEnd+1);
  };
  ok &= rejectsTemplate(withRightRows("[100,200,300]"), "truncated right rail must be rejected");
  auto badRight = rightRows;
  badRight[5] = badRight[4];
  std::ostringstream invalidRows;
  invalidRows << '[';
  for (std::size_t i=0; i<badRight.size(); ++i) {
    if (i) invalidRows << ',';
    invalidRows << badRight[i];
  }
  invalidRows << ']';
  ok &= rejectsTemplate(withRightRows(invalidRows.str()), "duplicate right rail rows must be rejected");
  ok &= require(t2.geometryVariants.front().registration.rightRowY.empty(),
    "legacy symmetric layouts must retain their original shared rail profile");
  ok &= rejectsTemplate("", "an empty template must be rejected");
  ok &= rejectsTemplate(
    replaceOnce(t2Json, "\"expectedDotsPerSide\": 24", "\"expectedDotsPerSide\": 23"),
    "a rail row/count mismatch must be rejected"
  );
  ok &= rejectsTemplate(
    replaceOnce(t2Json, "\"questionStart\": 21", "\"questionStart\": 41"),
    "overlapping answer blocks and uncovered questions must be rejected"
  );
  const omr::OmrTemplate thresholdTemplate = omr::OmrTemplate::fromJson(
    replaceOnce(t2Json, "\"minBrightness\": 0.28", "\"minBrightness\": 0.31")
  );
  ok &= require(
    near(omr::OmrTemplate::optionsFromJson("", thresholdTemplate).minBrightness, 0.31f),
    "live thresholds embedded in the physical template must be honored"
  );
  ok &= require(
    near(
      omr::OmrTemplate::optionsFromJson("{\"minBrightness\":0.33}", thresholdTemplate).minBrightness,
      0.33f
    ),
    "explicit runtime options must override embedded template thresholds"
  );

  const omr::OmrTemplate t3 = omr::OmrTemplate::fromJson(readTextFile(OMR_TEST_T3_TEMPLATE_PATH));
  ok &= validateCommon(t3, "yse-omr-a5-t3-40q-v1", "T3", 40, 24, 99.166567f, 1650.833333f, 679.162f, 2262.297f);
  ok &= validateBlocks(t3, {21, 1}, 20, 679.162f, 2262.297f, 160);
  if (t3.answers.blocks.size() == 2) {
    ok &= require(
      near(t3.answers.blocks[0].xByOption.at("4"), 502.917f) &&
      near(t3.answers.blocks[1].xByOption.at("1"), 1167.917f),
      "T3 outer answer bubble centers must match the current 40-question PDF"
    );
  }
  for (const auto* tpl : {&t1, &t2, &t3}) {
    std::string lower = tpl->sheetTypeCode;
    lower[0] = 't';
    ok &= require(tpl->geometryId == lower + "-header-20261020", "the new printed sheet must be primary");
    ok &= require(!tpl->geometryVariants.empty() &&
      tpl->geometryVariants.front().geometryId == lower + "-legacy-header" &&
      near(tpl->geometryVariants.front().registration.rowY.front(), 209.438f),
      "previously printed sheets must retain their original header coordinates");
  }
  ok &= require(t1.geometryVariants.size() == 1 && t2.geometryVariants.size() == 1 && t3.geometryVariants.size() == 2,
    "retain one old T1/T2 layout and both old T3 layouts");
  if (t3.geometryVariants.size() == 2) {
    const auto& variant = t3.geometryVariants.back();
    ok &= require(variant.geometryId == "t3-2x20-2026" && variant.geometryVariants.empty(),
      "T3 variant must have a stable unique identity without nesting");
    ok &= require(variant.templateId == t3.templateId && variant.sheetTypeCode == "T3" &&
      variant.answers.questionCount == 40 && variant.answers.options == t3.answers.options,
      "geometry changes must preserve the QR family and all 40 question identities");
    ok &= require(near(variant.registration.dotRadius, 25.0f) &&
      near(variant.registration.rowY[4], 706.354167f) &&
      near(variant.registration.rowY.back(), 2372.8125f), "new rail centers must match the 2x20 PDF");
    ok &= require(near(variant.answers.blocks[0].xByOption.at("4"), 483.333333f) &&
      near(variant.answers.blocks[1].xByOption.at("1"), 1183.333333f) &&
      near(variant.answers.blocks[0].bubbleRadius, 25.0f), "new answer centers and radii must match the 2x20 PDF");
    ok &= require(variant.barcode.expectedPayloadRegex == t3.barcode.expectedPayloadRegex &&
      variant.barcode.required && !variant.studentCode.enabled,
      "variant geometry must preserve strict QR acceptance and disabled identity bubbles");
  }
  const auto t3Json = readTextFile(OMR_TEST_T3_TEMPLATE_PATH);
  ok &= rejectsTemplate(replaceOnce(t3Json, "\"geometryId\": \"t3-2x20-2026\"", "\"geometryId\": \"\""),
    "an unnamed geometry variant must be rejected");
  const auto variantObjects = omr::json::objectArrayForKey(t3Json, "geometryVariants");
  if (!variantObjects.empty()) {
    const auto& original = variantObjects.front();
    const auto replaceVariant = [&](const std::string& modified) {
      return replaceOnce(t3Json, original, modified);
    };
    ok &= rejectsTemplate(replaceVariant(replaceOnce(original, "\"code\": \"T3\"", "\"code\": \"T2\"")),
      "a geometry variant cannot select a different QR family");
    ok &= rejectsTemplate(replaceVariant(original.substr(0, original.size()-1) + ",\"geometryVariants\":[{}]}"),
      "recursive geometry variants must be rejected before recursive parsing");
    const auto weakenedJson = replaceOnce(original,
      "\"expectedPayloadRegex\": \"" + t3.barcode.expectedPayloadRegex + "\"", "\"expectedPayloadRegex\": \"^.*$\"");
    ok &= require(weakenedJson != original, "the fixture must actually attempt to weaken variant QR acceptance");
    const auto weakened = omr::OmrTemplate::fromJson(replaceVariant(weakenedJson));
    ok &= require(weakened.geometryVariants.front().barcode.expectedPayloadRegex == t3.barcode.expectedPayloadRegex,
      "a geometry variant cannot weaken credential acceptance");
  }

  return ok ? EXIT_SUCCESS : EXIT_FAILURE;
}
