#include <cmath>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include "omr/MobileOmrC.h"
#include "omr/OmrTemplate.hpp"
#include "utils/Json.hpp"
#include "BarcodeFormat.h"
#include "BitMatrix.h"
#include "MultiFormatWriter.h"

namespace {
const std::string root = "/data/local/tmp/omr-bench";
const std::vector<std::string> names = {
  "yse_omr_a5_t1_100q_v2.json", "yse_omr_a5_t2_60q_v1.json", "yse_omr_a5_t3_40q_v1.json"
};

std::string readFile(const std::string& path) {
  std::ifstream input(path);
  std::ostringstream data;
  data << input.rdbuf();
  return data.str();
}

bool require(bool value, const std::string& message) {
  if (!value) std::cerr << "FAIL: " << message << '\n';
  return value;
}

void renderSheet(const omr::OmrTemplate& tpl, int index, bool prefixOnly) {
  cv::Mat page(cvRound(tpl.height), cvRound(tpl.width), CV_8UC3, cv::Scalar(245, 245, 245));
  for (const bool left : {true, false}) {
    const float x = left ? tpl.registration.leftRailX : tpl.registration.rightRailX;
    for (const auto y : tpl.registration.rowsForSide(left)) {
      cv::circle(page, {cvRound(x), cvRound(y)}, cvRound(tpl.registration.dotRadius),
                 cv::Scalar(0, 0, 0), cv::FILLED, cv::LINE_AA);
    }
  }
  for (const auto& block : tpl.answers.blocks) {
    for (int row = 0; row < block.questionCount; ++row) {
      const int question = block.questionStart + row;
      for (const auto& option : block.xByOption) {
        const cv::Point center(cvRound(option.second), cvRound(block.yByRow[row]));
        cv::circle(page, center, cvRound(block.bubbleRadius), cv::Scalar(75, 3, 182), 2, cv::LINE_AA);
        if (std::stoi(option.first) == (question - 1) % 4 + 1) {
          // Marks scale with each physical template's bubble size. T2/T3
          // circles are larger than T1; a fixed 11px dot is an underfilled mark.
          cv::circle(page, center, cvRound(block.bubbleRadius * 0.70f),
                     cv::Scalar(24, 24, 24), cv::FILLED, cv::LINE_AA);
        }
      }
    }
  }
  const auto& r = tpl.barcode.roi;
  const cv::Rect roi(cvRound(r.x), cvRound(r.y), cvRound(r.x + r.width) - cvRound(r.x),
                     cvRound(r.y + r.height) - cvRound(r.y));
  const std::string payload = prefixOnly
    ? tpl.sheetTypeCode
    : (index == 2 ? "T3:213327:rZeGOHtv" : tpl.sheetTypeCode + ":001234:S2000000000000000000");
  ZXing::MultiFormatWriter writer(ZXing::BarcodeFormat::QRCode);
  writer.setMargin(4).setEccLevel(2);
  const auto matrix = writer.encode(payload, roi.width, roi.height);
  cv::Mat qr(roi.height, roi.width, CV_8UC3, cv::Scalar(255, 255, 255));
  for (int y = 0; y < matrix.height(); ++y) {
    for (int x = 0; x < matrix.width(); ++x) {
      const unsigned char pixel = matrix.get(x, y) ? 0 : 255;
      qr.at<cv::Vec3b>(y, x) = cv::Vec3b(pixel, pixel, pixel);
    }
  }
  qr.copyTo(page(roi));
  // Exercise mobile orientation along with mixed template selection.
  if (index == 1) cv::rotate(page, page, cv::ROTATE_90_CLOCKWISE);
  if (index == 2) cv::rotate(page, page, cv::ROTATE_180);
  if (!cv::imwrite(root + "/r11-T" + std::to_string(index + 1) + ".jpg", page,
                   {cv::IMWRITE_JPEG_QUALITY, 94})) {
    throw std::runtime_error("cannot write synthetic sheet");
  }
}
}

