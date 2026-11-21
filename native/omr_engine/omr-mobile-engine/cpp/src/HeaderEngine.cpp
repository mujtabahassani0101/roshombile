#include "omr/HeaderEngine.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include "omr/BarcodeReader.hpp"
#include "omr/OmrTemplate.hpp"
#include "omr/QrSheetType.hpp"
#include "utils/Json.hpp"
#include "utils/Timer.hpp"

namespace omr {
namespace {

cv::Mat rotate(const cv::Mat& image, int angle) {
  if (!angle) return image;
  cv::Mat out;
  cv::rotate(image, out, angle == 90 ? cv::ROTATE_90_CLOCKWISE :
    angle == 180 ? cv::ROTATE_180 : cv::ROTATE_90_COUNTERCLOCKWISE);
  return out;
}

// The four header registration dots have a common spacing in T1/T2/T3.
// Locate their lower boundary from side strips only. This accommodates a
// shifted/cropped header without extending QR search into answer blocks.
int headerBottom(const cv::Mat& gray) {
  const int bandWidth = std::max(1, cvRound(gray.cols * .12));
  const int bandHeight = std::max(1, cvRound(gray.rows * .34));
  std::array<std::vector<float>, 2> rows;
  for (int side = 0; side < 2; ++side) {
    const cv::Rect band(side == 0 ? 0 : gray.cols - bandWidth, 0, bandWidth, bandHeight);
    cv::Mat binary;
    cv::threshold(gray(band), binary, 0, 255, cv::THRESH_BINARY_INV | cv::THRESH_OTSU);
    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(binary, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
    for (const auto& contour : contours) {
      const auto box = cv::boundingRect(contour);
      const float ratio = static_cast<float>(box.width) / std::max(1, box.height);
      const float fill = static_cast<float>(cv::contourArea(contour)) / std::max(1, box.area());
      if (box.width >= gray.cols * .012 && box.width <= gray.cols * .065 &&
          ratio >= .65 && ratio <= 1.5 && fill >= .5) {
        rows[side].push_back(box.y + box.height * .5f);
      }
    }
    std::sort(rows[side].begin(), rows[side].end());
  }
  std::vector<float> paired;
  for (float left : rows[0]) {
    for (float right : rows[1]) {
      if (std::abs(left - right) <= gray.rows * .012) {
        paired.push_back((left + right) * .5f);
        break;
      }
    }
  }
  if (paired.size() >= 4) {
    const float gap = (paired[3] - paired[0]) / 3;
    const bool spaced = gap > gray.rows * .015 && gap < gray.rows * .045 &&
      std::abs((paired[1]-paired[0]) - gap) < gap * .4 &&
      std::abs((paired[2]-paired[1]) - gap) < gap * .4;
    if (spaced && paired[0] < gray.rows * .16) {
      // Repeated answer-rail rows at the bottom of an upside-down page are
      // not a header. The real four-dot header has a gap before answers.
      if (paired.size() > 4 && paired[4] - paired[3] < gap * 1.35f) return 0;
      float bottom = paired[3] + gap;
      if (paired.size() > 4) bottom = std::min(bottom, (paired[3] + paired[4]) * .5f);
      return std::clamp(cvRound(bottom), 1, cvRound(gray.rows * .29));
    }
  }
  // Common A5 header bounds, with a small allowance for scanner edge crop.
  // No full-page barcode fallback is permitted.
  return std::max(1, cvRound(gray.rows * .235));
}

// A few scanner JPEGs put a sideways sheet inside a near-square portrait
// canvas. Locate its four-dot header across the padded top band rather than
// assuming that the outer bitmap edges are paper edges. This is registration
// geometry only: the decoder still receives only the resulting header crop.
cv::Rect paddedCanvasHeader(const cv::Mat& gray) {
  cv::Mat binary;
  const int height = cvRound(gray.rows * .38);
  cv::threshold(gray(cv::Rect(0,0,gray.cols,height)),binary,0,255,cv::THRESH_BINARY_INV|cv::THRESH_OTSU);
  std::vector<std::vector<cv::Point>> contours;
  cv::findContours(binary,contours,cv::RETR_EXTERNAL,cv::CHAIN_APPROX_SIMPLE);
  struct Column { float x; std::vector<float> ys; };
  std::vector<Column> columns;
  for (const auto& contour:contours) {
    const auto box=cv::boundingRect(contour);
    const float ratio=static_cast<float>(box.width)/std::max(1,box.height);
    if (box.width<gray.cols*.008 || box.width>gray.cols*.065 || ratio<.65 || ratio>1.5 ||
        cv::contourArea(contour)<box.area()*.5) continue;
    const float x=box.x+box.width*.5f, y=box.y+box.height*.5f;
    auto column=std::find_if(columns.begin(),columns.end(),[&](const Column& c) {
      return std::abs(c.x-x)<gray.cols*.018;
    });
    if (column==columns.end()) columns.push_back({x,{y}});
    else column->ys.push_back(y);
  }
  cv::Rect best;
  float bestSpan=0;
  for (auto& column:columns) std::sort(column.ys.begin(),column.ys.end());
  for (const auto& left:columns) for (const auto& right:columns) {
    const float span=right.x-left.x;
    if (span<gray.cols*.35 || span<bestSpan) continue;
    std::vector<float> paired;
    for (float a:left.ys) for (float b:right.ys) if(std::abs(a-b)<gray.rows*.012) {
      paired.push_back((a+b)*.5f); break;
    }
    if (paired.size()<4) continue;
    const float gap=(paired[3]-paired[0])/3;
    if(gap<gray.rows*.012 || gap>gray.rows*.05 || paired[0]>gray.rows*.20 ||
       std::abs(paired[1]-paired[0]-gap)>gap*.4 ||
       std::abs(paired[2]-paired[1]-gap)>gap*.4 ||
       (paired.size()>4 && paired[4]-paired[3]<gap*1.35) || span/gap<12 || span/gap>40) continue;
    const int x=std::max(0,cvRound(left.x-span*.04f));
    const int y=std::max(0,cvRound(paired[0]-gap*2.84f));
    const int endX=std::min(gray.cols,cvRound(right.x+span*.04f));
    const int endY=std::min(gray.rows,cvRound(paired[3]+gap));
    if(endX>x && endY>y) { best={x,y,endX-x,endY-y}; bestSpan=span; }
  }
  return best;
}

OmrTemplate headerLayout(const cv::Mat& image, int bottom) {
  OmrTemplate tpl;
  tpl.width = static_cast<float>(image.cols);
  tpl.height = static_cast<float>(image.rows);
  tpl.answers.enabled = false;
  tpl.studentCode.enabled = false;
  tpl.barcode.enabled = true;
  tpl.barcode.required = true;
  tpl.barcode.qrCodeOnly = true;
  tpl.barcode.preferredFormats = {"QR_CODE"};
  tpl.barcode.expectedPayloadRegex = "^T[123]:[0-9]{1,32}:(S2[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{18}|[A-Za-z0-9]{8})$";
  tpl.examCodeBox = {0, 0, static_cast<float>(image.cols), static_cast<float>(bottom)};
  const float x = image.cols * (350.f/1750.f);
  const float y = image.rows * (120.f/2480.f);
  const float right = image.cols * (710.f/1750.f);
  const float end = std::min(static_cast<float>(bottom), image.rows * (500.f/2480.f));
  tpl.barcode.roi = {x, y, right-x, std::max(1.f, end-y)};
  tpl.barcode.fallbackRois.clear();
  return tpl;
}
} // namespace

HeaderScanResult HeaderEngine::scan(const std::string& imagePath, int maxInputDimension) const {
  Timer timer;
  HeaderScanResult result;
  result.imagePath = imagePath;
  cv::Mat source = cv::imread(json::stripFileUri(imagePath), cv::IMREAD_GRAYSCALE);
  if (source.empty()) {
    result.error = "image_load_failed";
    result.processingTimeMs = timer.elapsedMs();
    return result;
  }
  if (maxInputDimension > 0 && std::max(source.rows, source.cols) > maxInputDimension) {
    const double scale = static_cast<double>(maxInputDimension) / std::max(source.rows, source.cols);
    cv::resize(source, source, cv::Size(), scale, scale, cv::INTER_AREA);
  }
  const std::array<int, 4> orientations = source.rows >= source.cols
    ? std::array<int, 4>{0,180,90,270} : std::array<int, 4>{90,270,0,180};
  for (const int orientation : orientations) {
    const auto candidate = rotate(source, orientation);
    cv::Rect region;
    const bool paddedCanvas=candidate.cols>candidate.rows*.85;
    if (paddedCanvas) {
      if(candidate.cols>candidate.rows*1.2) continue;
      region=paddedCanvasHeader(candidate);
      if(region.empty()) continue;
    } else {
      const int bottom=headerBottom(candidate);
      if(bottom<=0) continue;
      region={0,0,candidate.cols,bottom};
    }
    auto layout = headerLayout(candidate, region.height);
    // Pass only the header pixels to the decoder. Even its internal retries
    // and fallback rectangles cannot access any pixels below this boundary.
    const auto strip = candidate(region);
    if(paddedCanvas) {
      layout.examCodeBox={0,0,static_cast<float>(region.width),static_cast<float>(region.height)};
      layout.barcode.roi=layout.examCodeBox;
    }
    auto qr = BarcodeReader{}.decode(strip, layout);
    if (result.qr.text.empty() && qr.text.empty()) result.qr = qr;
    if (!qr.text.empty()) {
      result.qr = std::move(qr);
      result.clockwiseRotation = orientation;
      result.scannedRegion = {static_cast<float>(region.x),static_cast<float>(region.y),
        static_cast<float>(region.width),static_cast<float>(region.height)};
      result.sheetType = QrSheetTypeResolver::resolve(result.qr.text);
      if (result.valid()) {
        const auto second = result.qr.text.find(':', 3);
        result.studentCode = result.qr.text.substr(3, second - 3);
        result.identifier = result.qr.text.substr(second + 1);
        result.processingTimeMs = timer.elapsedMs();
        return result;
      }
      // Keep decoded invalid evidence; another orientation must not hide it.
      result.error = result.sheetType.status == "UNSUPPORTED_PREFIX"
        ? "sheet_type_qr_unsupported_prefix" : "sheet_type_qr_missing_or_invalid";
    }
  }
  if (result.error.empty()) result.error = "sheet_type_qr_missing_or_invalid";
  result.processingTimeMs = timer.elapsedMs();
  return result;
}
} // namespace omr
