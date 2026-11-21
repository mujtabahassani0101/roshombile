#include <opencv2/core.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <cstdint>
#include <algorithm>
#include <cmath>
#include <string>
#include <vector>
#include "omr/BarcodeReader.hpp"
#include "omr/OmrLiveResult.hpp"
#include "omr/OmrTemplate.hpp"

#include <iostream>
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

bool require(bool condition, const std::string& message) {
  if (!condition) {
    std::cerr << "FAIL: " << message << '\n';
  }
  return condition;
}

void printFailureResult(const omr::OmrBarcodeResult& result) {
  if (!result.found) {
    std::cerr << "  status=" << result.status << " error=" << result.error
              << " text=" << result.text << '\n';
  }
}

cv::Mat codeCanvas(
  ZXing::BarcodeFormat format,
  const std::string& payload,
  const cv::Rect& roi,
  const cv::Size& canvasSize
) {
  ZXing::MultiFormatWriter writer(format);
  writer.setMargin(4).setEccLevel(2);
  ZXing::BitMatrix matrix = writer.encode(payload, roi.width, roi.height);

  cv::Mat canvas(canvasSize, CV_8UC1, cv::Scalar(255));
  cv::Mat qr(roi.height, roi.width, CV_8UC1, cv::Scalar(255));
  for (int y = 0; y < matrix.height(); ++y) {
    for (int x = 0; x < matrix.width(); ++x) {
      qr.at<std::uint8_t>(y, x) = matrix.get(x, y) ? 0 : 255;
    }
  }
  qr.copyTo(canvas(roi));
  return canvas;
}

cv::Mat cameraDegradedQr(
  const std::string& payload,
  const cv::Rect& roi,
  const cv::Size& canvasSize,
  int reducedSide
) {
  cv::Mat canvas = codeCanvas(ZXing::BarcodeFormat::QRCode, payload, roi, canvasSize);
  cv::Mat qr = canvas(roi).clone();
  cv::Mat reduced;
  cv::resize(qr, reduced, cv::Size(reducedSide, reducedSide), 0.0, 0.0, cv::INTER_AREA);
  cv::resize(reduced, qr, roi.size(), 0.0, 0.0, cv::INTER_LINEAR);

  for (int y = 0; y < qr.rows; ++y) {
    uint8_t* row = qr.ptr<uint8_t>(y);
    for (int x = 0; x < qr.cols; ++x) {
      const double illumination = 0.68 + 0.27 * static_cast<double>(x) / std::max(1, qr.cols - 1);
      row[x] = static_cast<uint8_t>(std::clamp(
        cvRound(36.0 + illumination * static_cast<double>(row[x])),
        0,
        255
      ));
    }
  }
  cv::GaussianBlur(qr, qr, cv::Size(3, 3), 0.70);

  std::vector<uint8_t> encoded;
  cv::imencode(".jpg", qr, encoded, {cv::IMWRITE_JPEG_QUALITY, 68});
  qr = cv::imdecode(encoded, cv::IMREAD_GRAYSCALE);
  qr.copyTo(canvas(roi));
  return canvas;
}

cv::Mat unevenLowContrastQr(
  const std::string& payload,
  const cv::Rect& roi,
  const cv::Size& canvasSize
) {
  cv::Mat canvas = codeCanvas(ZXing::BarcodeFormat::QRCode, payload, roi, canvasSize);
  cv::Mat qr = canvas(roi).clone();
  cv::Mat reduced;
  cv::resize(qr, reduced, cv::Size(70, 70), 0.0, 0.0, cv::INTER_AREA);
  cv::resize(reduced, qr, roi.size(), 0.0, 0.0, cv::INTER_LINEAR);

  for (int y = 0; y < qr.rows; ++y) {
    uint8_t* row = qr.ptr<uint8_t>(y);
    for (int x = 0; x < qr.cols; ++x) {
      const double horizontal = static_cast<double>(x) / std::max(1, qr.cols - 1);
      const double vertical = static_cast<double>(y) / std::max(1, qr.rows - 1);
      const double finderShadow = x < qr.cols * 0.42 && y < qr.rows * 0.42 ? 0.52 : 1.0;
      const double paperTone = 52.0 + 72.0 * horizontal + 16.0 * std::sin(vertical * 5.0);
      const double inkContrast = 112.0 * finderShadow;
      row[x] = static_cast<uint8_t>(std::clamp(
        cvRound(paperTone + inkContrast * static_cast<double>(row[x]) / 255.0),
        0,
        255
      ));
    }
  }
  cv::GaussianBlur(qr, qr, cv::Size(5, 5), 1.05);

  std::vector<uint8_t> encoded;
  cv::imencode(".jpg", qr, encoded, {cv::IMWRITE_JPEG_QUALITY, 52});
  qr = cv::imdecode(encoded, cv::IMREAD_GRAYSCALE);
  qr.copyTo(canvas(roi));
  return canvas;
}