int main(int argc, char** argv) {
  const bool prefixOnly = argc > 1 && std::string(argv[1]) == "--prefix-only";
  bool ok = require(omr_mobile_api_version() == 2, "API version");
  ok &= require(!omr::json::topLevelStringForKey(
    "{\"pages\":[{\"imagePath\":\"one.jpg\"},{\"imagePath\":\"two.jpg\"}]}", "imagePath").has_value(),
    "nested page paths must not enable single-image mode");
  ok &= require(omr::json::topLevelStringForKey(
    "{\"metadata\":{\"imagePath\":\"wrong.jpg\"},\"imagePath\":\"right.jpg\"}", "imagePath").value_or("") == "right.jpg",
    "root single-image path must remain supported");
  ok &= require(std::string(omr_mobile_engine_revision()) == "omr-mobile-api-v2-four-engines-r26-highest-fill", "four-engine shared library revision");
  for (int index = 0; index < 3; ++index) {
    renderSheet(omr::OmrTemplate::fromJson(readFile(root + "/" + names[index])), index, prefixOnly);
  }
  std::ostringstream request;
  request << "{\"templateJsonPaths\":[";
  for (int index = 0; index < 3; ++index) {
    if (index) request << ',';
    request << '"' << root << '/' << names[index] << '"';
  }
  request << "],\"returnDebugImages\":false,\"returnNormalizedImages\":false,"
             "\"compactResult\":false,\"includeQuestions\":true,\"maxInputDimension\":2600,\"pages\":[";
  for (int index = 0; index < 4; ++index) {
    if (index) request << ',';
    request << "{\"pageNumber\":" << index + 1 << ",\"imagePath\":\"" << root << '/'
            << (index < 3 ? "r11-T" + std::to_string(index + 1) + ".jpg" : "missing.jpg") << "\"}";
  }
  request << "]}";
  const char* response = omr_mobile_process_json(request.str().c_str());
  const std::string json = response == nullptr ? "" : response;
  omr_mobile_free_string(response);
  std::ofstream(root + "/r11-packaged-response.json") << json;
  const auto sheets = omr::json::objectArrayForKey(json, "sheets");
  ok &= require(sheets.size() == 4, "all three templates and failed page must be returned");
  const std::vector<int> counts = {100, 60, 40};
  for (int index = 0; index < 3 && index < static_cast<int>(sheets.size()); ++index) {
    const auto& sheet = sheets[index];
    const std::string label = "T" + std::to_string(index + 1);
    if (prefixOnly) {
      ok &= require(!omr::json::boolForKey(sheet, "success").value_or(true), label + " incomplete QR rejected");
      ok &= require(omr::json::objectArrayForKey(sheet, "questions").empty(), label + " incomplete QR has no answers");
      continue;
    }
    ok &= require(omr::json::boolForKey(sheet, "success").value_or(false), label + " normalization");
    ok &= require(omr::json::intForKey(sheet, "activeQuestionCount").value_or(0) == counts[index], label + " active count");
    const auto questions = omr::json::objectArrayForKey(sheet, "questions");
    ok &= require(questions.size() == static_cast<size_t>(counts[index]), label + " exact observation count");
    for (int q = 0; q < static_cast<int>(questions.size()); ++q) {
      ok &= require(omr::json::intForKey(questions[q], "question").value_or(0) == q + 1 &&
          omr::json::intForKey(questions[q], "selectedOptionNumber").value_or(0) == q % 4 + 1,
          label + " question " + std::to_string(q + 1));
    }
  }
  if (sheets.size() == 4) {
    ok &= require(!omr::json::boolForKey(sheets[3], "success").value_or(true), "failed page retained");
    ok &= require(omr::json::objectArrayForKey(sheets[3], "questions").empty(), "failed page has no fabricated answers");
  }
  std::cout << (ok ? "PASS" : "FAIL") << ": r11 packaged C ABI, 100 + 60 + 40 exact choices, rotated sheets, failed page retained\n";
  return ok ? 0 : 1;
}
