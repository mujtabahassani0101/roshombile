#include "omr/NormalizeEngine.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <future>
#include <iterator>
#include <map>
#include <numeric>
#include <optional>
#include <sstream>
#include <utility>
#include <vector>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include "omr/AnswerReader.hpp"
#include "geometry/BubbleGridRefiner.hpp"
#include "geometry/Homography.hpp"
#include "geometry/SheetQuadEstimator.hpp"
#include "omr/BarcodeReader.hpp"
#include "quality/QualityAnalyzer.hpp"
#include "rails/SideDotRailDetector.hpp"
#include "utils/Json.hpp"
#include "utils/Timer.hpp"

namespace omr {

namespace {

constexpr int kCanonicalAnswerWidth = 1320;
constexpr int kCanonicalAnswerHeight = 1100;
constexpr float kAnswerPadLeftRatio = 0.035f;
constexpr float kAnswerPadRightRatio = 0.035f;
constexpr float kAnswerPadTopRatio = 0.060f;
constexpr float kAnswerPadBottomRatio = 0.030f;
// Keep overlays aligned with the physical bubble instead of drawing a small ring
// over the printed option label. Selected rings sit just outside the printed ring.
constexpr float kAnswerDebugDrawRadiusScale = 1.10f;
constexpr float kAnswerDebugSelectedRadiusScale = 1.35f;
constexpr float kStudentCodeDebugDrawRadiusScale = 1.30f;

float clamp(float value, float minValue, float maxValue) {
  return std::min(maxValue, std::max(minValue, value));
}

Rect scaleRectToImage(
  const Rect& rect,
  const OmrTemplate& tpl,
  const cv::Size& imageSize
) {
  const float scaleX = static_cast<float>(imageSize.width) /
    std::max(1.0f, tpl.width);
  const float scaleY = static_cast<float>(imageSize.height) /
    std::max(1.0f, tpl.height);
  return {
    rect.x * scaleX,
    rect.y * scaleY,
    rect.width * scaleX,
    rect.height * scaleY,
  };
}

OmrTemplate rawQrSearchTemplate(const OmrTemplate& tpl, const cv::Size& imageSize) {
  OmrTemplate raw = tpl;
  Rect search = tpl.examCodeBox.width > 0.0f && tpl.examCodeBox.height > 0.0f
    ? tpl.examCodeBox
    : tpl.barcode.roi;
  const float paddingX = std::max(12.0f, search.width * 0.12f);
  const float paddingY = std::max(12.0f, search.height * 0.20f);
  search = {
    search.x - paddingX,
    search.y - paddingY,
    search.width + paddingX * 2.0f,
    search.height + paddingY * 2.0f,
  };

  raw.width = static_cast<float>(imageSize.width);
  raw.height = static_cast<float>(imageSize.height);
  raw.examCodeBox = scaleRectToImage(search, tpl, imageSize);
  raw.barcode.roi = raw.examCodeBox;
  raw.barcode.fallbackRois.clear();
  return raw;
}

std::string makeOutputPath(
  const std::string& imagePath,
  const std::string& outputDirectory,
  const std::string& prefix,
  const std::string& extension = ".jpg"
) {
  namespace fs = std::filesystem;
  const auto stamp = std::chrono::duration_cast<std::chrono::milliseconds>(
                       std::chrono::system_clock::now().time_since_epoch())
                       .count();
  fs::path directory = outputDirectory.empty() ? fs::path(imagePath).parent_path() : fs::path(json::stripFileUri(outputDirectory));
  if (directory.empty()) {
    directory = ".";
  }
  fs::create_directories(directory);
  return (directory / (prefix + std::to_string(stamp) + extension)).string();
}

Point percentagePointToPixel(Point point, int imageWidth, int imageHeight) {
  const float maxX = static_cast<float>(std::max(0, imageWidth - 1));
  const float maxY = static_cast<float>(std::max(0, imageHeight - 1));
  return {
    clamp(point.x, 0.0f, 100.0f) * 0.01f * maxX,
    clamp(point.y, 0.0f, 100.0f) * 0.01f * maxY,
  };
}

std::vector<Point> percentagePointsToPixels(
  const std::vector<Point>& percentagePoints,
  int imageWidth,
  int imageHeight
) {
  std::vector<Point> pixels;
  pixels.reserve(percentagePoints.size());
  for (const Point point : percentagePoints) {
    pixels.push_back(percentagePointToPixel(point, imageWidth, imageHeight));
  }
  return pixels;
}

Point clampPoint(Point point, int imageWidth, int imageHeight) {
  return {
    clamp(point.x, 0.0f, static_cast<float>(std::max(0, imageWidth - 1))),
    clamp(point.y, 0.0f, static_cast<float>(std::max(0, imageHeight - 1))),
  };
}

Point add(Point lhs, Point rhs) {
  return {lhs.x + rhs.x, lhs.y + rhs.y};
}

Point subtract(Point lhs, Point rhs) {
  return {lhs.x - rhs.x, lhs.y - rhs.y};
}

Point multiply(Point point, float scalar) {
  return {point.x * scalar, point.y * scalar};
}

Point normalizedVector(Point from, Point to, float fallbackX, float fallbackY) {
  const Point vector = subtract(to, from);
  const float length = std::max(1.0f, distance(from, to));
  if (length <= 1.0f) {
    return {fallbackX, fallbackY};
  }
  return {vector.x / length, vector.y / length};
}

Quad expandQuadDirectional(
  const Quad& quad,
  int imageWidth,
  int imageHeight,
  float leftRatio = kAnswerPadLeftRatio,
  float rightRatio = kAnswerPadRightRatio,
  float topRatio = kAnswerPadTopRatio,
  float bottomRatio = kAnswerPadBottomRatio
) {
  Point topLeft = quad.topLeft;
  Point topRight = quad.topRight;
  Point bottomRight = quad.bottomRight;
  Point bottomLeft = quad.bottomLeft;

  const float widthTop = distance(topLeft, topRight);
  const float widthBottom = distance(bottomLeft, bottomRight);
  const float heightLeft = distance(topLeft, bottomLeft);
  const float heightRight = distance(topRight, bottomRight);
  const float averageWidth = (widthTop + widthBottom) * 0.5f;
  const float averageHeight = (heightLeft + heightRight) * 0.5f;

  const Point horizontalTop = normalizedVector(topLeft, topRight, 1.0f, 0.0f);
  const Point horizontalBottom = normalizedVector(bottomLeft, bottomRight, 1.0f, 0.0f);
  const Point verticalLeft = normalizedVector(topLeft, bottomLeft, 0.0f, 1.0f);
  const Point verticalRight = normalizedVector(topRight, bottomRight, 0.0f, 1.0f);

  topLeft = subtract(subtract(topLeft, multiply(horizontalTop, leftRatio * averageWidth)), multiply(verticalLeft, topRatio * averageHeight));
  topRight = subtract(add(topRight, multiply(horizontalTop, rightRatio * averageWidth)), multiply(verticalRight, topRatio * averageHeight));
  bottomRight = add(add(bottomRight, multiply(horizontalBottom, rightRatio * averageWidth)), multiply(verticalRight, bottomRatio * averageHeight));
  bottomLeft = add(subtract(bottomLeft, multiply(horizontalBottom, leftRatio * averageWidth)), multiply(verticalLeft, bottomRatio * averageHeight));

  return {
    clampPoint(topLeft, imageWidth, imageHeight),
    clampPoint(topRight, imageWidth, imageHeight),
    clampPoint(bottomRight, imageWidth, imageHeight),
    clampPoint(bottomLeft, imageWidth, imageHeight),
  };
}

bool isUsableAnchorQuad(const Quad& quad, int imageWidth, int imageHeight) {
  const float imageArea = static_cast<float>(std::max(1, imageWidth * imageHeight));
  const float area = polygonArea(quad);
  const float minSide = std::min({
    distance(quad.topLeft, quad.topRight),
    distance(quad.bottomLeft, quad.bottomRight),
    distance(quad.topLeft, quad.bottomLeft),
    distance(quad.topRight, quad.bottomRight),
  });
  return area > imageArea * 0.02f && minSide > 12.0f;
}

std::optional<Quad> semanticQuadFromPercentageAnchors(
  const std::vector<Point>& percentagePoints,
  int imageWidth,
  int imageHeight
) {
  if (percentagePoints.size() != 4 || imageWidth <= 0 || imageHeight <= 0) {
    return std::nullopt;
  }

  const std::vector<Point> pixels = percentagePointsToPixels(percentagePoints, imageWidth, imageHeight);
  const Quad semantic = {
    pixels[1], // point 2: top-left
    pixels[0], // point 1: top-right
    pixels[3], // point 4: bottom-right
    pixels[2], // point 3: bottom-left
  };
  const Quad expanded = expandQuadDirectional(semantic, imageWidth, imageHeight);
  if (!isUsableAnchorQuad(expanded, imageWidth, imageHeight)) {
    return std::nullopt;
  }
  return expanded;
}

Quad templateAnswerAnchorQuad(const OmrTemplate& tpl) {
  const float topY = tpl.answersRegion.y;
  const float bottomY = tpl.answersRegion.y + tpl.answersRegion.height - 1.0f;
  return {
    {tpl.registration.leftRailX, topY},
    {tpl.registration.rightRailX, topY},
    {tpl.registration.rightRailX, bottomY},
    {tpl.registration.leftRailX, bottomY},
  };
}

Quad expandedTemplateAnswerAnchorQuad(const OmrTemplate& tpl) {
  return expandQuadDirectional(
    templateAnswerAnchorQuad(tpl),
    static_cast<int>(std::round(tpl.width)),
    static_cast<int>(std::round(tpl.height))
  );
}

cv::Mat perspectiveMatrixForAnswerRegion(const Quad& source, const OmrTemplate& tpl) {
  const Quad destination = expandedTemplateAnswerAnchorQuad(tpl);
  const std::vector<cv::Point2f> src = {
    {source.topLeft.x, source.topLeft.y},
    {source.topRight.x, source.topRight.y},
    {source.bottomRight.x, source.bottomRight.y},
    {source.bottomLeft.x, source.bottomLeft.y},
  };
  const std::vector<cv::Point2f> dst = {
    {destination.topLeft.x, destination.topLeft.y},
    {destination.topRight.x, destination.topRight.y},
    {destination.bottomRight.x, destination.bottomRight.y},
    {destination.bottomLeft.x, destination.bottomLeft.y},
  };
  return cv::getPerspectiveTransform(src, dst);
}

cv::Size canonicalAnswerSize() {
  return {kCanonicalAnswerWidth, kCanonicalAnswerHeight};
}

cv::Mat perspectiveMatrixForCanonicalAnswerBlock(const Quad& source) {
  const std::vector<cv::Point2f> src = {
    {source.topLeft.x, source.topLeft.y},
    {source.topRight.x, source.topRight.y},
    {source.bottomRight.x, source.bottomRight.y},
    {source.bottomLeft.x, source.bottomLeft.y},
  };
  const std::vector<cv::Point2f> dst = {
    {0.0f, 0.0f},
    {static_cast<float>(kCanonicalAnswerWidth - 1), 0.0f},
    {static_cast<float>(kCanonicalAnswerWidth - 1), static_cast<float>(kCanonicalAnswerHeight - 1)},
    {0.0f, static_cast<float>(kCanonicalAnswerHeight - 1)},
  };
  return cv::getPerspectiveTransform(src, dst);
}

cv::Mat templateToCanonicalAnswerBlockMatrix(const OmrTemplate& tpl) {
  const Quad source = expandedTemplateAnswerAnchorQuad(tpl);
  const std::vector<cv::Point2f> src = {
    {source.topLeft.x, source.topLeft.y},
    {source.topRight.x, source.topRight.y},
    {source.bottomRight.x, source.bottomRight.y},
    {source.bottomLeft.x, source.bottomLeft.y},
  };
  const std::vector<cv::Point2f> dst = {
    {0.0f, 0.0f},
    {static_cast<float>(kCanonicalAnswerWidth - 1), 0.0f},
    {static_cast<float>(kCanonicalAnswerWidth - 1), static_cast<float>(kCanonicalAnswerHeight - 1)},
    {0.0f, static_cast<float>(kCanonicalAnswerHeight - 1)},
  };
  return cv::getPerspectiveTransform(src, dst);
}

Point projectPoint(const cv::Mat& matrix, Point point) {
  if (matrix.empty()) {
    return point;
  }

  cv::Mat h;
  matrix.convertTo(h, CV_64F);
  const double x = point.x;
  const double y = point.y;
  const double denominator = h.at<double>(2, 0) * x + h.at<double>(2, 1) * y + h.at<double>(2, 2);
  if (std::abs(denominator) < 1e-9) {
    return {};
  }
  return {
    static_cast<float>((h.at<double>(0, 0) * x + h.at<double>(0, 1) * y + h.at<double>(0, 2)) / denominator),
    static_cast<float>((h.at<double>(1, 0) * x + h.at<double>(1, 1) * y + h.at<double>(1, 2)) / denominator),
  };
}

OmrRailReprojectionStats validateRailReprojection(
  const cv::Mat& sourceToTemplate,
  const std::vector<MatchedRailRow>& matchedRows,
  const OmrTemplate& tpl
) {
  OmrRailReprojectionStats stats;
  if (sourceToTemplate.empty() || matchedRows.empty()) {
    return stats;
  }

  struct ErrorSample {
    double error = 0.0;
    float expectedY = 0.0f;
  };
  std::vector<ErrorSample> samples;
  const auto& studentBubbleYs = tpl.studentCode.bubbleYs();
  const auto [studentMinIt, studentMaxIt] = studentBubbleYs.empty()
    ? std::pair<std::vector<float>::const_iterator, std::vector<float>::const_iterator>{tpl.registration.rowY.begin(), tpl.registration.rowY.begin()}
    : std::minmax_element(studentBubbleYs.begin(), studentBubbleYs.end());
  const float studentTop = studentBubbleYs.empty() ? tpl.answersRegion.y : *studentMinIt;
  const float studentBottom = studentBubbleYs.empty() ? tpl.answersRegion.y : *studentMaxIt;

  for (const auto& match : matchedRows) {
    const Point projected = projectPoint(sourceToTemplate, match.detected);
    const Point expected{match.canonicalX, match.canonicalY};
    samples.push_back({static_cast<double>(distance(projected, expected)), expected.y});
  }
  if (samples.empty()) {
    return stats;
  }

  double sum = 0.0;
  double sumSq = 0.0;
  std::vector<double> headerSq;
  std::vector<double> studentSq;
  std::vector<double> answerSq;
  for (const auto& sample : samples) {
    sum += sample.error;
    sumSq += sample.error * sample.error;
    stats.maxError = std::max(stats.maxError, sample.error);
    if (sample.expectedY < studentTop) {
      headerSq.push_back(sample.error * sample.error);
      stats.headerSamples += 1;
    } else if (sample.expectedY <= studentBottom + 40.0f) {
      studentSq.push_back(sample.error * sample.error);
      stats.studentCodeSamples += 1;
    } else if (
      sample.expectedY >= tpl.answersRegion.y - 40.0f &&
      sample.expectedY <= tpl.answersRegion.y + tpl.answersRegion.height + 40.0f
    ) {
      answerSq.push_back(sample.error * sample.error);
      stats.answerSamples += 1;
    }
  }

  const auto rms = [](const std::vector<double>& values) {
    if (values.empty()) {
      return 0.0;
    }
    return std::sqrt(std::accumulate(values.begin(), values.end(), 0.0) / static_cast<double>(values.size()));
  };

  stats.sampleCount = static_cast<int>(samples.size());
  stats.meanError = sum / static_cast<double>(samples.size());
  stats.rmsError = std::sqrt(sumSq / static_cast<double>(samples.size()));
  stats.headerRms = rms(headerSq);
  stats.studentCodeRms = rms(studentSq);
  stats.answerRms = rms(answerSq);
  return stats;
}

float projectedRadius(const cv::Mat& matrix, Point center, float radius) {
  const Point projectedCenter = projectPoint(matrix, center);
  const Point projectedX = projectPoint(matrix, {center.x + radius, center.y});
  const Point projectedY = projectPoint(matrix, {center.x, center.y + radius});
  return std::max(2.0f, (distance(projectedCenter, projectedX) + distance(projectedCenter, projectedY)) * 0.5f);
}

const OmrAnswerQuestionResult* questionResultFor(const OmrAnswerReadResult& answers, int questionNumber) {
  for (const auto& question : answers.questions) {
    if (question.questionNumber == questionNumber) {
      return &question;
    }
  }
  return nullptr;
}

bool questionSelectedOption(const OmrAnswerQuestionResult* question, const std::string& option) {
  if (question == nullptr) {
    return false;
  }
  return std::find(question->selectedOptions.begin(), question->selectedOptions.end(), option) != question->selectedOptions.end();
}

const OmrAnswerOptionScore* answerOptionScoreFor(
  const OmrAnswerQuestionResult* question,
  const std::string& option
) {
  if (question == nullptr) {
    return nullptr;
  }
  for (const auto& score : question->options) {
    if (score.option == option) {
      return &score;
    }
  }
  return nullptr;
}

const OmrStudentCodeColumnResult* studentColumnResultFor(
  const OmrStudentCodeReadResult& code,
  int visualColumnIndex
) {
  for (const auto& column : code.columns) {
    if (column.visualColumnIndex == visualColumnIndex) {
      return &column;
    }
  }
  return nullptr;
}

cv::Scalar selectedAnswerDebugColor(const OmrAnswerQuestionResult* question) {
  if (question == nullptr) {
    return cv::Scalar(190, 190, 190);
  }
  if (question->status == "MARKED") {
    return cv::Scalar(0, 180, 0);
  }
  return cv::Scalar(0, 0, 220);
}

void drawAnchorDebugImage(
  const cv::Mat& image,
  const Quad& quad,
  const std::string& imagePath,
  const std::string& outputDirectory,
  NormalizeResult& result
) {
  if (image.empty()) {
    return;
  }

  cv::Mat debug = image.clone();
  const std::vector<cv::Point> polygon = {
    cv::Point(static_cast<int>(std::round(quad.topLeft.x)), static_cast<int>(std::round(quad.topLeft.y))),
    cv::Point(static_cast<int>(std::round(quad.topRight.x)), static_cast<int>(std::round(quad.topRight.y))),
    cv::Point(static_cast<int>(std::round(quad.bottomRight.x)), static_cast<int>(std::round(quad.bottomRight.y))),
    cv::Point(static_cast<int>(std::round(quad.bottomLeft.x)), static_cast<int>(std::round(quad.bottomLeft.y))),
  };
  const std::vector<std::vector<cv::Point>> contours = {polygon};
  cv::polylines(debug, contours, true, cv::Scalar(0, 200, 255), 4, cv::LINE_AA);

  const std::array<std::pair<Point, const char*>, 4> labels = {{
    {quad.topLeft, "TL"},
    {quad.topRight, "TR"},
    {quad.bottomLeft, "BL"},
    {quad.bottomRight, "BR"},
  }};
  for (const auto& [point, label] : labels) {
    const cv::Point center(static_cast<int>(std::round(point.x)), static_cast<int>(std::round(point.y)));
    cv::circle(debug, center, 10, cv::Scalar(0, 0, 255), -1, cv::LINE_AA);
    cv::putText(
      debug,
      label,
      center + cv::Point(12, -8),
      cv::FONT_HERSHEY_SIMPLEX,
      0.75,
      cv::Scalar(0, 0, 255),
      2,
      cv::LINE_AA
    );
  }

  const std::string debugPath = makeOutputPath(imagePath, outputDirectory, "omr-anchor-quad-");
  if (cv::imwrite(debugPath, debug)) {
    result.debugImagePaths.push_back(debugPath);
  }
}

void drawSideRailDebugImage(
  const cv::Mat& image,
  const RailDetection& rails,
  const Quad& quad,
  const SheetDetection& sheet,
  const std::string& imagePath,
  const std::string& outputDirectory,
  NormalizeResult& result
) {
  if (image.empty()) {
    return;
  }

  cv::Mat candidatesDebug = image.clone();
  std::map<std::string, int> rejectedByReason;
  for (const auto& rejected : rails.rejectedCandidates) {
    rejectedByReason[rejected.reason.empty() ? "unknown" : rejected.reason] += 1;
    const cv::Point center(static_cast<int>(std::round(rejected.x)), static_cast<int>(std::round(rejected.y)));
    cv::drawMarker(candidatesDebug, center, cv::Scalar(80, 80, 220), cv::MARKER_TILTED_CROSS, 5, 1, cv::LINE_AA);
  }
  const auto drawDots = [&](const std::vector<DotCandidate>& dots, cv::Scalar color) {
    for (const auto& dot : dots) {
      cv::circle(
        candidatesDebug,
        cv::Point(static_cast<int>(std::round(dot.x)), static_cast<int>(std::round(dot.y))),
        std::max(3, static_cast<int>(std::round(dot.radius * 1.25f))),
        color,
        2,
        cv::LINE_AA
      );
    }
  };
  drawDots(rails.left.dots, cv::Scalar(0, 220, 0));
  drawDots(rails.right.dots, cv::Scalar(255, 80, 0));

  cv::putText(
    candidatesDebug,
    "rail mode: " + rails.railDetectionMode + "  left=" + std::to_string(rails.left.dots.size()) +
      " right=" + std::to_string(rails.right.dots.size()) +
      " rejected=" + std::to_string(rails.rejectedCandidateCount),
    cv::Point(24, 42),
    cv::FONT_HERSHEY_SIMPLEX,
    0.75,
    cv::Scalar(0, 120, 255),
    2,
    cv::LINE_AA
  );
  int rejectLabelY = 72;
  for (const auto& [reason, count] : rejectedByReason) {
    cv::putText(
      candidatesDebug,
      "rejectedBy" + reason + "=" + std::to_string(count),
      cv::Point(24, rejectLabelY),
      cv::FONT_HERSHEY_SIMPLEX,
      0.52,
      cv::Scalar(80, 80, 220),
      1,
      cv::LINE_AA
    );
    rejectLabelY += 22;
    if (rejectLabelY > 170) {
      break;
    }
  }

  const std::string candidatesPath = makeOutputPath(imagePath, outputDirectory, "original_rail_candidates-");
  if (cv::imwrite(candidatesPath, candidatesDebug)) {
    result.debugImagePaths.push_back(candidatesPath);
  }

  cv::Mat debug = candidatesDebug.clone();
  const std::vector<cv::Point> polygon = {
    cv::Point(static_cast<int>(std::round(quad.topLeft.x)), static_cast<int>(std::round(quad.topLeft.y))),
    cv::Point(static_cast<int>(std::round(quad.topRight.x)), static_cast<int>(std::round(quad.topRight.y))),
    cv::Point(static_cast<int>(std::round(quad.bottomRight.x)), static_cast<int>(std::round(quad.bottomRight.y))),
    cv::Point(static_cast<int>(std::round(quad.bottomLeft.x)), static_cast<int>(std::round(quad.bottomLeft.y))),
  };
  const std::vector<std::vector<cv::Point>> contours = {polygon};
  cv::polylines(debug, contours, true, cv::Scalar(0, 200, 255), 4, cv::LINE_AA);
  std::ostringstream label;
  label << "strategy=" << sheet.homographyStrategyUsed
        << " used L/R=" << sheet.leftDotsUsed << "/" << sheet.rightDotsUsed
        << " rows=" << sheet.matchedRowsUsed
        << " reproj=" << sheet.reprojectionError
        << " railRms=" << result.railReprojection.rmsError
        << " answerRms=" << result.railReprojection.answerRms;
  cv::putText(
    debug,
    label.str(),
    cv::Point(24, 82),
    cv::FONT_HERSHEY_SIMPLEX,
    0.72,
    cv::Scalar(0, 120, 255),
    2,
    cv::LINE_AA
  );

  const std::string debugPath = makeOutputPath(imagePath, outputDirectory, "original_quad_overlay-");
  if (cv::imwrite(debugPath, debug)) {
    result.debugImagePaths.push_back(debugPath);
  }
}

cv::Rect clampedCvRect(Rect rect, const cv::Size& size) {
  const int x = static_cast<int>(std::floor(clamp(rect.x, 0.0f, static_cast<float>(std::max(0, size.width - 1)))));
  const int y = static_cast<int>(std::floor(clamp(rect.y, 0.0f, static_cast<float>(std::max(0, size.height - 1)))));
  const int maxWidth = std::max(0, size.width - x);
  const int maxHeight = std::max(0, size.height - y);
  const int width = static_cast<int>(std::ceil(clamp(rect.width, 1.0f, static_cast<float>(maxWidth))));
  const int height = static_cast<int>(std::ceil(clamp(rect.height, 1.0f, static_cast<float>(maxHeight))));
  return {x, y, width, height};
}

std::vector<float> authoritativeAnswerRowYs(const OmrTemplate& tpl) {
  int expectedRows = 0;
  for (const auto& block : tpl.answers.blocks) {
    expectedRows = std::max(expectedRows, block.questionCount);
  }

  // Keep debug geometry aligned with the same 30 timing rows used by the reader.
  // Raw magenta boxes are only horizontal/debug hints, not vertical grid sources.
  std::vector<float> rows;
  const float top = tpl.answersRegion.y - 1.0f;
  const float bottom = tpl.answersRegion.y + tpl.answersRegion.height + 1.0f;
  for (const float y : tpl.registration.rowY) {
    if (y >= top && y <= bottom) {
      rows.push_back(y);
    }
  }
  std::sort(rows.begin(), rows.end());
  if (expectedRows > 0 && static_cast<int>(rows.size()) >= expectedRows) {
    rows.resize(static_cast<size_t>(expectedRows));
    return rows;
  }

  for (const auto& block : tpl.answers.blocks) {
    if (expectedRows > 0 && static_cast<int>(block.yByRow.size()) >= expectedRows) {
      return std::vector<float>(block.yByRow.begin(), block.yByRow.begin() + expectedRows);
    }
  }
  return rows;
}

float answerRowY(
  const std::vector<float>& authoritativeRows,
  const OmrAnswerBlockTemplate& block,
  int row
) {
  if (row >= 0 && row < static_cast<int>(authoritativeRows.size())) {
    return authoritativeRows[static_cast<size_t>(row)];
  }
  return block.yByRow[static_cast<size_t>(row)];
}

int drawableRowsForBlock(
  const std::vector<float>& authoritativeRows,
  const OmrAnswerBlockTemplate& block
) {
  const int availableRows = !authoritativeRows.empty()
    ? static_cast<int>(authoritativeRows.size())
    : static_cast<int>(block.yByRow.size());
  return std::min(block.questionCount, availableRows);
}

float medianAnswerPitch(const std::vector<float>& rowYs) {
  std::vector<float> gaps;
  gaps.reserve(rowYs.size());
  for (size_t index = 1; index < rowYs.size(); ++index) {
    const float gap = rowYs[index] - rowYs[index - 1];
    if (gap > 1.0f) {
      gaps.push_back(gap);
    }
  }
  if (gaps.empty()) {
    return 40.0f;
  }
  std::sort(gaps.begin(), gaps.end());
  return gaps[gaps.size() / 2];
}

Rect inferredAnswerBlockRect(
  const OmrAnswerBlockTemplate& block,
  const std::vector<float>& rowYs,
  float pitch
) {
  if (block.hasBbox) {
    return block.bbox;
  }
  if (block.xByOption.empty()) {
    return {};
  }

  auto [minXIt, maxXIt] = std::minmax_element(
    block.xByOption.begin(),
    block.xByOption.end(),
    [](const auto& lhs, const auto& rhs) {
      return lhs.second < rhs.second;
    }
  );
  const float padX = block.bubbleRadius * 2.6f;
  const float top = !rowYs.empty() ? rowYs.front() - pitch : 0.0f;
  const float bottom = !rowYs.empty() ? rowYs.back() + pitch : top + 1.0f;
  return {
    minXIt->second - padX,
    top,
    (maxXIt->second - minXIt->second) + padX * 2.0f,
    std::max(1.0f, bottom - top),
  };
}

Rect correctedAnswerContentRect(
  const OmrAnswerBlockTemplate& block,
  const std::vector<float>& rowYs,
  float pitch
) {
  Rect horizontal = inferredAnswerBlockRect(block, rowYs, pitch);
  if (rowYs.empty()) {
    return horizontal;
  }

  const float contentTop = rowYs.front() - 0.45f * pitch;
  const float contentBottom = rowYs.back() + 0.45f * pitch;
  horizontal.y = contentTop;
  horizontal.height = std::max(1.0f, contentBottom - contentTop);
  return horizontal;
}

void drawRectRelativeToRoi(
  cv::Mat& image,
  Rect rect,
  const cv::Rect& roi,
  cv::Scalar color,
  int thickness
) {
  const cv::Point topLeft(
    static_cast<int>(std::round(rect.x - static_cast<float>(roi.x))),
    static_cast<int>(std::round(rect.y - static_cast<float>(roi.y)))
  );
  const cv::Point bottomRight(
    static_cast<int>(std::round(rect.x + rect.width - static_cast<float>(roi.x))),
    static_cast<int>(std::round(rect.y + rect.height - static_cast<float>(roi.y)))
  );
  cv::rectangle(image, topLeft, bottomRight, color, thickness, cv::LINE_AA);
}

void drawProjectedRect(
  cv::Mat& image,
  const cv::Mat& matrix,
  Rect rect,
  cv::Scalar color,
  int thickness
) {
  const Point topLeft = projectPoint(matrix, {rect.x, rect.y});
  const Point topRight = projectPoint(matrix, {rect.x + rect.width, rect.y});
  const Point bottomRight = projectPoint(matrix, {rect.x + rect.width, rect.y + rect.height});
  const Point bottomLeft = projectPoint(matrix, {rect.x, rect.y + rect.height});
  const std::vector<cv::Point> points = {
    cv::Point(static_cast<int>(std::round(topLeft.x)), static_cast<int>(std::round(topLeft.y))),
    cv::Point(static_cast<int>(std::round(topRight.x)), static_cast<int>(std::round(topRight.y))),
    cv::Point(static_cast<int>(std::round(bottomRight.x)), static_cast<int>(std::round(bottomRight.y))),
    cv::Point(static_cast<int>(std::round(bottomLeft.x)), static_cast<int>(std::round(bottomLeft.y))),
  };
  const std::vector<std::vector<cv::Point>> contours = {points};
  cv::polylines(image, contours, true, color, thickness, cv::LINE_AA);
}

void drawWarpedAnswerDebugImage(
  const cv::Mat& normalized,
  const OmrTemplate& tpl,
  const OmrAnswerReadResult& answers,
  const BubbleGridRefinement* refinement,
  const std::string& imagePath,
  const std::string& outputDirectory,
  NormalizeResult& result
) {
  if (normalized.empty() || !tpl.answers.enabled) {
    return;
  }

  const cv::Rect roi = clampedCvRect(tpl.answersRegion, normalized.size());
  if (roi.empty()) {
    return;
  }

  cv::Mat debug = normalized(roi).clone();
  const std::vector<float> rowYs = authoritativeAnswerRowYs(tpl);
  const float pitch = medianAnswerPitch(rowYs);
  for (const auto& block : tpl.answers.blocks) {
    if (block.hasBbox) {
      drawRectRelativeToRoi(debug, block.bbox, roi, cv::Scalar(255, 190, 80), 1);
    }
    drawRectRelativeToRoi(
      debug,
      correctedAnswerContentRect(block, rowYs, pitch),
      roi,
      cv::Scalar(0, 150, 255),
      2
    );

    const int drawRadius = std::max(2, static_cast<int>(std::round(block.bubbleRadius * kAnswerDebugDrawRadiusScale)));
    const int selectedRadius = std::max(drawRadius + 1, static_cast<int>(std::round(block.bubbleRadius * kAnswerDebugSelectedRadiusScale)));
    const int rowCount = drawableRowsForBlock(rowYs, block);
    for (int row = 0; row < rowCount; ++row) {
      const int questionNumber = block.questionStart + row;
      const OmrAnswerQuestionResult* question = questionResultFor(answers, questionNumber);
      for (const auto& option : tpl.answers.options) {
        const auto xIt = block.xByOption.find(option);
        if (xIt == block.xByOption.end()) {
          continue;
        }
        Point templateCenter{xIt->second, answerRowY(rowYs, block, row)};
        Point drawCenter = templateCenter;
        const OmrAnswerOptionScore* optionScore = answerOptionScoreFor(question, option);
        if (optionScore != nullptr) {
          drawCenter = {
            static_cast<float>(optionScore->refinedX),
            static_cast<float>(optionScore->refinedY),
          };
        }
        const cv::Point center(
          static_cast<int>(std::round(drawCenter.x - static_cast<float>(roi.x))),
          static_cast<int>(std::round(drawCenter.y - static_cast<float>(roi.y)))
        );
        if (center.x < 0 || center.y < 0 || center.x >= debug.cols || center.y >= debug.rows) {
          continue;
        }

        cv::circle(debug, center, drawRadius, cv::Scalar(190, 190, 190), 1, cv::LINE_AA);
        if (questionSelectedOption(question, option)) {
          cv::circle(debug, center, selectedRadius, selectedAnswerDebugColor(question), 2, cv::LINE_AA);
        }
      }
    }
  }

  const std::string debugPath = makeOutputPath(imagePath, outputDirectory, "normalized_answer_scoring_overlay-");
  if (cv::imwrite(debugPath, debug)) {
    result.debugImagePaths.push_back(debugPath);
  }
}

void drawStudentCodeDebugImage(
  const cv::Mat& normalized,
  const OmrTemplate& tpl,
  const OmrStudentCodeReadResult& code,
  const BubbleGridRefinement* refinement,
  const std::string& imagePath,
  const std::string& outputDirectory,
  NormalizeResult& result
) {
  if (
    normalized.empty() ||
    !tpl.studentCode.enabled ||
    tpl.studentCode.bubbleXs().empty() ||
    tpl.studentCode.bubbleYs().empty() ||
    !code.available
  ) {
    return;
  }

  const auto& bubbleXs = tpl.studentCode.bubbleXs();
  const auto& bubbleYs = tpl.studentCode.bubbleYs();
  const auto& digitLabels = tpl.studentCode.digitLabelsByChoiceOrder();
  const auto [minXIt, maxXIt] = std::minmax_element(
    bubbleXs.begin(),
    bubbleXs.end()
  );
  const auto [minYIt, maxYIt] = std::minmax_element(
    bubbleYs.begin(),
    bubbleYs.end()
  );
  const float pad = tpl.studentCode.bubbleRadius * 3.0f;
  const Rect debugRect{
    *minXIt - pad,
    *minYIt - pad - 42.0f,
    (*maxXIt - *minXIt) + pad * 2.0f,
    (*maxYIt - *minYIt) + pad * 2.0f + 42.0f,
  };
  const cv::Rect roi = clampedCvRect(debugRect, normalized.size());
  if (roi.empty()) {
    return;
  }

  cv::Mat debug = normalized(roi).clone();
  const int drawRadius = std::max(
    3,
    static_cast<int>(std::round(tpl.studentCode.bubbleRadius * kStudentCodeDebugDrawRadiusScale))
  );

  if (!code.value.empty()) {
    cv::putText(
      debug,
      std::string("student_id: ") + code.value,
      cv::Point(8, std::max(18, static_cast<int>(std::round(pad * 0.55f)))),
      cv::FONT_HERSHEY_SIMPLEX,
      0.65,
      cv::Scalar(0, 130, 0),
      2,
      cv::LINE_AA
    );
  }

  for (size_t visualIndex = 0; visualIndex < static_cast<size_t>(tpl.studentCode.columns); ++visualIndex) {
    const OmrStudentCodeColumnResult* column = studentColumnResultFor(code, static_cast<int>(visualIndex));
    if (column == nullptr || !column->hasSelectedDigit) {
      continue;
    }

    const auto digitIt = std::find(
      digitLabels.begin(),
      digitLabels.end(),
      column->selectedDigit
    );
    if (digitIt == digitLabels.end()) {
      continue;
    }

    const size_t digitIndex = static_cast<size_t>(std::distance(digitLabels.begin(), digitIt));
    if (digitIndex >= digitLabels.size()) {
      continue;
    }

    Point drawCenter = tpl.studentCode.bubbleCenter(visualIndex, digitIndex);
    for (const auto& digit : column->digits) {
      if (digit.digit == column->selectedDigit) {
        drawCenter = {
          static_cast<float>(digit.refinedX),
          static_cast<float>(digit.refinedY),
        };
        break;
      }
    }

    const cv::Point center(
      static_cast<int>(std::round(drawCenter.x - static_cast<float>(roi.x))),
      static_cast<int>(std::round(drawCenter.y - static_cast<float>(roi.y)))
    );
    if (center.x < 0 || center.y < 0 || center.x >= debug.cols || center.y >= debug.rows) {
      continue;
    }
    cv::circle(debug, center, drawRadius, cv::Scalar(0, 180, 0), 2, cv::LINE_AA);
  }

  const std::string debugPath = makeOutputPath(imagePath, outputDirectory, "omr-student-id-");
  if (cv::imwrite(debugPath, debug)) {
    result.debugImagePaths.push_back(debugPath);
  }
}

void saveDebugImage(
  const cv::Mat& image,
  const std::string& imagePath,
  const std::string& outputDirectory,
  const std::string& prefix,
  NormalizeResult& result
) {
  if (image.empty()) {
    return;
  }

  const std::string debugPath = makeOutputPath(imagePath, outputDirectory, prefix);
  if (cv::imwrite(debugPath, image)) {
    result.debugImagePaths.push_back(debugPath);
  }
}

void drawCanonicalAnswerDebugImage(
  const cv::Mat& answerBlock,
  const OmrTemplate& tpl,
  const cv::Mat& templateToAnswerBlock,
  const OmrAnswerReadResult& answers,
  const std::string& imagePath,
  const std::string& outputDirectory,
  NormalizeResult& result
) {
  if (answerBlock.empty() || templateToAnswerBlock.empty() || !tpl.answers.enabled) {
    return;
  }

  cv::Mat debug = answerBlock.clone();
  const std::vector<float> rowYs = authoritativeAnswerRowYs(tpl);
  const float pitch = medianAnswerPitch(rowYs);
  for (const auto& block : tpl.answers.blocks) {
    if (block.hasBbox) {
      drawProjectedRect(debug, templateToAnswerBlock, block.bbox, cv::Scalar(255, 190, 80), 1);
    }
    drawProjectedRect(
      debug,
      templateToAnswerBlock,
      correctedAnswerContentRect(block, rowYs, pitch),
      cv::Scalar(0, 150, 255),
      2
    );

    const int rowCount = drawableRowsForBlock(rowYs, block);
    for (int row = 0; row < rowCount; ++row) {
      const int questionNumber = block.questionStart + row;
      const OmrAnswerQuestionResult* question = questionResultFor(answers, questionNumber);
      for (const auto& option : tpl.answers.options) {
        const auto xIt = block.xByOption.find(option);
        if (xIt == block.xByOption.end()) {
          continue;
        }

        const Point templateCenter{xIt->second, answerRowY(rowYs, block, row)};
        const Point center = projectPoint(templateToAnswerBlock, templateCenter);
        const float drawRadius = projectedRadius(
          templateToAnswerBlock,
          templateCenter,
          block.bubbleRadius * kAnswerDebugDrawRadiusScale
        );
        const float selectedRadius = projectedRadius(
          templateToAnswerBlock,
          templateCenter,
          block.bubbleRadius * kAnswerDebugSelectedRadiusScale
        );
        if (
          center.x < 0.0f ||
          center.y < 0.0f ||
          center.x >= static_cast<float>(debug.cols) ||
          center.y >= static_cast<float>(debug.rows)
        ) {
          continue;
        }

        cv::circle(
          debug,
          cv::Point(static_cast<int>(std::round(center.x)), static_cast<int>(std::round(center.y))),
          std::max(2, static_cast<int>(std::round(drawRadius))),
          cv::Scalar(190, 190, 190),
          1,
          cv::LINE_AA
        );

        if (questionSelectedOption(question, option)) {
          cv::circle(
            debug,
            cv::Point(static_cast<int>(std::round(center.x)), static_cast<int>(std::round(center.y))),
            std::max(3, static_cast<int>(std::round(selectedRadius))),
            selectedAnswerDebugColor(question),
            2,
            cv::LINE_AA
          );
        }
      }
    }
  }

  const std::string debugPath = makeOutputPath(imagePath, outputDirectory, "omr-canonical-answer-grid-");
  if (cv::imwrite(debugPath, debug)) {
    result.debugImagePaths.push_back(debugPath);
  }
}

void drawNormalizedTemplateOverlayImage(
  const cv::Mat& normalized,
  const OmrTemplate& tpl,
  const BubbleGridRefinement* refinement,
  const std::string& imagePath,
  const std::string& outputDirectory,
  NormalizeResult& result
) {
  if (normalized.empty()) {
    return;
  }

  cv::Mat debug = normalized.clone();
  const int railRadius = std::max(2, static_cast<int>(std::round(tpl.registration.dotRadius * 0.22f)));
  for (const float y : tpl.registration.rowY) {
    cv::circle(debug, cv::Point(static_cast<int>(std::round(tpl.registration.leftRailX)), static_cast<int>(std::round(y))),
               railRadius, cv::Scalar(0, 180, 255), 1, cv::LINE_AA);
    cv::circle(debug, cv::Point(static_cast<int>(std::round(tpl.registration.rightRailX)), static_cast<int>(std::round(y))),
               railRadius, cv::Scalar(0, 180, 255), 1, cv::LINE_AA);
  }

  if (tpl.studentCode.enabled) {
    const int studentRadius = std::max(2, static_cast<int>(std::round(tpl.studentCode.bubbleRadius * 0.25f)));
    for (const float x : tpl.studentCode.bubbleXs()) {
      for (const float y : tpl.studentCode.bubbleYs()) {
        cv::drawMarker(
          debug,
          cv::Point(static_cast<int>(std::round(x)), static_cast<int>(std::round(y))),
          cv::Scalar(255, 120, 0),
          cv::MARKER_CROSS,
          studentRadius * 2 + 1,
          1,
          cv::LINE_AA
        );
      }
    }
    if (refinement != nullptr) {
      for (const auto& center : refinement->studentCodeCenters) {
        cv::drawMarker(
          debug,
          cv::Point(static_cast<int>(std::round(center.refined.x)), static_cast<int>(std::round(center.refined.y))),
          cv::Scalar(0, 180, 0),
          cv::MARKER_CROSS,
          studentRadius * 2 + 1,
          1,
          cv::LINE_AA
        );
      }
    }
  }

  const std::vector<float> rowYs = authoritativeAnswerRowYs(tpl);
  const float pitch = medianAnswerPitch(rowYs);
  for (const auto& block : tpl.answers.blocks) {
    drawRectRelativeToRoi(debug, correctedAnswerContentRect(block, rowYs, pitch), cv::Rect(0, 0, normalized.cols, normalized.rows),
                          cv::Scalar(0, 150, 255), 1);
    const int answerRadius = std::max(2, static_cast<int>(std::round(block.bubbleRadius * 0.25f)));
    const int rowCount = drawableRowsForBlock(rowYs, block);
    for (int row = 0; row < rowCount; ++row) {
      const float y = answerRowY(rowYs, block, row);
      for (const auto& option : tpl.answers.options) {
        const auto xIt = block.xByOption.find(option);
        if (xIt == block.xByOption.end()) {
          continue;
        }
        Point drawCenter{xIt->second, y};
        if (refinement != nullptr) {
          for (const auto& refined : refinement->answerCenters) {
            if (
              refined.blockId == block.id &&
              refined.questionNumber == block.questionStart + row &&
              refined.rowIndex == row &&
              refined.option == option
            ) {
              drawCenter = refined.refined;
              break;
            }
          }
        }
        cv::drawMarker(
          debug,
          cv::Point(static_cast<int>(std::round(drawCenter.x)), static_cast<int>(std::round(drawCenter.y))),
          refinement == nullptr ? cv::Scalar(180, 180, 180) : cv::Scalar(0, 180, 0),
          cv::MARKER_CROSS,
          answerRadius * 2 + 1,
          1,
          cv::LINE_AA
        );
      }
    }
  }

  const std::string debugPath = makeOutputPath(imagePath, outputDirectory, "normalized_template_overlay-");
  if (cv::imwrite(debugPath, debug)) {
    result.debugImagePaths.push_back(debugPath);
  }
}

void drawBubbleRefinementOverlayImage(
  const cv::Mat& normalized,
  const BubbleGridRefinement& refinement,
  const std::string& imagePath,
  const std::string& outputDirectory,
  NormalizeResult& result
) {
  if (normalized.empty()) {
    return;
  }

  cv::Mat debug = normalized.clone();
  const auto drawPair = [&](Point expected, Point detected, bool hasDetected, Point refined, float confidence) {
    const cv::Point expectedPoint(static_cast<int>(std::round(expected.x)), static_cast<int>(std::round(expected.y)));
    const cv::Point detectedPoint(static_cast<int>(std::round(detected.x)), static_cast<int>(std::round(detected.y)));
    const cv::Point refinedPoint(static_cast<int>(std::round(refined.x)), static_cast<int>(std::round(refined.y)));
    cv::drawMarker(debug, expectedPoint, cv::Scalar(170, 170, 170), cv::MARKER_CROSS, 7, 1, cv::LINE_AA);
    if (hasDetected) {
      cv::circle(debug, detectedPoint, 3, cv::Scalar(255, 100, 0), -1, cv::LINE_AA);
    }
    cv::drawMarker(debug, refinedPoint, cv::Scalar(0, 190, 0), cv::MARKER_TILTED_CROSS, 7, 1, cv::LINE_AA);
    if (distance(expected, refined) >= 0.75f) {
      const cv::Scalar lineColor = confidence >= 0.35f ? cv::Scalar(0, 170, 255) : cv::Scalar(90, 90, 220);
      cv::line(debug, expectedPoint, refinedPoint, lineColor, 1, cv::LINE_AA);
    }
  };

  for (const auto& center : refinement.answerCenters) {
    drawPair(center.expected, center.detected, center.hasDetected, center.refined, center.confidence);
  }
  for (const auto& center : refinement.studentCodeCenters) {
    drawPair(center.expected, center.detected, center.hasDetected, center.refined, center.confidence);
  }

  int labelY = 38;
  const auto drawStats = [&](const OmrBubbleGridStats& stats, const std::string& label) {
    std::ostringstream text;
    text << label
         << " dx=" << stats.meanDx
         << " dy=" << stats.meanDy
         << " rms=" << stats.rmsError
         << " cov=" << stats.detectedCoverage
         << " conf=" << stats.confidence;
    cv::putText(
      debug,
      text.str(),
      cv::Point(24, labelY),
      cv::FONT_HERSHEY_SIMPLEX,
      0.58,
      cv::Scalar(0, 120, 255),
      2,
      cv::LINE_AA
    );
    labelY += 28;
  };

  for (const auto& stats : refinement.answerBlockStats) {
    drawStats(stats, stats.blockId.empty() ? "answer_block" : stats.blockId);
  }
  if (refinement.studentCodeStats.expectedCentersCount > 0) {
    drawStats(refinement.studentCodeStats, "student_code");
  }

  const std::string debugPath = makeOutputPath(imagePath, outputDirectory, "normalized_bubble_refinement_overlay-");
  if (cv::imwrite(debugPath, debug)) {
    result.debugImagePaths.push_back(debugPath);
  }
}

std::string csvEscape(const std::string& value);

std::vector<OmrAnswerBlockRowResidualSummary> summarizeRowResiduals(
  const std::vector<OmrAnswerRowResidual>& residuals
) {
  std::map<std::string, std::vector<OmrAnswerRowResidual>> byBlock;
  for (const auto& residual : residuals) {
    byBlock[residual.blockId].push_back(residual);
  }

  const auto rmsRange = [](const std::vector<OmrAnswerRowResidual>& rows, int begin, int end, bool after) {
    double sum = 0.0;
    int count = 0;
    for (const auto& row : rows) {
      if (row.rowIndex < begin || row.rowIndex > end || row.detectedCount <= 0) {
        continue;
      }
      const double value = after ? row.afterRms : row.beforeRms;
      sum += value * value;
      count += 1;
    }
    return count > 0 ? std::sqrt(sum / static_cast<double>(count)) : 0.0;
  };

  std::vector<OmrAnswerBlockRowResidualSummary> summaries;
  for (auto& [blockId, rows] : byBlock) {
    std::sort(rows.begin(), rows.end(), [](const auto& lhs, const auto& rhs) {
      return lhs.rowIndex < rhs.rowIndex;
    });
    OmrAnswerBlockRowResidualSummary summary;
    summary.blockId = blockId;
    summary.topRowsRmsBefore = rmsRange(rows, 0, 9, false);
    summary.topRowsRmsAfter = rmsRange(rows, 0, 9, true);
    summary.middleRowsRmsBefore = rmsRange(rows, 10, 19, false);
    summary.middleRowsRmsAfter = rmsRange(rows, 10, 19, true);
    summary.bottomRowsRmsBefore = rmsRange(rows, 20, 29, false);
    summary.bottomRowsRmsAfter = rmsRange(rows, 20, 29, true);
    for (const auto& row : rows) {
      if (row.rowIndex < 20 || row.rowIndex > 29 || row.detectedCount <= 0) {
        continue;
      }
      summary.bottomRowsMaxBefore = std::max(summary.bottomRowsMaxBefore, row.beforeRms);
      summary.bottomRowsMaxAfter = std::max(summary.bottomRowsMaxAfter, row.afterRms);
    }
    auto first = std::find_if(rows.begin(), rows.end(), [](const auto& row) {
      return row.detectedCount > 0;
    });
    auto last = std::find_if(rows.rbegin(), rows.rend(), [](const auto& row) {
      return row.detectedCount > 0;
    });
    if (first != rows.end() && last != rows.rend()) {
      summary.verticalDriftBefore = last->rowDy - first->rowDy;
      summary.verticalDriftAfter = last->smoothedRowDy - first->smoothedRowDy;
      summary.bottomSystematicDrift = std::abs(summary.verticalDriftBefore) > 4.0;
    }
    summaries.push_back(summary);
  }
  return summaries;
}

void saveAlignmentRowResidualsCsv(
  const std::vector<OmrAnswerRowResidual>& residuals,
  const std::string& imagePath,
  const std::string& outputDirectory,
  NormalizeResult& result
) {
  if (residuals.empty()) {
    return;
  }
  const std::string path = makeOutputPath(imagePath, outputDirectory, "alignment_row_residuals-", ".csv");
  std::ofstream out(path);
  if (!out) {
    return;
  }
  out << "blockId,rowIndex,questionNumber,expectedY,refinedYBefore,detectedMedianY,rowDx,rowDy,smoothedRowDx,smoothedRowDy,detectedCount,confidence,beforeRms,afterRms\n";
  for (const auto& row : residuals) {
    out << csvEscape(row.blockId) << ","
        << row.rowIndex << ","
        << row.questionNumber << ","
        << row.expectedY << ","
        << row.refinedYBefore << ","
        << row.detectedMedianY << ","
        << row.rowDx << ","
        << row.rowDy << ","
        << row.smoothedRowDx << ","
        << row.smoothedRowDy << ","
        << row.detectedCount << ","
        << row.confidence << ","
        << row.beforeRms << ","
        << row.afterRms << "\n";
  }
  result.debugImagePaths.push_back(path);
}

void saveAlignmentRowResidualsJson(
  const std::vector<OmrAnswerRowResidual>& residuals,
  const std::vector<OmrAnswerBlockRowResidualSummary>& summaries,
  const std::string& imagePath,
  const std::string& outputDirectory,
  NormalizeResult& result
) {
  if (residuals.empty()) {
    return;
  }
  const std::string path = makeOutputPath(imagePath, outputDirectory, "alignment_row_residuals-", ".json");
  std::ofstream out(path);
  if (!out) {
    return;
  }
  out << "{\"rows\":[";
  for (size_t index = 0; index < residuals.size(); ++index) {
    if (index > 0) out << ",";
    const auto& row = residuals[index];
    out << "{\"blockId\":" << csvEscape(row.blockId)
        << ",\"rowIndex\":" << row.rowIndex
        << ",\"questionNumber\":" << row.questionNumber
        << ",\"expectedY\":" << row.expectedY
        << ",\"refinedYBefore\":" << row.refinedYBefore
        << ",\"detectedMedianY\":" << row.detectedMedianY
        << ",\"rowDx\":" << row.rowDx
        << ",\"rowDy\":" << row.rowDy
        << ",\"smoothedRowDx\":" << row.smoothedRowDx
        << ",\"smoothedRowDy\":" << row.smoothedRowDy
        << ",\"detectedCount\":" << row.detectedCount
        << ",\"confidence\":" << row.confidence
        << ",\"beforeRms\":" << row.beforeRms
        << ",\"afterRms\":" << row.afterRms << "}";
  }
  out << "],\"summaries\":[";
  for (size_t index = 0; index < summaries.size(); ++index) {
    if (index > 0) out << ",";
    const auto& summary = summaries[index];
    out << "{\"blockId\":" << csvEscape(summary.blockId)
        << ",\"topRowsRmsBefore\":" << summary.topRowsRmsBefore
        << ",\"topRowsRmsAfter\":" << summary.topRowsRmsAfter
        << ",\"middleRowsRmsBefore\":" << summary.middleRowsRmsBefore
        << ",\"middleRowsRmsAfter\":" << summary.middleRowsRmsAfter
        << ",\"bottomRowsRmsBefore\":" << summary.bottomRowsRmsBefore
        << ",\"bottomRowsRmsAfter\":" << summary.bottomRowsRmsAfter
        << ",\"bottomRowsMaxBefore\":" << summary.bottomRowsMaxBefore
        << ",\"bottomRowsMaxAfter\":" << summary.bottomRowsMaxAfter
        << ",\"verticalDriftBefore\":" << summary.verticalDriftBefore
        << ",\"verticalDriftAfter\":" << summary.verticalDriftAfter
        << ",\"bottomSystematicDrift\":" << (summary.bottomSystematicDrift ? "true" : "false") << "}";
  }
  out << "]}";
  result.debugImagePaths.push_back(path);
}

void drawBottomRowsAlignmentOverlayImage(
  const cv::Mat& normalized,
  const OmrTemplate& tpl,
  const BubbleGridRefinement& refinement,
  const std::string& imagePath,
  const std::string& outputDirectory,
  NormalizeResult& result
) {
  if (normalized.empty() || refinement.answerCenters.empty()) {
    return;
  }
  const std::vector<float> rowYs = authoritativeAnswerRowYs(tpl);
  if (rowYs.size() < 30) {
    return;
  }
  const float pitch = medianAnswerPitch(rowYs);
  Rect rect = tpl.answersRegion;
  rect.y = rowYs[20] - pitch * 0.75f;
  rect.height = (rowYs[29] - rowYs[20]) + pitch * 1.5f;
  const cv::Rect roi = clampedCvRect(rect, normalized.size());
  if (roi.empty()) {
    return;
  }

  cv::Mat debug = normalized(roi).clone();
  const auto toRoi = [&](Point point) {
    return cv::Point(
      static_cast<int>(std::round(point.x - static_cast<float>(roi.x))),
      static_cast<int>(std::round(point.y - static_cast<float>(roi.y)))
    );
  };

  for (const auto& center : refinement.answerCenters) {
    if (center.rowIndex < 20 || center.rowIndex > 29) {
      continue;
    }
    cv::drawMarker(debug, toRoi(center.expected), cv::Scalar(170, 170, 170), cv::MARKER_CROSS, 7, 1, cv::LINE_AA);
    cv::circle(debug, toRoi(center.blockAffine), 3, cv::Scalar(255, 120, 0), -1, cv::LINE_AA);
    if (center.hasDetected) {
      cv::circle(debug, toRoi(center.detected), 3, cv::Scalar(0, 220, 255), -1, cv::LINE_AA);
    }
    cv::drawMarker(debug, toRoi(center.refined), cv::Scalar(0, 180, 0), cv::MARKER_TILTED_CROSS, 7, 1, cv::LINE_AA);
    if (distance(center.blockAffine, center.refined) >= 0.75f) {
      cv::line(debug, toRoi(center.blockAffine), toRoi(center.refined), cv::Scalar(0, 180, 255), 1, cv::LINE_AA);
    }
  }

  cv::putText(
    debug,
    "rows 21-30: gray=template blue=block-affine yellow=detected green=final",
    cv::Point(18, 28),
    cv::FONT_HERSHEY_SIMPLEX,
    0.58,
    cv::Scalar(0, 120, 255),
    2,
    cv::LINE_AA
  );

  const std::string debugPath = makeOutputPath(imagePath, outputDirectory, "normalized_bottom_rows_alignment_overlay-");
  if (cv::imwrite(debugPath, debug)) {
    result.debugImagePaths.push_back(debugPath);
  }
}

std::string csvEscape(const std::string& value) {
  std::string escaped = "\"";
  for (const char ch : value) {
    if (ch == '"') {
      escaped += "\"\"";
    } else {
      escaped += ch;
    }
  }
  escaped += "\"";
  return escaped;
}

void saveAnswerScoresCsv(
  const OmrTemplate& tpl,
  const OmrAnswerReadResult& answers,
  const std::string& imagePath,
  const std::string& outputDirectory,
  NormalizeResult& result
) {
  if (!answers.available) {
    return;
  }
  const std::string path = makeOutputPath(imagePath, outputDirectory, "answer_scores-", ".csv");
  std::ofstream out(path);
  if (!out) {
    return;
  }
  out << "questionNumber,blockId,rowIndex,option,score,rawScore,localBaseline,fillRatio,meanGray,expectedX,expectedY,blockAffineX,blockAffineY,rowCorrectedX,rowCorrectedY,finalX,finalY,dxFinal,dyFinal,refinementConfidence,rowConfidence,refinementMode,bestOption,secondBest,gap,status\n";
  for (const auto& question : answers.questions) {
    const OmrAnswerBlockTemplate* block = nullptr;
    for (const auto& candidate : tpl.answers.blocks) {
      if (question.questionNumber >= candidate.questionStart &&
          question.questionNumber < candidate.questionStart + candidate.questionCount) {
        block = &candidate;
        break;
      }
    }
    const int rowIndex = block == nullptr ? -1 : question.questionNumber - block->questionStart;
    std::vector<OmrAnswerOptionScore> sorted = question.options;
    std::sort(sorted.begin(), sorted.end(), [](const auto& lhs, const auto& rhs) {
      return lhs.score > rhs.score;
    });
    const std::string best = sorted.empty() ? "" : sorted[0].option;
    const std::string second = sorted.size() < 2 ? "" : sorted[1].option;
    const double gap = sorted.size() < 2 ? 0.0 : sorted[0].score - sorted[1].score;
    for (const auto& score : question.options) {
      out << question.questionNumber << ","
          << csvEscape(block == nullptr ? "" : block->id) << ","
          << rowIndex << ","
          << csvEscape(score.option) << ","
          << score.score << ","
          << score.rawScore << ","
          << score.localBaseline << ","
          << score.fillRatio << ","
          << score.meanGray << ","
          << score.expectedX << ","
          << score.expectedY << ","
          << score.blockAffineX << ","
          << score.blockAffineY << ","
          << score.rowCorrectedX << ","
          << score.rowCorrectedY << ","
          << score.refinedX << ","
          << score.refinedY << ","
          << score.refinementDx << ","
          << score.refinementDy << ","
          << score.refinementConfidence << ","
          << score.rowConfidence << ","
          << csvEscape(score.refinementMode) << ","
          << csvEscape(best) << ","
          << csvEscape(second) << ","
          << gap << ","
          << question.status << "\n";
    }
  }
  result.debugImagePaths.push_back(path);
}

void saveStudentCodeScoresCsv(
  const OmrTemplate& tpl,
  const OmrStudentCodeReadResult& code,
  const std::string& imagePath,
  const std::string& outputDirectory,
  NormalizeResult& result
) {
  if (!code.available) {
    return;
  }
  const std::string path = makeOutputPath(imagePath, outputDirectory, "student_code_scores-", ".csv");
  std::ofstream out(path);
  if (!out) {
    return;
  }
  out << "columnIndex,visualColumnIndex,digit,score,rawScore,localBaseline,fillRatio,meanGray,expectedX,expectedY,refinedX,refinedY,dx,dy,refinementConfidence,selectedDigit,bestScore,secondScore,gap,status\n";
  for (const auto& column : code.columns) {
    std::vector<OmrStudentCodeDigitScore> sorted = column.digits;
    std::sort(sorted.begin(), sorted.end(), [](const auto& lhs, const auto& rhs) {
      return lhs.score > rhs.score;
    });
    const double best = sorted.empty() ? 0.0 : sorted[0].score;
    const double second = sorted.size() < 2 ? 0.0 : sorted[1].score;
    (void)tpl;
    for (const auto& score : column.digits) {
      out << column.columnIndex << ","
          << column.visualColumnIndex << ","
          << csvEscape(score.digit) << ","
          << score.score << ","
          << score.rawScore << ","
          << score.localBaseline << ","
          << score.fillRatio << ","
          << score.meanGray << ","
          << score.expectedX << ","
          << score.expectedY << ","
          << score.refinedX << ","
          << score.refinedY << ","
          << score.refinementDx << ","
          << score.refinementDy << ","
          << score.refinementConfidence << ","
          << csvEscape(column.hasSelectedDigit ? column.selectedDigit : "") << ","
          << best << ","
          << second << ","
          << (best - second) << ","
          << column.status << "\n";
    }
  }
  result.debugImagePaths.push_back(path);
}

} // namespace

void NormalizeEngine::configure(const OmrTemplate& tpl, const OmrLiveOptions& opts) {
  template_ = tpl;
  options_ = opts;
}

NormalizeResult NormalizeEngine::normalize(const NormalizeRequest& request) {
  Timer timer;
  NormalizeResult result;
  result.examCodeUsesQr = template_.barcode.qrCodeOnly;

  const std::string imagePath = json::stripFileUri(request.imagePath);
  cv::Mat image = cv::imread(imagePath, cv::IMREAD_COLOR);
  if (image.empty()) {
    result.status = "ERROR";
    result.error = "image_load_failed";
    result.processingTimeMs = timer.elapsedMs();
    return result;
  }

  if (request.maxInputDimension > 0) {
    const int largestDimension = std::max(image.cols, image.rows);
    if (largestDimension > request.maxInputDimension) {
      const double scale = static_cast<double>(request.maxInputDimension) /
        static_cast<double>(largestDimension);
      cv::Mat resized;
      cv::resize(
        image,
        resized,
        cv::Size(
          std::max(1, static_cast<int>(std::round(image.cols * scale))),
          std::max(1, static_cast<int>(std::round(image.rows * scale)))
        ),
        0.0,
        0.0,
        cv::INTER_AREA
      );
      image = std::move(resized);
    }
  }

  cv::Mat decodedGray;
  cv::cvtColor(image, decodedGray, cv::COLOR_BGR2GRAY);

  struct GeometryCandidate {
    cv::Mat gray;
    RailDetection rails;
    SheetDetection sheet;
    int clockwiseRotation = 0;
  };
  const auto evaluateGeometry = [&](cv::Mat candidateGray, int clockwiseRotation) {
    GeometryCandidate candidate;
    candidate.gray = std::move(candidateGray);
    candidate.clockwiseRotation = clockwiseRotation;
    candidate.rails = SideDotRailDetector{}.detect(
      candidate.gray,
      template_,
      options_.minDotsPerSideForNormalize
    );
    candidate.sheet = SheetQuadEstimator{}.estimate(
      candidate.rails,
      template_,
      candidate.gray.cols,
      candidate.gray.rows
    );
    return candidate;
  };
  const auto evaluateOrientationProbe = [&](cv::Mat candidateGray, int clockwiseRotation) {
    GeometryCandidate candidate;
    candidate.gray = std::move(candidateGray);
    candidate.clockwiseRotation = clockwiseRotation;
    candidate.rails = SideDotRailDetector{}.detectSideBands(
      candidate.gray,
      template_,
      options_.minDotsPerSideForNormalize
    );
    candidate.sheet = SheetQuadEstimator{}.estimate(
      candidate.rails,
      template_,
      candidate.gray.cols,
      candidate.gray.rows
    );
    return candidate;
  };
  const auto geometryScore = [](const GeometryCandidate& candidate) {
    // A valid sheet and broad row correspondence dominate the choice. Rail
    // confidence resolves the rare case where both 90-degree rotations detect
    // two vertical rails but only one follows the template's asymmetric rows.
    return (candidate.sheet.found ? 10000.0 : 0.0) +
      static_cast<double>(candidate.sheet.matchedRowsUsed) * 100.0 +
      static_cast<double>(candidate.rails.rowMatchScore) * 20.0 +
      static_cast<double>(candidate.rails.railConfidence) * 10.0 -
      std::min(100.0, candidate.sheet.reprojectionError);
  };

  const auto rotatedCopy = [](const cv::Mat& source, int clockwiseRotation) {
    if (clockwiseRotation == 0) {
      return source.clone();
    }
    cv::Mat rotated;
    if (clockwiseRotation == 90) {
      cv::rotate(source, rotated, cv::ROTATE_90_CLOCKWISE);
    } else if (clockwiseRotation == 180) {
      cv::rotate(source, rotated, cv::ROTATE_180);
    } else {
      cv::rotate(source, rotated, cv::ROTATE_90_COUNTERCLOCKWISE);
    }
    return rotated;
  };
  const auto orientationIsReliable = [&](const GeometryCandidate& candidate) {
    return candidate.sheet.found &&
      candidate.sheet.matchedRowsUsed >= options_.minDotsPerSideForNormalize &&
      candidate.rails.railConfidence >= 0.50f;
  };
  const auto bestOrientationIndex = [&](const std::vector<GeometryCandidate>& candidates) {
    std::size_t bestIndex = 0;
    for (std::size_t index = 1; index < candidates.size(); ++index) {
      if (geometryScore(candidates[index]) > geometryScore(candidates[bestIndex])) {
        bestIndex = index;
      }
    }
    return bestIndex;
  };
  const auto appendOrientationWarning = [&](int clockwiseRotation) {
    if (clockwiseRotation == 90) {
      result.warnings.push_back("input_auto_rotated_90_clockwise");
    } else if (clockwiseRotation == 180) {
      result.warnings.push_back("input_auto_rotated_180");
    } else if (clockwiseRotation == 270) {
      result.warnings.push_back("input_auto_rotated_90_counterclockwise");
    }
  };

  const auto finishQrOnly = [&](OmrBarcodeResult barcode) {
    result.barcode = std::move(barcode);
    result.barcodeAttempted = result.barcode.attempted;
    result.barcodeSkippedReason = result.barcode.skippedReason;
    if (template_.barcode.required && !(
        result.barcode.found &&
        result.barcode.status == "FOUND" &&
        !result.barcode.text.empty()
      )) {
      const bool invalidQr = result.barcode.status == "INVALID_PAYLOAD" ||
        result.barcode.status == "INVALID_PAYLOAD_REGEX";
      result.reviewReasons.push_back(invalidQr ? "exam_qr_invalid" : "exam_qr_missing");
    }
    result.reviewRequired = !result.reviewReasons.empty();
    result.success = true;
    result.status = "NORMALIZED";
    result.processingTimeMs = timer.elapsedMs();
    return result;
  };

  if (
    request.qrOnly &&
    request.anchorPointsPercent.empty() &&
    template_.barcode.enabled
  ) {
    // Google Document Scanner usually returns an already cropped, upright
    // page. Read the QR directly from its expected header area before doing
    // rail detection, homography estimation, and a full-page perspective
    // warp. If this conservative shortcut misses, the proven geometry path
    // below still runs unchanged.
    const bool portrait = decodedGray.rows >= decodedGray.cols;
    const std::array<int, 2> rotations = portrait
      ? std::array<int, 2>{0, 0}
      : std::array<int, 2>{90, 270};
    const int rotationCount = portrait ? 1 : 2;
    BarcodeReader barcodeReader;
    for (int index = 0; index < rotationCount; ++index) {
      const int rotation = rotations[static_cast<std::size_t>(index)];
      cv::Mat candidate = rotatedCopy(decodedGray, rotation);
      const OmrTemplate rawTemplate = rawQrSearchTemplate(template_, candidate.size());
      OmrBarcodeResult barcode = barcodeReader.decode(candidate, rawTemplate);
      if (
        barcode.found &&
        barcode.status == "FOUND" &&
        !barcode.text.empty()
      ) {
        appendOrientationWarning(rotation);
        result.warnings.push_back("qr_raw_fast_path");
        return finishQrOnly(std::move(barcode));
      }
    }
  }

  GeometryCandidate geometry;
  if (request.anchorPointsPercent.empty() && template_.height > template_.width) {
    // Do not trust the JPEG dimensions to describe the paper orientation.
    // Some Android scanners save a portrait canvas whose pixels contain a
    // sideways sheet. Probe the template rails in all four directions and
    // use the actual sheet content to choose the upright orientation.
    cv::Mat orientationProbe = decodedGray;
    constexpr int kOrientationProbeMaxDimension = 900;
    const int probeLargestDimension = std::max(orientationProbe.cols, orientationProbe.rows);
    if (probeLargestDimension > kOrientationProbeMaxDimension) {
      const double probeScale = static_cast<double>(kOrientationProbeMaxDimension) /
        static_cast<double>(probeLargestDimension);
      cv::resize(
        orientationProbe,
        orientationProbe,
        cv::Size(),
        probeScale,
        probeScale,
        cv::INTER_AREA
      );
    }

    const std::array<int, 4> rotations{0, 90, 180, 270};
    std::vector<GeometryCandidate> probes;
    probes.reserve(rotations.size());
    for (const int rotation : rotations) {
      probes.push_back(evaluateOrientationProbe(
        rotatedCopy(orientationProbe, rotation),
        rotation
      ));
    }

    std::size_t selectedIndex = bestOrientationIndex(probes);
    const bool uprightProbeIsReliable = orientationIsReliable(probes[0]);
    if (
      selectedIndex != 0 &&
      uprightProbeIsReliable &&
      geometryScore(probes[selectedIndex]) <= geometryScore(probes[0]) + 5.0
    ) {
      selectedIndex = 0;
    }

    int selectedRotation = probes[selectedIndex].clockwiseRotation;
    if (orientationIsReliable(probes[selectedIndex])) {
      geometry = evaluateGeometry(
        rotatedCopy(decodedGray, selectedRotation),
        selectedRotation
      );
    } else {
      // Faint or partially cropped rails need a full-resolution comparison.
      // This path is slower, but it runs only when the inexpensive probe
      // cannot make a safe decision.
      std::vector<GeometryCandidate> fullCandidates;
      fullCandidates.reserve(rotations.size());
      for (const int rotation : rotations) {
        fullCandidates.push_back(evaluateGeometry(
          rotatedCopy(decodedGray, rotation),
          rotation
        ));
      }
      selectedIndex = bestOrientationIndex(fullCandidates);
      if (
        selectedIndex != 0 &&
        fullCandidates[0].sheet.found &&
        geometryScore(fullCandidates[selectedIndex]) <=
          geometryScore(fullCandidates[0]) + 5.0
      ) {
        selectedIndex = 0;
      }
      selectedRotation = fullCandidates[selectedIndex].clockwiseRotation;
      geometry = std::move(fullCandidates[selectedIndex]);
    }

    if (selectedRotation != 0) {
      image = rotatedCopy(image, selectedRotation);
      appendOrientationWarning(selectedRotation);
    }
  } else {
    geometry = evaluateGeometry(std::move(decodedGray), 0);
  }

  cv::Mat gray = std::move(geometry.gray);
  RailDetection rails = std::move(geometry.rails);
  SheetDetection sheet = std::move(geometry.sheet);
  if (request.collectQualityMetrics) {
    result.metrics = QualityAnalyzer::analyzeGray(gray);
  }

  bool usingAnchorQuad = false;
  bool usingRailQuad = false;

  result.detectedLeftDots = static_cast<int>(rails.left.dots.size());
  result.detectedRightDots = static_cast<int>(rails.right.dots.size());
  result.expectedDotsPerSide = rails.expectedDotsPerSide;
  result.rejectedCandidateCount = rails.rejectedCandidateCount;
  result.railDetectionMode = rails.railDetectionMode;
  result.railConfidence = rails.railConfidence;
  result.rowMatchScore = rails.rowMatchScore;

  result.metrics.coverage = sheet.coverage;
  result.metrics.angleScore = sheet.angleScore;
  result.metrics.railScore = sheet.railScore;
  result.metrics.stability = 1.0f;
  result.homographyStrategyUsed = sheet.homographyStrategyUsed;
  result.leftDotsUsed = sheet.leftDotsUsed;
  result.rightDotsUsed = sheet.rightDotsUsed;
  result.matchedRowsUsed = sheet.matchedRowsUsed;
  result.reprojectionError = sheet.reprojectionError;
  result.fallbackUsed = sheet.fallbackUsed;

  if (sheet.found) {
    usingRailQuad = true;
    result.hasQuad = true;
    result.quad = sheet.quad;
  } else if (!request.anchorPointsPercent.empty()) {
    const std::optional<Quad> anchorQuad = semanticQuadFromPercentageAnchors(request.anchorPointsPercent, gray.cols, gray.rows);
    if (!anchorQuad.has_value()) {
      result.status = "ERROR";
      result.error = "invalid_anchor_points";
      result.barcode.error = result.error;
      result.processingTimeMs = timer.elapsedMs();
      return result;
    }

    usingAnchorQuad = true;
    result.hasQuad = true;
    result.quad = *anchorQuad;
    result.metrics.coverage = clamp01(polygonArea(result.quad) / static_cast<float>(std::max(1, gray.cols * gray.rows)));
    result.metrics.angleScore = 1.0f;
    result.metrics.railScore = 1.0f;
    result.metrics.stability = 1.0f;
    result.homographyStrategyUsed = "anchor_points_percent";
    result.fallbackUsed = true;
    if (request.returnDebugImages) {
      drawAnchorDebugImage(image, result.quad, imagePath, request.outputDirectory, result);
    }
  } else {
    result.status = "NO_SHEET";
    result.error = sheet.reason.empty() ? "not_enough_side_dots" : sheet.reason;
    result.barcode.error = result.error;
    result.processingTimeMs = timer.elapsedMs();
    return result;
  }

  if (
    usingRailQuad &&
    (result.metrics.angleScore < options_.minAngleScore || result.metrics.railScore < options_.minRailScore)
  ) {
    result.status = "BAD_ANGLE";
    result.error = "quad_estimation_failed";
    result.barcode.error = result.error;
    result.processingTimeMs = timer.elapsedMs();
    return result;
  }

  cv::Mat h = usingAnchorQuad
    ? perspectiveMatrixForAnswerRegion(result.quad, template_)
    : Homography::perspectiveMatrix(result.quad, template_);
  if (h.empty()) {
    result.status = "ERROR";
    result.error = "perspective_warp_failed";
    result.barcode.error = result.error;
    result.processingTimeMs = timer.elapsedMs();
    return result;
  }
  result.homography = Homography::toArray(h);
  if (usingRailQuad) {
    result.railReprojection = validateRailReprojection(h, sheet.matchedRailRows, template_);
    if (result.railReprojection.headerRms > 4.0) {
      result.warnings.push_back("rail_reprojection:header_rms_gt_4px");
    }
    if (result.railReprojection.studentCodeRms > 4.0) {
      result.warnings.push_back("rail_reprojection:student_code_rms_gt_4px");
    }
    if (result.railReprojection.answerRms > 4.0) {
      result.warnings.push_back("rail_reprojection:answer_rms_gt_4px");
    }
    if (request.returnDebugImages) {
      drawSideRailDebugImage(image, rails, result.quad, sheet, imagePath, request.outputDirectory, result);
    }
  }

  cv::Mat canonicalAnswerBlock;
  cv::Mat templateToAnswerBlock;
  if (usingAnchorQuad && !request.qrOnly) {
    const cv::Mat answerBlockMatrix = perspectiveMatrixForCanonicalAnswerBlock(result.quad);
    templateToAnswerBlock = templateToCanonicalAnswerBlockMatrix(template_);
    if (answerBlockMatrix.empty() || templateToAnswerBlock.empty()) {
      result.status = "ERROR";
      result.error = "perspective_warp_failed";
      result.barcode.error = result.error;
      result.processingTimeMs = timer.elapsedMs();
      return result;
    }

    cv::warpPerspective(
      image,
      canonicalAnswerBlock,
      answerBlockMatrix,
      canonicalAnswerSize(),
      cv::INTER_LINEAR,
      cv::BORDER_REPLICATE
    );

    if (canonicalAnswerBlock.empty()) {
      result.status = "ERROR";
      result.error = "perspective_warp_failed";
      result.barcode.error = result.error;
      result.processingTimeMs = timer.elapsedMs();
      return result;
    }
  }

  cv::Mat normalized;
  cv::warpPerspective(
    image,
    normalized,
    h,
    cv::Size(static_cast<int>(template_.width), static_cast<int>(template_.height)),
    cv::INTER_LINEAR,
    cv::BORDER_CONSTANT,
    cv::Scalar(255, 255, 255)
  );

  if (normalized.empty()) {
    result.status = "ERROR";
    result.error = "perspective_warp_failed";
    result.barcode.error = result.error;
    result.processingTimeMs = timer.elapsedMs();
    return result;
  }

  if (request.returnDebugImages && usingAnchorQuad && !request.qrOnly) {
    saveDebugImage(canonicalAnswerBlock, imagePath, request.outputDirectory, "omr-canonical-answer-block-", result);
  }

  cv::Mat normalizedGray;
  cv::cvtColor(normalized, normalizedGray, cv::COLOR_BGR2GRAY);

  if (request.qrOnly) {
    BarcodeReader barcodeReader;
    return finishQrOnly(barcodeReader.decode(normalizedGray, template_));
  }

  std::string outputPath;
  std::future<bool> normalizedWriteFuture;
  if (request.persistNormalizedImage) {
    outputPath = makeOutputPath(imagePath, request.outputDirectory, "omr-normalized-");
    // The persisted page is only needed after OMR finishes. Encode it while
    // QR and bubble work run so automatic scanner-orientation repair does not
    // add a full JPEG write to the user's wait time.
    normalizedWriteFuture = std::async(
      std::launch::async,
      [&normalized, outputPath]() {
        return cv::imwrite(
          outputPath,
          normalized,
          {cv::IMWRITE_JPEG_QUALITY, 95}
        );
      }
    );
  }

  // QR decoding reads an independent gray ROI. Running it while the bubble
  // grid is refined hides its latency without changing either algorithm.
  auto barcodeFuture = std::async(std::launch::async, [&normalizedGray, this]() {
    BarcodeReader barcodeReader;
    return barcodeReader.decode(normalizedGray, template_);
  });
  auto answerContextFuture = std::async(std::launch::async, [&normalized, &normalizedGray]() {
    return AnswerReader::prepare(normalized, normalizedGray);
  });

  const BubbleGridRefinement refinement = BubbleGridRefiner::refine(normalized, normalizedGray, template_);
  result.templateRefinementUsed = refinement.templateRefinementUsed;
  result.bubbleRefinementMode = refinement.bubbleRefinementMode;
  result.answerBlockRefinementStats = refinement.answerBlockStats;
  result.studentCodeRefinementStats = refinement.studentCodeStats;
  result.answerRowResiduals = refinement.answerRowResiduals;
  result.answerRowResidualSummaries = summarizeRowResiduals(refinement.answerRowResiduals);
  result.warnings.insert(result.warnings.end(), refinement.warnings.begin(), refinement.warnings.end());

  // Both readers use the same normalized sheet. Reuse the already-computed
  // grayscale image and one identical Otsu threshold instead of processing the
  // full page twice more.
  const AnswerReaderContext answerContext = answerContextFuture.get();
  auto studentCodeFuture = std::async(
    std::launch::async,
    [&normalized, this, &answerContext, &refinement]() {
      return AnswerReader::readStudentCode(normalized, template_, answerContext, &refinement);
    }
  );
  result.answers = AnswerReader::read(normalized, template_, answerContext, &refinement);
  result.studentCode = studentCodeFuture.get();
  result.studentId = result.studentCode.complete ? result.studentCode.value : "";
  result.selectedAnswers.clear();
  for (const auto& question : result.answers.questions) {
    if (question.status == "MARKED" && question.hasSelectedOptionNumber) {
      result.selectedAnswers[question.questionNumber] = question.selectedOptionNumber;
    }
  }

  if (request.returnDebugImages) {
    if (usingAnchorQuad) {
      drawCanonicalAnswerDebugImage(
        canonicalAnswerBlock,
        template_,
        templateToAnswerBlock,
        result.answers,
        imagePath,
        request.outputDirectory,
        result
      );
    } else {
      drawWarpedAnswerDebugImage(normalized, template_, result.answers, &refinement, imagePath, request.outputDirectory, result);
    }
    drawBubbleRefinementOverlayImage(normalized, refinement, imagePath, request.outputDirectory, result);
    drawBottomRowsAlignmentOverlayImage(normalized, template_, refinement, imagePath, request.outputDirectory, result);
    drawNormalizedTemplateOverlayImage(normalized, template_, &refinement, imagePath, request.outputDirectory, result);
    drawStudentCodeDebugImage(normalized, template_, result.studentCode, &refinement, imagePath, request.outputDirectory, result);
    saveAlignmentRowResidualsCsv(result.answerRowResiduals, imagePath, request.outputDirectory, result);
    saveAlignmentRowResidualsJson(result.answerRowResiduals, result.answerRowResidualSummaries, imagePath, request.outputDirectory, result);
    saveAnswerScoresCsv(template_, result.answers, imagePath, request.outputDirectory, result);
    saveStudentCodeScoresCsv(template_, result.studentCode, imagePath, request.outputDirectory, result);
  }

  result.barcode = barcodeFuture.get();
  result.barcodeAttempted = result.barcode.attempted;
  result.barcodeSkippedReason = result.barcode.skippedReason;

  // Production decision contract: uncertain data is never silently promoted to a
  // final answer. Normalization may succeed while the sheet still requires review.
  if (template_.barcode.required && !(
      result.barcode.found &&
      result.barcode.status == "FOUND" &&
      !result.barcode.text.empty()
    )) {
    const bool invalidQr = result.barcode.status == "INVALID_PAYLOAD" ||
      result.barcode.status == "INVALID_PAYLOAD_REGEX";
    result.reviewReasons.push_back(invalidQr ? "exam_qr_invalid" : "exam_qr_missing");
  }
  if (template_.studentCode.enabled && !result.studentCode.complete) {
    result.reviewReasons.push_back("student_code_incomplete");
  }
  if (template_.studentCode.enabled && result.studentCode.complete) {
    for (const auto& column : result.studentCode.columns) {
      if (column.status == "MARKED" && column.confidence < 0.85) {
        result.reviewReasons.push_back("student_code_confidence_low");
        break;
      }
    }
  }
  if (!result.answers.available) {
    result.reviewReasons.push_back("answers_unavailable");
  }
  if (result.answers.summary.multi > 0) {
    result.reviewReasons.push_back("answers_multiple_marks");
  }
  if (result.answers.summary.ambiguous > 0) {
    result.reviewReasons.push_back("answers_ambiguous");
  }
  if (usingRailQuad && result.railReprojection.sampleCount == 0) {
    // rail_extents has no row correspondence and therefore cannot prove that
    // the QR/student/answer regions landed at their canonical coordinates.
    result.reviewReasons.push_back("sheet_geometry_reprojection_unverified");
  } else if (result.railReprojection.sampleCount > 0) {
    if (template_.barcode.required) {
      if (result.railReprojection.headerSamples < 6) {
        result.reviewReasons.push_back("exam_qr_geometry_reprojection_unverified");
      } else if (result.railReprojection.headerRms > 4.0) {
        result.reviewReasons.push_back("exam_qr_geometry_reprojection_high");
      }
    }
    if (template_.studentCode.enabled) {
      if (result.railReprojection.studentCodeSamples < 8) {
        result.reviewReasons.push_back("student_code_geometry_reprojection_unverified");
      } else if (result.railReprojection.studentCodeRms > 4.0) {
        result.reviewReasons.push_back("student_code_geometry_reprojection_high");
      }
    }
    if (template_.answers.enabled) {
      if (result.railReprojection.answerSamples < 20) {
        result.reviewReasons.push_back("answer_geometry_reprojection_unverified");
      } else if (result.railReprojection.answerRms > 4.0) {
        result.reviewReasons.push_back("answer_geometry_reprojection_high");
      }
    }
  }
  for (const auto& question : result.answers.questions) {
    if (question.status == "MARKED" && question.confidence < 0.85) {
      result.reviewReasons.push_back("answer_confidence_low");
      break;
    }
  }
  result.reviewRequired = !result.reviewReasons.empty();

  if (normalizedWriteFuture.valid() && !normalizedWriteFuture.get()) {
    result.status = "ERROR";
    result.error = "output_write_failed";
    result.barcode.error = result.error;
    result.processingTimeMs = timer.elapsedMs();
    return result;
  }

  result.success = true;
  result.status = "NORMALIZED";
  result.normalizedImagePath = outputPath;
  result.processingTimeMs = timer.elapsedMs();
  return result;
}

} // namespace omr
