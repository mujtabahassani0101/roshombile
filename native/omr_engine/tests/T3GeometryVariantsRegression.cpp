#include <algorithm>
#include <fstream>
#include <iostream>
#include <sstream>
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
std::string readFile(const std::string& path) {
  std::ifstream input(path);
  std::ostringstream out;
  out << input.rdbuf();
  return out.str();
}

void render(const omr::OmrTemplate& tpl, const std::string& path,
            const std::string& payload, int rotation) {
  cv::Mat page(cvRound(tpl.height), cvRound(tpl.width), CV_8UC3, cv::Scalar(245, 245, 245));
  for (const float y : tpl.registration.rowY) {
    for (const float x : {tpl.registration.leftRailX, tpl.registration.rightRailX}) {
      cv::circle(page, {cvRound(x), cvRound(y)}, cvRound(tpl.registration.dotRadius),
        cv::Scalar(0, 0, 0), cv::FILLED, cv::LINE_AA);
    }
  }
  for (const auto& block : tpl.answers.blocks) {
    const float step = block.yByRow[1] - block.yByRow[0];
    for (int row = 0; row < block.questionCount; ++row) {
      const int question = block.questionStart + row;
      const float y = block.yByRow[row];
      if (row % 2 == 0) {
        cv::rectangle(page, {cvRound(block.bbox.x), cvRound(y-step/2)},
          {cvRound(block.bbox.x+block.bbox.width), cvRound(y+step/2)},
          cv::Scalar(75, 0, 182), cv::FILLED);
      }
      for (const auto& [option, x] : block.xByOption) {
        const cv::Point center(cvRound(x), cvRound(y));
        cv::circle(page, center, cvRound(block.bubbleRadius), cv::Scalar(245, 245, 245), cv::FILLED, cv::LINE_AA);
        if (row % 2 != 0) cv::circle(page, center, cvRound(block.bubbleRadius), cv::Scalar(75, 0, 182), 2, cv::LINE_AA);
        if (std::stoi(option) == (question-1)%4+1) {
          cv::circle(page, center, cvRound(block.bubbleRadius*.73f), cv::Scalar(20,20,20), cv::FILLED, cv::LINE_AA);
        }
      }
    }
  }
  if (!payload.empty()) {
    const auto& roi = tpl.barcode.roi;
    ZXing::MultiFormatWriter writer(ZXing::BarcodeFormat::QRCode);
    writer.setMargin(4).setEccLevel(2);
    const auto matrix = writer.encode(payload, cvRound(roi.width), cvRound(roi.height));
    for (int y=0; y<matrix.height(); ++y) {
      for (int x=0; x<matrix.width(); ++x) {
        page.at<cv::Vec3b>(cvRound(roi.y)+y, cvRound(roi.x)+x) =
          matrix.get(x,y) ? cv::Vec3b(0,0,0) : cv::Vec3b(255,255,255);
      }
    }
  }
  if (rotation == 90) cv::rotate(page, page, cv::ROTATE_90_CLOCKWISE);
  if (rotation == 180) cv::rotate(page, page, cv::ROTATE_180);
  if (!cv::imwrite(path, page, {cv::IMWRITE_JPEG_QUALITY,94})) throw std::runtime_error("fixture_write_failed");
}
}