omr::OmrTemplate qrTemplate(const cv::Rect& roi, const std::string& payloadRegex) {
  omr::OmrTemplate tpl;
  tpl.width = 360.0f;
  tpl.height = 330.0f;
  tpl.examCodeBox = {
    static_cast<float>(std::max(0, roi.x - 30)),
    static_cast<float>(std::max(0, roi.y - 30)),
    static_cast<float>(roi.width + 110),
    static_cast<float>(roi.height + 60),
  };
  tpl.barcode.qrCodeOnly = true;
  tpl.barcode.roi = {
    static_cast<float>(roi.x),
    static_cast<float>(roi.y),
    static_cast<float>(roi.width),
    static_cast<float>(roi.height),
  };
  tpl.barcode.preferredFormats = {"QR_CODE"};
  tpl.barcode.expectedPayloadRegex = payloadRegex;
  return tpl;
}

bool testValidQrPayloadAndRoi() {
  const cv::Rect roi(31, 23, 247, 247);
  const cv::Mat canvas = codeCanvas(
    ZXing::BarcodeFormat::QRCode,
    "12345678",
    roi,
    cv::Size(320, 300)
  );
  omr::OmrTemplate tpl;
  tpl.barcode.roi = {
    static_cast<float>(roi.x),
    static_cast<float>(roi.y),
    static_cast<float>(roi.width),
    static_cast<float>(roi.height),
  };
  tpl.barcode.preferredFormats = {"QR_CODE"};
  tpl.barcode.expectedPayloadRegex = "^[0-9]{8}$";

  const omr::OmrBarcodeResult result = omr::BarcodeReader{}.decode(canvas, tpl);

  bool ok = true;
  ok &= require(result.found, "valid QR payload must be found");
  ok &= require(result.attempted, "valid QR payload must be attempted");
  ok &= require(result.status == "FOUND", "valid QR payload must have FOUND status");
  ok &= require(result.text == "12345678", "valid QR payload text must be preserved");
  ok &= require(!result.format.empty(), "valid QR payload must report its format");
  ok &= require(
    result.quad.topLeft.x == static_cast<float>(roi.x) &&
      result.quad.topLeft.y == static_cast<float>(roi.y) &&
      result.quad.bottomRight.x == static_cast<float>(roi.x + roi.width) &&
      result.quad.bottomRight.y == static_cast<float>(roi.y + roi.height),
    "decoded QR quad must match the configured ROI"
  );
  return ok;
}

bool testQrSurvivesCameraResamplingBlurAndJpeg() {
  const cv::Rect roi(37, 31, 247, 247);
  const std::string payload = "EXAM:2026_A7";
  const cv::Mat canvas = cameraDegradedQr(payload, roi, cv::Size(330, 315), 96);
  const omr::OmrTemplate tpl = qrTemplate(roi, "^EXAM:[A-Za-z0-9_-]{2,40}$");

  const omr::OmrBarcodeResult result = omr::BarcodeReader{}.decode(canvas, tpl);
  bool ok = true;
  ok &= require(result.found, "camera-resampled QR at usable module pitch must be found");
  ok &= require(result.status == "FOUND", "degraded QR must retain FOUND status");
  ok &= require(result.text == payload, "degraded QR must decode to the exact payload");
  return ok;
}

bool testDenseStudentTokenQrIsPreserved() {
  const cv::Rect roi(37, 31, 247, 247);
  const std::string payload =
    "fH8_n7mQ2pL5xV9cK3sD6wR1tY4uI0oP-aS8dF7gH6jK5lZ4xC3vB2nM1qW9eR8";
  const cv::Mat canvas = cameraDegradedQr(payload, roi, cv::Size(330, 315), 104);
  const omr::OmrTemplate tpl = qrTemplate(roi, "^.{2,80}$");

  const omr::OmrBarcodeResult result = omr::BarcodeReader{}.decode(canvas, tpl);
  bool ok = true;
  printFailureResult(result);
  ok &= require(result.found, "dense student-token QR must be found");
  ok &= require(result.status == "FOUND", "student-token QR must retain FOUND status");
  ok &= require(result.text == payload, "student-token QR bytes must be preserved exactly");
  return ok;
}

