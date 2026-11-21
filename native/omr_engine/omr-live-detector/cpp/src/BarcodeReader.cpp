#include "omr/BarcodeReader.hpp"

#include <algorithm>
#include <cmath>
#include <regex>
#include <utility>
#include <opencv2/imgproc.hpp>

#if __has_include(<ZXing/ReadBarcode.h>) && __has_include(<ZXing/ReaderOptions.h>) && __has_include(<ZXing/ImageView.h>)
#include <ZXing/BarcodeFormat.h>
#include <ZXing/ImageView.h>
#include <ZXing/ReadBarcode.h>
#include <ZXing/ReaderOptions.h>
#define OMR_HAS_ZXING 1
#elif __has_include(<ReadBarcode.h>) && __has_include(<ReaderOptions.h>) && __has_include(<ImageView.h>)
// ZXing's installed CMake package exposes headers below `ZXing/`, while its
// source-tree target exposes `core/src` directly. Android builds vendor the
// source tree, so support that public target layout as well.
#include <BarcodeFormat.h>
#include <ImageView.h>
#include <ReadBarcode.h>
#include <ReaderOptions.h>
#define OMR_HAS_ZXING 1
#else
#error "ZXing headers are required but were not exposed by the linked ZXing target"
#endif

namespace omr {

namespace {

cv::Rect clampRect(const Rect& rect, const cv::Size& size) {
  const int x = std::max(0, static_cast<int>(std::round(rect.x)));
  const int y = std::max(0, static_cast<int>(std::round(rect.y)));
  const int right = std::min(size.width, static_cast<int>(std::round(rect.x + rect.width)));
  const int bottom = std::min(size.height, static_cast<int>(std::round(rect.y + rect.height)));
  return cv::Rect(x, y, std::max(0, right - x), std::max(0, bottom - y));
}

bool hasBarcodeLikeDarkContent(const cv::Mat& gray) {
  if (gray.empty()) {
    return false;
  }
  cv::Mat thresholded;
  cv::threshold(gray, thresholded, 0.0, 255.0, cv::THRESH_BINARY_INV | cv::THRESH_OTSU);
  const double darkRatio = static_cast<double>(cv::countNonZero(thresholded)) / static_cast<double>(std::max<size_t>(1, gray.total()));
  return darkRatio >= 0.005;
}

bool isTerminalDecodeError(const OmrBarcodeResult& result) {
  return result.status == "INVALID_PAYLOAD" || result.status == "INVALID_PAYLOAD_REGEX";
}

Rect paddedQrRoi(const OmrTemplate& tpl) {
  const Rect& roi = tpl.barcode.roi;
  const float padding = std::max(12.0f, std::min(roi.width, roi.height) * 0.22f);
  const float boxLeft = tpl.examCodeBox.x;
  const float boxTop = tpl.examCodeBox.y;
  const float boxRight = tpl.examCodeBox.x + tpl.examCodeBox.width;
  const float boxBottom = tpl.examCodeBox.y + tpl.examCodeBox.height;

  const float left = std::max(boxLeft, roi.x - padding);
  const float top = std::max(boxTop, roi.y - padding);
  const float right = std::min(boxRight, roi.x + roi.width + padding);
  const float bottom = std::min(boxBottom, roi.y + roi.height + padding);
  if (right <= left || bottom <= top) {
    return roi;
  }
  return {left, top, right - left, bottom - top};
}

bool sameRect(const Rect& left, const Rect& right) {
  constexpr float epsilon = 0.5f;
  return std::abs(left.x - right.x) < epsilon &&
         std::abs(left.y - right.y) < epsilon &&
         std::abs(left.width - right.width) < epsilon &&
         std::abs(left.height - right.height) < epsilon;
}

OmrBarcodeResult useQrVocabulary(OmrBarcodeResult result, bool qrCodeOnly) {
  if (!qrCodeOnly) {
    return result;
  }
  if (result.status == "SKIPPED_NO_BARCODE_PATTERN") result.status = "SKIPPED_NO_QR_PATTERN";
  if (result.error == "barcode_disabled") result.error = "qr_code_disabled";
  else if (result.error == "invalid_barcode_roi") result.error = "invalid_qr_code_roi";
  else if (result.error == "barcode_not_found") result.error = "qr_code_not_found";
  else if (result.error == "barcode_invalid_payload") result.error = "qr_code_invalid_payload";
  else if (result.error == "barcode_invalid_payload_regex") result.error = "qr_code_invalid_payload_regex";
  if (result.skippedReason == "barcode_disabled") result.skippedReason = "qr_code_disabled";
  else if (result.skippedReason == "invalid_barcode_roi") result.skippedReason = "invalid_qr_code_roi";
  else if (result.skippedReason == "no_barcode_pattern") result.skippedReason = "no_qr_pattern";
  return result;
}

} // namespace

OmrBarcodeResult BarcodeReader::decode(const cv::Mat& normalizedGray, const OmrTemplate& tpl) const {
  if (!tpl.barcode.enabled) {
    OmrBarcodeResult disabled;
    disabled.status = "DISABLED";
    disabled.attempted = false;
    disabled.error = "barcode_disabled";
    disabled.skippedReason = "barcode_disabled";
    return useQrVocabulary(std::move(disabled), tpl.barcode.qrCodeOnly);
  }

  auto result = decodeRoi(normalizedGray, tpl.barcode.roi, tpl.barcode.preferredFormats, tpl.barcode.expectedPayloadRegex);
  if (result.found || isTerminalDecodeError(result)) {
    return useQrVocabulary(std::move(result), tpl.barcode.qrCodeOnly);
  }

  if (tpl.barcode.qrCodeOnly) {
    // The configured ROI is the nominal 89-point QR box. A small homography
    // residual near the top of the sheet can otherwise crop finder modules.
    // Retry with bounded padding, never leaving the exam-code container.
    const Rect padded = paddedQrRoi(tpl);
    if (
      !sameRect(padded, tpl.barcode.roi)
    ) {
      result = decodeRoi(normalizedGray, padded, tpl.barcode.preferredFormats, tpl.barcode.expectedPayloadRegex);
      if (result.found || isTerminalDecodeError(result)) {
        return useQrVocabulary(std::move(result), true);
      }
    }

    // The signed template's exam-code box is still a narrow region. Searching
    // it as a final geometry fallback recovers a QR shifted farther than the
    // nominal ROI by page curl or a slightly imperfect sheet homography.
    if (
      tpl.examCodeBox.width > 0.0f && tpl.examCodeBox.height > 0.0f &&
      !sameRect(tpl.examCodeBox, padded) && !sameRect(tpl.examCodeBox, tpl.barcode.roi)
    ) {
      result = decodeRoi(
        normalizedGray,
        tpl.examCodeBox,
        tpl.barcode.preferredFormats,
        tpl.barcode.expectedPayloadRegex
      );
      if (result.found || isTerminalDecodeError(result)) {
        return useQrVocabulary(std::move(result), true);
      }
    }
  }

  for (const auto& fallback : tpl.barcode.fallbackRois) {
    result = decodeRoi(normalizedGray, fallback, tpl.barcode.preferredFormats, tpl.barcode.expectedPayloadRegex);
    if (result.found || isTerminalDecodeError(result)) {
      return useQrVocabulary(std::move(result), tpl.barcode.qrCodeOnly);
    }
  }

  result.error = "barcode_not_found";
  if (result.status.empty() || result.status == "NOT_ATTEMPTED") {
    result.status = "NOT_FOUND";
  }
  return useQrVocabulary(std::move(result), tpl.barcode.qrCodeOnly);
}

OmrBarcodeResult BarcodeReader::decodeRoi(
  const cv::Mat& normalizedGray,
  const Rect& roi,
  const std::vector<std::string>& formats,
  const std::string& expectedPayloadRegex
) const {
  OmrBarcodeResult result;
  result.status = "NOT_ATTEMPTED";
  if (normalizedGray.empty()) {
    result.error = "empty_image";
    result.skippedReason = "empty_image";
    return result;
  }

  const cv::Rect cvRoi = clampRect(roi, normalizedGray.size());
  if (cvRoi.width <= 0 || cvRoi.height <= 0) {
    result.error = "invalid_barcode_roi";
    result.skippedReason = "invalid_barcode_roi";
    return result;
  }

  const cv::Mat cropped = normalizedGray(cvRoi);
  if (!hasBarcodeLikeDarkContent(cropped)) {
    result.status = "SKIPPED_NO_BARCODE_PATTERN";
    result.error = "barcode_not_found";
    result.skippedReason = "no_barcode_pattern";
    return result;
  }

  result.attempted = true;

#if OMR_HAS_ZXING
  ZXing::BarcodeFormats selected;
#if ZXING_VERSION_MAJOR >= 3
  std::vector<ZXing::BarcodeFormat> selectedList;
#endif
  for (const auto& format : formats) {
    if (format == "QR_CODE" || format == "QRCode") {
#if ZXING_VERSION_MAJOR >= 3
      selectedList.push_back(ZXing::BarcodeFormat::QRCode);
#else
      selected |= ZXing::BarcodeFormat::QRCode;
#endif
    } else if (format == "DATA_MATRIX" || format == "DataMatrix") {
#if ZXING_VERSION_MAJOR >= 3
      selectedList.push_back(ZXing::BarcodeFormat::DataMatrix);
#else
      selected |= ZXing::BarcodeFormat::DataMatrix;
#endif
    } else if (format == "CODE_128" || format == "Code128") {
#if ZXING_VERSION_MAJOR >= 3
      selectedList.push_back(ZXing::BarcodeFormat::Code128);
#else
      selected |= ZXing::BarcodeFormat::Code128;
#endif
    } else if (format == "PDF_417" || format == "PDF417") {
#if ZXING_VERSION_MAJOR >= 3
      selectedList.push_back(ZXing::BarcodeFormat::PDF417);
#else
      selected |= ZXing::BarcodeFormat::PDF417;
#endif
    }
  }
#if ZXING_VERSION_MAJOR >= 3
  selected = selectedList.empty()
    ? ZXing::BarcodeFormats(ZXing::BarcodeFormat::QRCode |
                            ZXing::BarcodeFormat::DataMatrix |
                            ZXing::BarcodeFormat::Code128)
    : ZXing::BarcodeFormats(std::move(selectedList));
#else
  if (selected.empty()) {
    selected = ZXing::BarcodeFormat::QRCode |
               ZXing::BarcodeFormat::DataMatrix |
               ZXing::BarcodeFormat::Code128;
  }
#endif

  const auto tryDecode = [&result, &selected, &expectedPayloadRegex, &cvRoi](
    const cv::Mat& imageMat,
    ZXing::Binarizer binarizer,
    bool pure = false
  ) {
    if (imageMat.empty()) {
      return false;
    }
    const ZXing::ImageView image(
      imageMat.data,
      imageMat.cols,
      imageMat.rows,
      ZXing::ImageFormat::Lum,
      static_cast<int>(imageMat.step)
    );
    const auto options = ZXing::ReaderOptions()
                           .setFormats(selected)
                           .setTryHarder(true)
                           .setTryRotate(true)
                           .setTryInvert(true)
                           .setBinarizer(binarizer)
                           .setIsPure(pure)
                           .setMaxNumberOfSymbols(1);
    const auto barcodes = ZXing::ReadBarcodes(image, options);
    if (barcodes.empty()) {
      return false;
    }

    const auto& barcode = barcodes.front();
    if (!barcode.isValid() || barcode.bytes().empty()) {
      return false;
    }
    result.found = true;
    result.status = "FOUND";
    result.text = barcode.text();
    result.format = ZXing::ToString(barcode.format());
    result.confidence = 1.0f;
    result.quad = {
      {static_cast<float>(cvRoi.x), static_cast<float>(cvRoi.y)},
      {static_cast<float>(cvRoi.x + cvRoi.width), static_cast<float>(cvRoi.y)},
      {static_cast<float>(cvRoi.x + cvRoi.width), static_cast<float>(cvRoi.y + cvRoi.height)},
      {static_cast<float>(cvRoi.x), static_cast<float>(cvRoi.y + cvRoi.height)},
    };

    if (!expectedPayloadRegex.empty()) {
      try {
        if (!std::regex_match(result.text, std::regex(expectedPayloadRegex))) {
          result.found = false;
          result.error = "barcode_invalid_payload";
          result.status = "INVALID_PAYLOAD";
        }
      } catch (const std::regex_error&) {
        result.found = false;
        result.error = "barcode_invalid_payload_regex";
        result.status = "INVALID_PAYLOAD_REGEX";
      }
    }
    return true;
  };

  // Compatibility path: preserve the exact 2x cubic input and ZXing defaults
  // used by the original reader. Optimized retries must never replace a path
  // that already worked for real student QR tokens.
  cv::Mat baselineEnlarged;
  cv::resize(cropped, baselineEnlarged, cv::Size(), 2.0, 2.0, cv::INTER_CUBIC);
  if (tryDecode(baselineEnlarged, ZXing::Binarizer::LocalAverage)) {
    return result;
  }

  // Preserve the camera pixels as a second fast path so interpolation cannot
  // erase already-readable module edges.
  if (tryDecode(cropped, ZXing::Binarizer::LocalAverage)) {
    return result;
  }

  const int shortestSide = std::max(1, std::min(cropped.cols, cropped.rows));
  const double scale = std::clamp(620.0 / shortestSide, 2.0, 3.0);
  cv::Mat enlarged;
  cv::resize(cropped, enlarged, cv::Size(), scale, scale, cv::INTER_CUBIC);
  if (tryDecode(enlarged, ZXing::Binarizer::LocalAverage)) {
    return result;
  }

  // Retry only after the normal decoder failed. CLAHE corrects local shadows
  // on photographed sheets, while a light unsharp mask restores finder/module
  // edges softened by PDF rasterization, camera resampling, or JPEG encoding.
  cv::Mat contrast;
  cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(2.8, cv::Size(6, 6));
  clahe->apply(cropped, contrast);
  cv::resize(contrast, contrast, enlarged.size(), 0.0, 0.0, cv::INTER_CUBIC);

  cv::Mat blurred;
  cv::Mat sharpened;
  cv::GaussianBlur(contrast, blurred, cv::Size(0, 0), 1.15);
  cv::addWeighted(contrast, 1.8, blurred, -0.8, 0.0, sharpened);
  if (
    tryDecode(sharpened, ZXing::Binarizer::LocalAverage) ||
    tryDecode(sharpened, ZXing::Binarizer::GlobalHistogram)
  ) {
    return result;
  }

  const int enhancedShortestSide = std::min(sharpened.cols, sharpened.rows);
  int blockSize = std::clamp(enhancedShortestSide / 8, 15, 81);
  if (blockSize % 2 == 0) ++blockSize;
  const int largestValidBlock =
    enhancedShortestSide % 2 == 0 ? enhancedShortestSide - 1 : enhancedShortestSide;
  blockSize = std::min(blockSize, largestValidBlock);
  if (blockSize < 3) {
    result.error = "barcode_not_found";
    result.status = "NOT_FOUND";
    return result;
  }
  cv::Mat adaptive;
  cv::adaptiveThreshold(
    sharpened,
    adaptive,
    255.0,
    cv::ADAPTIVE_THRESH_GAUSSIAN_C,
    cv::THRESH_BINARY,
    blockSize,
    5.0
  );
  const int quietBorder = std::max(12, enhancedShortestSide / 16);
  cv::Mat bordered;
  cv::copyMakeBorder(
    adaptive,
    bordered,
    quietBorder,
    quietBorder,
    quietBorder,
    quietBorder,
    cv::BORDER_CONSTANT,
    cv::Scalar(255)
  );
  if (
    tryDecode(adaptive, ZXing::Binarizer::FixedThreshold) ||
    tryDecode(bordered, ZXing::Binarizer::FixedThreshold) ||
    tryDecode(bordered, ZXing::Binarizer::FixedThreshold, true)
  ) {
    return result;
  }

  result.error = "barcode_not_found";
  result.status = "NOT_FOUND";
  return result;
#else
  result.error = "zxing_unavailable";
  result.status = "SKIPPED_ZXING_UNAVAILABLE";
  result.skippedReason = "zxing_unavailable";
  return result;
#endif
}

} // namespace omr