int main() {
  const auto t3 = omr::OmrTemplate::fromJson(readFile(root + "/yse_omr_a5_t3_40q_v1.json"));
  if (t3.geometryVariants.size() != 2) return 2;
  const std::vector<omr::OmrTemplate> layouts{t3, t3.geometryVariants[0], t3.geometryVariants[1]};
  const int positiveCount = static_cast<int>(layouts.size()) * 2;
  const int caseCount = positiveCount + 4;
  const std::vector<std::string> payloads = {"T3:213327:rZeGOHtv", "S2000000000000000000", "", "T4:test"};
  for (int index=0; index<caseCount; ++index) {
    const auto& tpl = layouts[(index/2)%layouts.size()];
    const auto payload = index < positiveCount ? payloads[0] : payloads[index-positiveCount < 2 ? 1 : index-positiveCount];
    render(tpl, root + "/t3-geometry-" + std::to_string(index) + ".jpg", payload,
      index == 1 || index == 5 ? 90 : (index == 3 ? 180 : 0));
  }
  std::ostringstream request;
  request << "{\"templateJsonPaths\":[";
  int templateIndex = 0;
  for (const auto& name : {"yse_omr_a5_t1_100q_v2.json","yse_omr_a5_t2_60q_v1.json","yse_omr_a5_t3_40q_v1.json"}) {
    if (templateIndex++) request << ',';
    request << '"' << root << '/' << name << '"';
  }
  request << "],\"includeQuestions\":true,\"returnNormalizedImages\":false,\"pages\":[";
  for (int index=0; index<caseCount; ++index) {
    if (index) request << ',';
    request << "{\"pageNumber\":" << index+1 << ",\"imagePath\":\"" << root << "/t3-geometry-" << index << ".jpg\"}";
  }
  request << "]}";
  const char* response = omr_mobile_process_json(request.str().c_str());
  const std::string json = response ? response : "";
  omr_mobile_free_string(response);
  std::ofstream(root + "/t3-geometry-response.json") << json;
  const auto sheets = omr::json::objectArrayForKey(json,"sheets");
  bool ok = sheets.size() == static_cast<std::size_t>(caseCount);
  for (std::size_t index=0; index<sheets.size(); ++index) {
    const auto& sheet = sheets[index];
    const auto questions = omr::json::objectArrayForKey(sheet,"questions");
    if (index >= static_cast<std::size_t>(positiveCount)) {
      if (omr::json::boolForKey(sheet,"success").value_or(true) || !questions.empty()) {
        std::cerr << "FAIL: non-T3 credential produced observations, case " << index << '\n';
        ok = false;
      }
      continue;
    }
    const auto warnings = omr::json::stringArrayForKey(sheet,"warnings");
    const std::string expectedGeometry = layouts[index/2].geometryId;
    if (!omr::json::boolForKey(sheet,"success").value_or(false) || questions.size()!=40 ||
        std::find(warnings.begin(),warnings.end(),"sheet_geometry_variant:"+expectedGeometry)==warnings.end()) {
      std::cerr << "FAIL: T3 geometry selection, case " << index << '\n';
      ok = false;
    }
    for (std::size_t q=0; q<questions.size(); ++q) {
      if (omr::json::intForKey(questions[q],"question").value_or(0)!=static_cast<int>(q)+1 ||
          omr::json::intForKey(questions[q],"selectedOptionNumber").value_or(0)!=static_cast<int>(q)%4+1) {
        std::cerr << "FAIL: T3 case " << index << " question " << q+1 << '\n';
        ok = false;
      }
    }
  }
  // The production path reuses its selected geometry probe. The persisted
  // diagnostic path performs a fresh normalization. Both must observe exactly
  // the same answers, confidence and fill ratios on each physical T3 layout.
  for (const int index : {0, 2, 4}) {
    std::ostringstream fullRequest;
    fullRequest << "{\"templateJsonPath\":\"" << root
      << "/yse_omr_a5_t3_40q_v1.json\",\"includeQuestions\":true,"
      << "\"returnNormalizedImages\":true,\"outputDirectory\":\"" << root
      << "\",\"pages\":[{\"pageNumber\":1,\"imagePath\":\"" << root
      << "/t3-geometry-" << index << ".jpg\"}]}";
    const char* fullResponse = omr_mobile_process_json(fullRequest.str().c_str());
    const std::string fullJson = fullResponse ? fullResponse : "";
    omr_mobile_free_string(fullResponse);
    const auto fullSheets = omr::json::objectArrayForKey(fullJson, "sheets");
    if (sheets.size() <= static_cast<std::size_t>(index) || fullSheets.size() != 1 ||
        !omr::json::boolForKey(fullSheets.front(), "success").value_or(false) ||
        omr::json::objectArrayForKey(fullSheets.front(), "questions") !=
          omr::json::objectArrayForKey(sheets[index], "questions")) {
      std::cerr << "FAIL: reused geometry differs from full read, case " << index << '\n';
      ok = false;
    }
  }
  std::cout << (ok ? "PASS" : "FAIL") << ": compact mixed-case QR on all three T3 geometries, rotations, 240 exact choices; S2, missing and unknown prefixes rejected\n";
  return ok ? 0 : 1;
}