bool testOldAndShortOpaqueStudentQrRotations() {
  const cv::Rect roi(37, 31, 247, 247);
  const auto tpl = qrTemplate(roi, "^.{2,80}$");
  const std::vector<std::string> payloads = {
    "S2EAE97QMWMFX0ENW5FX", "C2HP314HP61CME44WCR6",
    "yse.sheet.v1." + std::string(64, 'a'),
  };
  bool ok = true;
  for (const auto& payload : payloads) {
    for (int rotation = 0; rotation < 4; ++rotation) {
      auto canvas = cameraDegradedQr(payload, roi, cv::Size(330, 315), payload.size() == 20 ? 80 : 144);
      auto qr = canvas(roi).clone();
      for (int turn = 0; turn < rotation; ++turn) cv::rotate(qr, qr, cv::ROTATE_90_CLOCKWISE);
      qr.copyTo(canvas(roi));
      const auto result = omr::BarcodeReader{}.decode(canvas, tpl);
      ok &= require(result.found && result.text == payload,
        "legacy/short student QR must survive all quarter-turns, resampling, blur, uneven light and JPEG");
    }
  }
  return ok;
}

bool testQrSurvivesUnevenLightingLowContrastAndBlur() {
  const cv::Rect roi(37, 31, 247, 247);
  const std::string payload = "LOW_CONTRAST_2026";
  const cv::Mat canvas = unevenLowContrastQr(payload, roi, cv::Size(330, 315));
  const omr::OmrTemplate tpl = qrTemplate(roi, "^[A-Z0-9_]{2,40}$");

  const omr::OmrBarcodeResult result = omr::BarcodeReader{}.decode(canvas, tpl);
  bool ok = true;
  ok &= require(result.found, "uneven low-contrast QR must be recovered");
  ok &= require(result.text == payload, "low-contrast recovery must preserve the exact payload");
  return ok;
}

bool testQrSurvivesSmallGeometricDriftAroundConfiguredRoi() {
  const cv::Rect configuredRoi(45, 38, 247, 247);
  const cv::Rect shiftedQr(configuredRoi.x + 18, configuredRoi.y - 14, 247, 247);
  const std::string payload = "SHIFTED_2026";
  const cv::Mat canvas = codeCanvas(
    ZXing::BarcodeFormat::QRCode,
    payload,
    shiftedQr,
    cv::Size(360, 330)
  );
  const omr::OmrTemplate tpl = qrTemplate(configuredRoi, "^[A-Z0-9_]{2,40}$");

  const omr::OmrBarcodeResult result = omr::BarcodeReader{}.decode(canvas, tpl);
  bool ok = true;
  printFailureResult(result);
  ok &= require(result.found, "small post-warp QR drift must be recovered inside the exam-code box");
  ok &= require(result.text == payload, "drift recovery must preserve the exact QR payload");
  return ok;
}

bool testQrSurvivesLargeDriftInsideExamCodeBox() {
  const cv::Rect configuredRoi(45, 38, 247, 247);
  const cv::Rect shiftedQr(configuredRoi.x + 55, configuredRoi.y, 247, 247);
  const std::string payload = "SHIFTED_FAR_2026";
  const cv::Mat canvas = codeCanvas(
    ZXing::BarcodeFormat::QRCode,
    payload,
    shiftedQr,
    cv::Size(410, 330)
  );
  const omr::OmrTemplate tpl = qrTemplate(configuredRoi, "^[A-Z0-9_]{2,40}$");

  const omr::OmrBarcodeResult result = omr::BarcodeReader{}.decode(canvas, tpl);
  bool ok = true;
  printFailureResult(result);
  ok &= require(result.found, "QR drift inside the signed exam-code box must be recovered");
  ok &= require(result.text == payload, "large drift recovery must preserve the exact payload");
  return ok;
}

bool testUnderResolvedQrNeverProducesWrongPayload() {
  const cv::Rect roi(29, 25, 247, 247);
  const std::string payload = "UNDER_RESOLVED_2026";
  const cv::Mat canvas = cameraDegradedQr(payload, roi, cv::Size(310, 300), 43);
  const omr::OmrTemplate tpl = qrTemplate(roi, "^[A-Z0-9_]{2,40}$");

  const omr::OmrBarcodeResult result = omr::BarcodeReader{}.decode(canvas, tpl);
  return require(
    !result.found || (result.status == "FOUND" && result.text == payload),
    "under-resolved QR must either decode exactly or fail safely"
  );
}

