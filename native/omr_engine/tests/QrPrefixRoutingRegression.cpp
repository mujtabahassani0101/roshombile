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
}

int main() {
  const std::vector<std::string> names = {"yse_omr_a5_t1_100q_v2.json",
    "yse_omr_a5_t2_60q_v1.json", "yse_omr_a5_t3_40q_v1.json"};
  const std::vector<std::string> payloads = {"S2000000000000000000",
    "C2000000000000000000", "T4:123:test", "t1:123:test", "unsupported",
    "T1" + std::string(90, 'X'), "", "T1", "T1|001234|S2000000000000000000",
    "T1:001234:S2000000000000000000:extra", "T1:001234:S2000000000000000000 ",
    "T1:001234:C2000000000000000000", "T10:001234:S2000000000000000000",
    "T1:001234:s2000000000000000000", "T1:",
    "S2000000000000000000", "S2000000000000000000"};
  const auto primaryCases = payloads.size() - 2;
  for (std::size_t index = 0; index < payloads.size(); ++index) {
    const auto tpl = omr::OmrTemplate::fromJson(readFile(root + '/' + names[index < primaryCases ? 0 : index-primaryCases+1]));
    cv::Mat page(cvRound(tpl.height), cvRound(tpl.width), CV_8UC3, cv::Scalar(245, 245, 245));
    for (const bool left : {true, false}) {
      const float x = left ? tpl.registration.leftRailX : tpl.registration.rightRailX;
      for (const float y : tpl.registration.rowsForSide(left)) {
        cv::circle(page, {cvRound(x), cvRound(y)}, cvRound(tpl.registration.dotRadius),
          cv::Scalar(0, 0, 0), cv::FILLED, cv::LINE_AA);
      }
    }
    if (!payloads[index].empty()) {
      const auto& r = tpl.barcode.roi;
      ZXing::MultiFormatWriter writer(ZXing::BarcodeFormat::QRCode);
      writer.setMargin(4).setEccLevel(2);
      const auto matrix = writer.encode(payloads[index], cvRound(r.width), cvRound(r.height));
      for (int y = 0; y < matrix.height(); ++y) {
        for (int x = 0; x < matrix.width(); ++x) {
          page.at<cv::Vec3b>(cvRound(r.y) + y, cvRound(r.x) + x) =
            matrix.get(x, y) ? cv::Vec3b(0, 0, 0) : cv::Vec3b(255, 255, 255);
        }
      }
    }
    if (!cv::imwrite(root + "/prefix-negative-" + std::to_string(index) + ".png", page)) return 2;
  }
  bool ok = true;
  for (const bool multiTemplate : {false, true}) {
    const std::size_t caseCount = multiTemplate ? payloads.size() : primaryCases;
    std::ostringstream request;
    request << "{\"templateJsonPaths\":[";
    for (int index = 0; index < (multiTemplate ? 3 : 1); ++index) {
      if (index) request << ',';
      request << '"' << root << '/' << names[index] << '"';
    }
    request << "],\"includeQuestions\":true,\"returnNormalizedImages\":false,\"pages\":[";
    for (std::size_t index = 0; index < caseCount; ++index) {
      if (index) request << ',';
      request << "{\"pageNumber\":" << index+1 << ",\"imagePath\":\"" << root
        << "/prefix-negative-" << index << ".png\"}";
    }
    request << "]}";
    const char* response = omr_mobile_process_json(request.str().c_str());
    const std::string json = response ? response : "";
    omr_mobile_free_string(response);
    const auto sheets = omr::json::objectArrayForKey(json, "sheets");
    ok = ok && sheets.size() == caseCount;
    for (std::size_t index = 0; index < sheets.size(); ++index) {
      const auto& sheet = sheets[index];
      const bool rejected = !omr::json::boolForKey(sheet, "success").value_or(true) &&
        omr::json::objectArrayForKey(sheet, "questions").empty();
      const auto error = omr::json::stringForKey(sheet, "error").value_or("");
      const bool diagnostic = index == 5 || index == 6 ||
        (omr::json::stringForKey(sheet, "status").value_or("") == "UNSUPPORTED_SHEET_TYPE" &&
         error == "sheet_type_qr_unsupported_prefix") ||
        (payloads[index].rfind("T1:", 0) == 0 &&
         omr::json::stringForKey(sheet, "status").value_or("") == "SHEET_TYPE_REQUIRED" &&
         error.rfind("sheet_type_qr_missing_or_invalid", 0) == 0);
      if (!rejected || !diagnostic) {
        std::cerr << "FAIL: prefix case " << index << " multiTemplate=" << multiTemplate << '\n';
        ok = false;
      }
    }
  }
  std::cout << (ok ? "PASS" : "FAIL") << ": strict full QR contract rejects bare prefixes, bad separators/fields, S2, C2, T4, lowercase, invalid-length and missing QR; no geometry fallback\n";
  return ok ? 0 : 1;
}