bool testInvalidQrPayloadIsNotFound() {
  const cv::Rect roi(19, 17, 247, 247);
  const cv::Mat canvas = codeCanvas(
    ZXing::BarcodeFormat::QRCode,
    "NOT-AN-EXAM-CODE",
    roi,
    cv::Size(300, 290)
  );
  omr::OmrTemplate tpl;
  tpl.barcode.roi = {
    static_cast<float>(roi.x),
    static_cast<float>(roi.y),
    static_cast<float>(roi.width),
    static_cast<float>(roi.height),
  };
  tpl.barcode.preferredFormats = {"QR_CODE"};
  tpl.barcode.expectedPayloadRegex = "^[0-9]{8}$";

  const omr::OmrBarcodeResult result = omr::BarcodeReader{}.decode(canvas, tpl);

  bool ok = true;
  ok &= require(!result.found, "QR payload rejected by the regex must not be found");
  ok &= require(result.attempted, "invalid QR payload must still record a decode attempt");
  ok &= require(result.status == "INVALID_PAYLOAD", "invalid QR payload status must remain INVALID_PAYLOAD");
  ok &= require(result.text == "NOT-AN-EXAM-CODE", "invalid QR payload text must remain available for diagnostics");
  return ok;
}

bool testLinearBarcodeIsRejectedByQrOnlyTemplate() {
  const cv::Rect roi(20, 20, 247, 247);
  const cv::Mat canvas = codeCanvas(
    ZXing::BarcodeFormat::Code128,
    "12345678",
    roi,
    cv::Size(290, 290)
  );
  omr::OmrTemplate tpl;
  tpl.barcode.qrCodeOnly = true;
  tpl.barcode.roi = {
    static_cast<float>(roi.x),
    static_cast<float>(roi.y),
    static_cast<float>(roi.width),
    static_cast<float>(roi.height),
  };
  tpl.barcode.preferredFormats = {"QR_CODE"};
  tpl.barcode.expectedPayloadRegex = "^[0-9]{8}$";

  const omr::OmrBarcodeResult result = omr::BarcodeReader{}.decode(canvas, tpl);
  bool ok = true;
  if (!result.found && result.status != "NOT_FOUND") printFailureResult(result);
  ok &= require(!result.found, "Code 128 must not be accepted by the QR-only template");
  ok &= require(result.attempted, "Code 128 rejection must still record a decode attempt");
  ok &= require(result.status == "NOT_FOUND", "rejected Code 128 must remain NOT_FOUND");
  return ok;
}

bool testQrTemplateParsing() {
  const omr::OmrTemplate tpl = omr::OmrTemplate::fromJson(R"json({
    "version": 2,
    "templateId": "qr-parser-regression",
    "coordinateSystem": {
      "referenceRender": {"dpi": 200, "width": 1656, "height": 2339}
    },
    "examCode": {
      "box": {"x": 167, "y": 61, "width": 492, "height": 303},
      "qrCode": {
        "enabled": true,
        "required": true,
        "placement": "inside_exam_code_box",
        "preferredFormats": ["QR_CODE"],
        "roi": {"x": 194.5, "y": 88.9, "width": 247.2, "height": 247.2},
        "fallbackRois": [],
        "expectedPayloadRegex": "^[0-9]{8}$"
      }
    }
  })json");

  bool ok = true;
  ok &= require(tpl.barcode.qrCodeOnly, "examCode.qrCode must select QR-only parsing");
  ok &= require(tpl.barcode.required, "required QR setting must be parsed");
  ok &= require(
    tpl.barcode.preferredFormats == std::vector<std::string>{"QR_CODE"},
    "QR template must keep QR_CODE as its only format"
  );
  ok &= require(
    tpl.barcode.roi.x == 194.5f && tpl.barcode.roi.y == 88.9f &&
      tpl.barcode.roi.width == 247.2f && tpl.barcode.roi.height == 247.2f,
    "89-point QR ROI must be parsed in 200-DPI reference coordinates"
  );
  return ok;
}

} // namespace

int main() {
  bool ok = true;
  ok &= testValidQrPayloadAndRoi();
  ok &= testQrSurvivesCameraResamplingBlurAndJpeg();
  ok &= testDenseStudentTokenQrIsPreserved();
  ok &= testOldAndShortOpaqueStudentQrRotations();
  ok &= testQrSurvivesUnevenLightingLowContrastAndBlur();
  ok &= testQrSurvivesSmallGeometricDriftAroundConfiguredRoi();
  ok &= testQrSurvivesLargeDriftInsideExamCodeBox();
  ok &= testUnderResolvedQrNeverProducesWrongPayload();
  ok &= testInvalidQrPayloadIsNotFound();
  ok &= testLinearBarcodeIsRejectedByQrOnlyTemplate();
  ok &= testQrTemplateParsing();
  if (ok) {
    std::cout << "BarcodeReader regression tests passed\n";
    return 0;
  }
  return 1;
}
