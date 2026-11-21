#include "omr/AnswerReader.hpp"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <functional>
#include <numeric>
#include <opencv2/imgproc.hpp>

namespace omr {

namespace {

double clamp(double value, double minValue, double maxValue) {
  return std::min(maxValue, std::max(minValue, value));
}

double optionNumber(const std::string& option, bool& ok) {
  char* end = nullptr;
  const double value = std::strtod(option.c_str(), &end);
  ok = end != option.c_str() && *end == '\0';
  return ok ? value : 0.0;
}

// Printed option/digit labels occupy the centre of an otherwise empty bubble. Sampling
// that centre makes those labels look like marks. Score a clean interior annulus instead:
// a real filled mark spreads into it, while the central glyph and printed ring do not.
// Keep a six-pixel safety moat from the printed outline at the production
// 15-pixel radius. This tolerates small residual warp/refinement errors without
// sampling a desaturated ring as handwriting in grayscale phone scans.
constexpr float kAnswerScoreRadiusScale = 0.58f;
// Ignore the central printed glyph. The outer limit above remains far enough inside
// the bubble to ignore its printed outline.
constexpr float kInnerExclusionRadiusScale = 0.38f;

struct InkSample {
  int gray = 255;
  bool dark = false;
  bool templateMagenta = false;
};

struct ColorSample {
  int gray = 255;
  int red = 255;
  int green = 255;
  int blue = 255;
  int minChannel = 255;
  int maxChannel = 255;
  bool templateMagenta = false;
};

int markThresholdFor(const cv::Mat& gray) {
  cv::Mat scratch;
  const double otsu = cv::threshold(gray, scratch, 0.0, 255.0, cv::THRESH_BINARY | cv::THRESH_OTSU);
  return static_cast<int>(clamp(std::round(otsu + 58.0), 95.0, 178.0));
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

float projectedRadius(const cv::Mat& matrix, Point center, float radius) {
  const Point projectedCenter = projectPoint(matrix, center);
  const Point projectedX = projectPoint(matrix, {center.x + radius, center.y});
  const Point projectedY = projectPoint(matrix, {center.x, center.y + radius});
  const float radiusX = distance(projectedCenter, projectedX);
  const float radiusY = distance(projectedCenter, projectedY);
  return std::max(2.0f, (radiusX + radiusY) * 0.5f);
}

ColorSample colorSampleAt(const cv::Mat& image, int x, int y) {
  const int channels = image.channels();
  if (channels >= 3) {
    const uint8_t* pixel = image.ptr<uint8_t>(y) + x * channels;
    const int blue = pixel[0];
    const int green = pixel[1];
    const int red = pixel[2];
    const int maxChannel = std::max({red, green, blue});
    const int minChannel = std::min({red, green, blue});
    const int gray = static_cast<int>(std::round(red * 0.299 + green * 0.587 + blue * 0.114));

    // The form is printed in magenta. Reject that ink explicitly instead of
    // rejecting every saturated colour: students commonly use a blue pen.
    // The tolerance also catches anti-aliased/blurred edges of the printed ring.
    const bool templateMagenta =
      red >= green + 14 &&
      blue >= green + 14 &&
      std::abs(red - blue) <= 70;
    return {gray, red, green, blue, minChannel, maxChannel, templateMagenta};
  }

  const int gray = image.at<uint8_t>(y, x);
  return {gray, gray, gray, gray, gray, gray, false};
}

int localPaperGray(const cv::Mat& image, float centerX, float centerY, float radius) {
  // Sample outside the printed outline. A high percentile is robust to nearby
  // text, rail fragments and the outline of an adjacent bubble, while still
  // following smooth shadows and camera-light gradients local to this bubble.
  const float innerRadius = radius * 1.16f;
  const float outerRadius = radius * 1.68f;
  const float innerSquared = innerRadius * innerRadius;
  const float outerSquared = outerRadius * outerRadius;
  const float step = std::max(1.25f, radius / 7.0f);
  std::vector<int> samples;

  for (float dy = -outerRadius; dy <= outerRadius; dy += step) {
    for (float dx = -outerRadius; dx <= outerRadius; dx += step) {
      const float distanceSquared = dx * dx + dy * dy;
      if (distanceSquared < innerSquared || distanceSquared > outerSquared) {
        continue;
      }
      const int x = static_cast<int>(std::round(centerX + dx));
      const int y = static_cast<int>(std::round(centerY + dy));
      if (x < 0 || y < 0 || x >= image.cols || y >= image.rows) {
        continue;
      }
      const ColorSample sample = colorSampleAt(image, x, y);
      if (!sample.templateMagenta) {
        samples.push_back(sample.gray);
      }
    }
  }

  if (samples.empty()) {
    return 255;
  }
  const size_t percentileIndex = static_cast<size_t>(
    std::round(static_cast<double>(samples.size() - 1) * 0.72)
  );
  std::nth_element(samples.begin(), samples.begin() + percentileIndex, samples.end());
  return samples[percentileIndex];
}

InkSample sampleAnswerInk(
  const cv::Mat& image,
  int x,
  int y,
  int markThreshold,
  int paperGray
) {
  const ColorSample sample = colorSampleAt(image, x, y);
  if (sample.templateMagenta) {
    return {sample.gray, false, true};
  }

  // Relative darkness recovers faint graphite and coloured pen strokes without
  // turning a broad shadow into a mark, because paper and bubble move together.
  // An absolute cutoff remains only as a conservative fallback on normally lit
  // local paper where the pixel still has measurable contrast from that paper.
  const int requiredLocalContrast = std::max(20, static_cast<int>(std::round(paperGray * 0.12)));
  const bool locallyDark = sample.gray <= paperGray - requiredLocalContrast && sample.gray <= 212;
  // Never apply the absolute cutoff to paper that is itself dark. Without this
  // guard, a smooth illumination ramp crossing gray 118 can make an entirely
  // empty choice look fully marked even though it has no local ink contrast.
  const bool absolutelyDark =
    paperGray >= 145 &&
    sample.gray <= paperGray - 12 &&
    sample.gray <= std::min(132, markThreshold + 14);
  return {sample.gray, locallyDark || absolutelyDark, false};
}

OmrAnswerOptionScore readInkBubble(
  const cv::Mat& image,
  const std::string& option,
  float centerX,
  float centerY,
  float radius,
  int markThreshold
) {
  const int paperGray = localPaperGray(image, centerX, centerY, radius);
  const float scoreRadius = radius * kAnswerScoreRadiusScale;
  // Keep enough samples at the production 15px radius that JPEG/resampling
  // cannot flip a class by changing only one or two of ~36 annulus pixels.
  // The sample count remains scale-stable for higher-DPI templates.
  const float step = std::max(1.0f, radius / 12.0f);
  int dark = 0;
  int total = 0;
  int outerDark = 0;
  int outerTotal = 0;
  int templateMagenta = 0;
  int outerTemplateMagenta = 0;
  double graySum = 0.0;
  const float outerStartSquared = radius * radius *
    kInnerExclusionRadiusScale * kInnerExclusionRadiusScale;

  for (float dy = -scoreRadius; dy <= scoreRadius; dy += step) {
    for (float dx = -scoreRadius; dx <= scoreRadius; dx += step) {
      if (dx * dx + dy * dy > scoreRadius * scoreRadius) {
        continue;
      }

      const int x = static_cast<int>(std::round(centerX + dx));
      const int y = static_cast<int>(std::round(centerY + dy));
      if (x < 0 || y < 0 || x >= image.cols || y >= image.rows) {
        continue;
      }

      const InkSample sample = sampleAnswerInk(image, x, y, markThreshold, paperGray);
      graySum += sample.gray;
      total += 1;
      const bool isOuterSample = dx * dx + dy * dy >= outerStartSquared;
      if (isOuterSample) {
        outerTotal += 1;
      }
      if (sample.templateMagenta) {
        templateMagenta += 1;
        if (isOuterSample) {
          outerTemplateMagenta += 1;
        }
      }
      if (sample.dark) {
        dark += 1;
        if (isOuterSample) {
          outerDark += 1;
        }
      }
    }
  }

  OmrAnswerOptionScore score;
  score.option = option;
  score.hasOptionNumber = false;
  score.optionNumber = optionNumber(option, score.hasOptionNumber);
  const double darkFillRatio = total > 0 ? static_cast<double>(dark) / static_cast<double>(total) : 0.0;
  const double outerDarkFillRatio = outerTotal > 0
    ? static_cast<double>(outerDark) / static_cast<double>(outerTotal)
    : 0.0;
  const double magentaFillRatio = total > 0
    ? static_cast<double>(templateMagenta) / static_cast<double>(total)
    : 0.0;
  const double outerMagentaFillRatio = outerTotal > 0
    ? static_cast<double>(outerTemplateMagenta) / static_cast<double>(outerTotal)
    : 0.0;
  // Thin printed rings and the central glyph occupy only a small part of the
  // interior annulus. A real purple/mahogany pen fill can share the form's hue,
  // but covers most of that annulus. Recover only broad same-hue fills so the
  // colour rejection cannot erase valid handwriting.
  const bool broadMagentaFill = outerMagentaFillRatio >= 0.55;
  score.fillRatio = broadMagentaFill
    ? std::max(darkFillRatio, magentaFillRatio)
    : darkFillRatio;
  const double outerFillRatio = broadMagentaFill
    ? std::max(outerDarkFillRatio, outerMagentaFillRatio)
    : outerDarkFillRatio;
  score.rawScore = outerFillRatio;
  score.score = outerFillRatio;
  score.meanGray = total > 0 ? graySum / static_cast<double>(total) : 255.0;
  return score;
}

template <typename Score>
void subtractCommonPrintedBaseline(std::vector<Score>& scores) {
  if (scores.size() < 2) {
    return;
  }
  std::vector<double> sorted;
  sorted.reserve(scores.size());
  for (const auto& score : scores) {
    sorted.push_back(score.score);
  }
  std::sort(sorted.begin(), sorted.end());

  // Use the median common response when all choices have similar ring/glyph
  // leakage. When a true mark is clearly separated, use the lower half so the
  // mark itself cannot raise the baseline. This per-row calibration is robust
  // to both grayscale printing and multiple genuine marks.
  double baseline = 0.0;
  if (sorted.size() % 2 == 0) {
    const size_t upper = sorted.size() / 2;
    baseline = (sorted[upper - 1] + sorted[upper]) * 0.5;
  } else {
    baseline = sorted[sorted.size() / 2];
  }
  if (sorted.back() - sorted.front() >= 0.55) {
    const size_t baselineCount = std::max<size_t>(1, sorted.size() / 2);
    baseline = std::accumulate(
      sorted.begin(),
      sorted.begin() + static_cast<std::ptrdiff_t>(baselineCount),
      0.0
    ) / static_cast<double>(baselineCount);
  }
  const double usableRange = std::max(0.15, 1.0 - baseline);
  for (auto& score : scores) {
    score.rawScore = score.score;
    score.localBaseline = baseline;
    score.score = clamp((score.score - baseline) / usableRange, 0.0, 1.0);
  }
}

double confidenceFor(
  const OmrAnswerOptionScore& best,
  const OmrAnswerOptionScore& second,
  const OmrAnswerReadThresholds& thresholds
) {
  const auto decisionConfidence = [](double value, double rejectBoundary, double acceptBoundary) {
    if (value <= rejectBoundary) {
      return 0.0;
    }
    if (value < acceptBoundary) {
      return 0.5 * (value - rejectBoundary) /
        std::max(0.01, acceptBoundary - rejectBoundary);
    }
    return 0.5 + 0.5 * (value - acceptBoundary) /
      std::max(0.01, 1.0 - acceptBoundary);
  };

  // Crossing a classification boundary is evidence for the class, but it is
  // not perfect confidence. The previous normalization mapped both acceptance
  // boundaries directly to 1.0, making every MARKED result algebraically have
  // confidence 1 and rendering the production low-confidence gate ineffective.
  const double fillStrength = clamp(decisionConfidence(
    best.score,
    thresholds.blankMaxFillRatio,
    thresholds.markedFillRatio
  ), 0.0, 1.0);
  const double separation = clamp(decisionConfidence(
    best.score - second.score,
    0.0,
    thresholds.ambiguousDelta
  ), 0.0, 1.0);
  return clamp(fillStrength * 0.58 + separation * 0.42, 0.0, 1.0);
}

double blankConfidenceFor(
  double bestScore,
  const OmrAnswerReadThresholds& thresholds
) {
  // Confidence describes the class that was actually returned. A clean blank
  // row has almost no fill evidence and must therefore be highly confident,
  // rather than inheriting the near-zero confidence of the MARKED class.
  return clamp(
    1.0 - bestScore / std::max(0.01f, thresholds.blankMaxFillRatio),
    0.0,
    1.0
  );
}

OmrAnswerQuestionResult classifyQuestion(
  int questionNumber,
  std::vector<OmrAnswerOptionScore> options,
  const OmrAnswerReadThresholds& thresholds
) {
  subtractCommonPrintedBaseline(options);
  std::vector<OmrAnswerOptionScore> sorted = options;
  std::sort(sorted.begin(), sorted.end(), [](const auto& lhs, const auto& rhs) {
    return lhs.score > rhs.score;
  });

  OmrAnswerQuestionResult question;
  question.questionNumber = questionNumber;
  question.status = "BLANK";
  question.options = std::move(options);
  if (sorted.empty()) {
    return question;
  }

  const OmrAnswerOptionScore& best = sorted[0];
  const OmrAnswerOptionScore second = sorted.size() > 1 ? sorted[1] : OmrAnswerOptionScore{};
  const double delta = best.score - second.score;
  std::vector<double> rawSorted;
  rawSorted.reserve(sorted.size());
  for (const auto& option : sorted) {
    rawSorted.push_back(option.rawScore);
  }
  std::sort(rawSorted.begin(), rawSorted.end(), std::greater<double>());
  const double rawMultiThreshold = std::max(0.65, static_cast<double>(thresholds.multiMarkMinRatio));
  const bool rawSeparatedMulti = rawSorted.size() >= 4 &&
    rawSorted[1] >= rawMultiThreshold &&
    ((rawSorted[0] + rawSorted[1]) - (rawSorted[2] + rawSorted[3])) * 0.5 >=
      thresholds.ambiguousDelta;
  int rawMarkedCount = 0;
  for (const auto& option : sorted) {
    if (option.rawScore >= rawMultiThreshold) {
      rawMarkedCount += 1;
    }
  }
  std::vector<OmrAnswerOptionScore> marked;
  marked.reserve(sorted.size());
  for (const auto& option : sorted) {
    if (option.score >= thresholds.multiMarkMinRatio) {
      marked.push_back(option);
    }
  }

  if (marked.size() > 1 || rawSeparatedMulti || rawMarkedCount > 1) {
    question.status = "MULTI";
    for (const auto& option : sorted) {
      if (
        option.score >= thresholds.multiMarkMinRatio ||
        option.rawScore >= rawMultiThreshold
      ) {
        question.selectedOptions.push_back(option.option);
      }
    }
  } else if (best.score <= thresholds.blankMaxFillRatio) {
    question.status = "BLANK";
  } else if (best.score >= thresholds.markedFillRatio && delta >= thresholds.ambiguousDelta) {
    question.status = "MARKED";
    question.hasSelectedOption = true;
    question.selectedOption = best.option;
    question.hasSelectedOptionNumber = best.hasOptionNumber;
    question.selectedOptionNumber = best.optionNumber;
    question.selectedOptions.push_back(best.option);
  } else {
    question.status = "AMBIGUOUS";
    // Keep the ranked option scores for review, but never promote a candidate
    // from an ambiguous row into the canonical selected-answer fields.
  }

  if (question.status == "MARKED") {
    question.confidence = confidenceFor(best, second, thresholds);
  } else if (question.status == "BLANK") {
    question.confidence = blankConfidenceFor(best.score, thresholds);
  } else {
    // MULTI and AMBIGUOUS remain review states. Preserve their mark evidence,
    // but cap its authority so they cannot look auto-accepted in consumers.
    question.confidence = confidenceFor(best, second, thresholds) * 0.5;
  }
  return question;
}

OmrStudentCodeDigitScore digitFromOption(const OmrAnswerOptionScore& option) {
  OmrStudentCodeDigitScore digit;
  digit.digit = option.option;
  digit.digitNumber = option.optionNumber;
  digit.hasDigitNumber = option.hasOptionNumber;
  digit.fillRatio = option.fillRatio;
  digit.rawScore = option.rawScore;
  digit.localBaseline = option.localBaseline;
  digit.score = option.score;
  digit.meanGray = option.meanGray;
  digit.expectedX = option.expectedX;
  digit.expectedY = option.expectedY;
  digit.blockAffineX = option.blockAffineX;
  digit.blockAffineY = option.blockAffineY;
  digit.rowCorrectedX = option.rowCorrectedX;
  digit.rowCorrectedY = option.rowCorrectedY;
  digit.refinedX = option.refinedX;
  digit.refinedY = option.refinedY;
  digit.refinementDx = option.refinementDx;
  digit.refinementDy = option.refinementDy;
  digit.refinementConfidence = option.refinementConfidence;
  digit.rowConfidence = option.rowConfidence;
  digit.refinementMode = option.refinementMode;
  digit.hasRefinement = option.hasRefinement;
  return digit;
}

std::vector<float> authoritativeAnswerRowYs(const OmrTemplate& tpl) {
  int expectedRows = 0;
  for (const auto& block : tpl.answers.blocks) {
    expectedRows = std::max(expectedRows, block.questionCount);
  }

  // The 30 answer-row timing marks are the source of truth for vertical geometry.
  // Answer block bounding boxes may include printed margin and must not define dy.
  std::vector<float> rows;
  rows.reserve(tpl.registration.rowY.size());
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

int readableRowsForBlock(
  const std::vector<float>& authoritativeRows,
  const OmrAnswerBlockTemplate& block
) {
  const int availableRows = !authoritativeRows.empty()
    ? static_cast<int>(authoritativeRows.size())
    : static_cast<int>(block.yByRow.size());
  return std::min(block.questionCount, availableRows);
}

OmrStudentCodeColumnResult classifyStudentCodeColumn(
  int columnIndex,
  int visualColumnIndex,
  std::vector<OmrStudentCodeDigitScore> digits,
  const OmrAnswerReadThresholds& thresholds
) {
  subtractCommonPrintedBaseline(digits);
  std::vector<OmrStudentCodeDigitScore> sorted = digits;
  std::sort(sorted.begin(), sorted.end(), [](const auto& lhs, const auto& rhs) {
    return lhs.score > rhs.score;
  });

  OmrStudentCodeColumnResult column;
  column.columnIndex = columnIndex;
  column.visualColumnIndex = visualColumnIndex;
  column.status = "BLANK";
  column.digits = std::move(digits);
  if (sorted.empty()) {
    return column;
  }

  const OmrStudentCodeDigitScore& best = sorted[0];
  const OmrStudentCodeDigitScore second = sorted.size() > 1 ? sorted[1] : OmrStudentCodeDigitScore{};
  const double delta = best.score - second.score;
  const double rawMultiThreshold = std::max(0.65, static_cast<double>(thresholds.multiMarkMinRatio));
  std::vector<OmrStudentCodeDigitScore> marked;
  std::vector<OmrStudentCodeDigitScore> rawMarked;
  marked.reserve(sorted.size());
  rawMarked.reserve(sorted.size());
  for (const auto& digit : sorted) {
    if (digit.score >= thresholds.multiMarkMinRatio) {
      marked.push_back(digit);
    }
    if (digit.rawScore >= rawMultiThreshold) {
      rawMarked.push_back(digit);
    }
  }

  if (marked.size() > 1 || rawMarked.size() > 1) {
    column.status = "MULTI";
    for (const auto& digit : sorted) {
      if (digit.score >= thresholds.multiMarkMinRatio || digit.rawScore >= rawMultiThreshold) {
        column.selectedDigits.push_back(digit.digit);
      }
    }
  } else if (best.score <= thresholds.blankMaxFillRatio) {
    column.status = "BLANK";
  } else {
    if (best.score >= thresholds.markedFillRatio && delta >= thresholds.ambiguousDelta) {
      column.hasSelectedDigit = true;
      column.selectedDigit = best.digit;
      column.hasSelectedDigitNumber = best.hasDigitNumber;
      column.selectedDigitNumber = best.digitNumber;
      column.selectedDigits.push_back(best.digit);
      column.status = "MARKED";
    } else {
      column.status = "AMBIGUOUS";
    }
  }

  if (column.status == "MARKED") {
    OmrAnswerOptionScore bestOption;
    bestOption.score = best.score;
    OmrAnswerOptionScore secondOption;
    secondOption.score = second.score;
    column.confidence = confidenceFor(bestOption, secondOption, thresholds);
  } else if (column.status == "BLANK") {
    column.confidence = blankConfidenceFor(best.score, thresholds);
  } else {
    OmrAnswerOptionScore bestOption;
    bestOption.score = best.score;
    OmrAnswerOptionScore secondOption;
    secondOption.score = second.score;
    column.confidence = confidenceFor(bestOption, secondOption, thresholds) * 0.5;
  }

  return column;
}

std::string rawDigitFor(const OmrStudentCodeColumnResult& column) {
  if (column.status == "MARKED" && column.hasSelectedDigit) {
    return column.selectedDigit;
  }
  if (column.status == "BLANK") {
    return "-";
  }
  if (column.status == "MULTI") {
    return "*";
  }
  return "?";
}

std::string displayDigitFor(const OmrStudentCodeColumnResult& column) {
  if (column.status == "MARKED" && column.hasSelectedDigit) {
    return column.selectedDigit.empty() ? "?" : column.selectedDigit;
  }
  return "";
}

const RefinedAnswerBubbleCenter* refinedAnswerCenterFor(
  const BubbleGridRefinement* refinement,
  const std::string& blockId,
  int questionNumber,
  int rowIndex,
  const std::string& option
) {
  if (refinement == nullptr) {
    return nullptr;
  }
  for (const auto& center : refinement->answerCenters) {
    if (
      center.blockId == blockId &&
      center.questionNumber == questionNumber &&
      center.rowIndex == rowIndex &&
      center.option == option
    ) {
      return &center;
    }
  }
  return nullptr;
}

const OmrBubbleGridStats* answerBlockStatsFor(
  const BubbleGridRefinement* refinement,
  const std::string& blockId
) {
  if (refinement == nullptr) {
    return nullptr;
  }
  for (const auto& stats : refinement->answerBlockStats) {
    if (stats.blockId == blockId) {
      return &stats;
    }
  }
  return nullptr;
}

bool refinementStatsAreProductionSafe(const OmrBubbleGridStats* stats) {
  return stats != nullptr &&
    stats->detectedCoverage >= 0.70 &&
    stats->usedCentersCount >= 12 &&
    stats->confidence >= 0.55 &&
    stats->fitResidualRms <= 3.5 &&
    stats->appliedShiftRms <= 10.0 &&
    stats->maxError <= 10.0 &&
    std::abs(stats->verticalDrift) <= 5.0 &&
    std::abs(stats->horizontalDrift) <= 5.0;
}

const RefinedStudentCodeBubbleCenter* refinedStudentCenterFor(
  const BubbleGridRefinement* refinement,
  int visualColumnIndex,
  int digitIndex,
  const std::string& digit
) {
  if (refinement == nullptr) {
    return nullptr;
  }
  for (const auto& center : refinement->studentCodeCenters) {
    if (
      center.visualColumnIndex == visualColumnIndex &&
      center.digitIndex == digitIndex &&
      center.digit == digit
    ) {
      return &center;
    }
  }
  return nullptr;
}

void applyAnswerCenterMetadata(
  OmrAnswerOptionScore& score,
  Point expected,
  Point refined,
  float confidence,
  bool hasRefinement
) {
  score.expectedX = expected.x;
  score.expectedY = expected.y;
  score.blockAffineX = refined.x;
  score.blockAffineY = refined.y;
  score.rowCorrectedX = refined.x;
  score.rowCorrectedY = refined.y;
  score.refinedX = refined.x;
  score.refinedY = refined.y;
  score.refinementDx = refined.x - expected.x;
  score.refinementDy = refined.y - expected.y;
  score.refinementConfidence = confidence;
  score.hasRefinement = hasRefinement;
}

} // namespace

AnswerReaderContext AnswerReader::prepare(
  const cv::Mat& normalizedImage,
  const cv::Mat& normalizedGray
) {
  AnswerReaderContext context;
  if (normalizedImage.empty()) {
    return context;
  }

  cv::Mat gray;
  if (
    !normalizedGray.empty() &&
    normalizedGray.channels() == 1 &&
    normalizedGray.size() == normalizedImage.size()
  ) {
    gray = normalizedGray;
  } else if (normalizedImage.channels() >= 3) {
    cv::cvtColor(normalizedImage, gray, cv::COLOR_BGR2GRAY);
  } else {
    gray = normalizedImage;
  }
  context.markThreshold = std::min(markThresholdFor(gray), 118);
  return context;
}

OmrStudentCodeReadResult AnswerReader::readStudentCode(
  const cv::Mat& normalizedImage,
  const OmrTemplate& tpl,
  const BubbleGridRefinement* refinement
) {
  const AnswerReaderContext context = prepare(normalizedImage);
  return readStudentCode(normalizedImage, tpl, context, refinement);
}

OmrStudentCodeReadResult AnswerReader::readStudentCode(
  const cv::Mat& normalizedImage,
  const OmrTemplate& tpl,
  const AnswerReaderContext& context,
  const BubbleGridRefinement* refinement
) {
  OmrStudentCodeReadResult result;
  result.summary.columns = tpl.studentCode.columns;

  if (!tpl.studentCode.enabled) {
    result.reason = "student_code_template_missing";
    return result;
  }
  if (normalizedImage.empty()) {
    result.reason = "normalized_image_empty";
    return result;
  }

  const int markThreshold = context.markThreshold;
  const bool useStudentRefinement = refinement != nullptr &&
    refinementStatsAreProductionSafe(&refinement->studentCodeStats);
  const auto& digitLabels = tpl.studentCode.digitLabelsByChoiceOrder();
  std::vector<int> visualIndexes(static_cast<size_t>(tpl.studentCode.columns));
  std::iota(visualIndexes.begin(), visualIndexes.end(), 0);
  if (
    (!tpl.studentCode.usesHorizontalRows() && tpl.studentCode.columnReadOrder == "rtl") ||
    (tpl.studentCode.usesHorizontalRows() && tpl.studentCode.positionReadOrder == "bottom_to_top")
  ) {
    std::reverse(visualIndexes.begin(), visualIndexes.end());
  }

  result.columns.reserve(static_cast<size_t>(tpl.studentCode.columns));
  int columnIndex = 0;
  for (const int visualIndex : visualIndexes) {
    std::vector<OmrStudentCodeDigitScore> digits;
    digits.reserve(digitLabels.size());
    for (size_t digitIndex = 0; digitIndex < digitLabels.size(); ++digitIndex) {
      const Point expected = tpl.studentCode.bubbleCenter(static_cast<size_t>(visualIndex), digitIndex);
      const RefinedStudentCodeBubbleCenter* refinedCenter = refinedStudentCenterFor(
        useStudentRefinement ? refinement : nullptr,
        visualIndex,
        static_cast<int>(digitIndex),
        digitLabels[digitIndex]
      );
      const Point refined = refinedCenter == nullptr ? expected : refinedCenter->refined;
      // Student-code bubbles use the same colour-aware ink filter as answers so
      // the printed magenta ring cannot turn every digit in a column into a mark.
      auto option = readInkBubble(
        normalizedImage,
        digitLabels[digitIndex],
        refined.x,
        refined.y,
        tpl.studentCode.bubbleRadius,
        markThreshold
      );
      applyAnswerCenterMetadata(
        option,
        expected,
        refined,
        refinedCenter == nullptr ? 0.0f : refinedCenter->confidence,
        refinedCenter != nullptr && refinement != nullptr && refinement->templateRefinementUsed
      );
      digits.push_back(digitFromOption(option));
    }
    result.columns.push_back(classifyStudentCodeColumn(
      columnIndex,
      visualIndex,
      digits,
      tpl.studentCode.thresholds
    ));
    columnIndex += 1;
  }

  for (const auto& column : result.columns) {
    if (column.status == "MARKED") result.summary.marked += 1;
    else if (column.status == "MULTI") result.summary.multi += 1;
    else if (column.status == "AMBIGUOUS") result.summary.ambiguous += 1;
    else result.summary.blank += 1;
    result.rawValue += rawDigitFor(column);
  }
  result.complete = !result.columns.empty() && result.summary.marked == static_cast<int>(result.columns.size());
  if (result.complete) {
    result.status = "COMPLETE";
    for (const auto& column : result.columns) {
      result.value += displayDigitFor(column);
    }
  } else {
    result.status = "INCOMPLETE";
    result.reason = "student_code_incomplete";
  }
  result.available = true;
  return result;
}

OmrAnswerReadResult AnswerReader::read(
  const cv::Mat& normalizedImage,
  const OmrTemplate& tpl,
  const BubbleGridRefinement* refinement
) {
  const AnswerReaderContext context = prepare(normalizedImage);
  return read(normalizedImage, tpl, context, refinement);
}

OmrAnswerReadResult AnswerReader::read(
  const cv::Mat& normalizedImage,
  const OmrTemplate& tpl,
  const AnswerReaderContext& context,
  const BubbleGridRefinement* refinement
) {
  OmrAnswerReadResult result;
  result.summary.total = tpl.answers.questionCount;

  if (!tpl.answers.enabled || tpl.answers.blocks.empty()) {
    result.reason = "answers_template_missing";
    return result;
  }
  if (normalizedImage.empty()) {
    result.reason = "normalized_image_empty";
    return result;
  }

  const int markThreshold = context.markThreshold;
  const std::vector<float> rowYs = authoritativeAnswerRowYs(tpl);
  result.questions.reserve(static_cast<size_t>(std::max(0, tpl.answers.questionCount)));
  for (const auto& block : tpl.answers.blocks) {
    const bool useBlockRefinement = refinementStatsAreProductionSafe(
      answerBlockStatsFor(refinement, block.id)
    );
    const int rowCount = readableRowsForBlock(rowYs, block);
    for (int row = 0; row < rowCount; ++row) {
      std::vector<OmrAnswerOptionScore> options;
      options.reserve(tpl.answers.options.size());
      for (const auto& option : tpl.answers.options) {
        const auto xIt = block.xByOption.find(option);
        if (xIt == block.xByOption.end()) {
          continue;
        }
        const int questionNumber = block.questionStart + row;
        const Point expected{xIt->second, answerRowY(rowYs, block, row)};
        const RefinedAnswerBubbleCenter* refinedCenter = refinedAnswerCenterFor(
          useBlockRefinement ? refinement : nullptr,
          block.id,
          questionNumber,
          row,
          option
        );
        const Point refined = refinedCenter == nullptr ? expected : refinedCenter->refined;
        auto score = readInkBubble(
          normalizedImage,
          option,
          refined.x,
          refined.y,
          block.bubbleRadius,
          markThreshold
        );
        applyAnswerCenterMetadata(
          score,
          expected,
          refined,
          refinedCenter == nullptr ? 0.0f : refinedCenter->confidence,
          refinedCenter != nullptr && refinement != nullptr && refinement->templateRefinementUsed
        );
        if (refinedCenter != nullptr) {
          score.blockAffineX = refinedCenter->blockAffine.x;
          score.blockAffineY = refinedCenter->blockAffine.y;
          score.rowCorrectedX = refinedCenter->rowCorrected.x;
          score.rowCorrectedY = refinedCenter->rowCorrected.y;
          score.rowConfidence = refinedCenter->rowConfidence;
          score.refinementMode = refinedCenter->refinementMode;
        }
        options.push_back(score);
      }
      result.questions.push_back(classifyQuestion(block.questionStart + row, options, tpl.answers.thresholds));
    }
  }

  std::sort(result.questions.begin(), result.questions.end(), [](const auto& lhs, const auto& rhs) {
    return lhs.questionNumber < rhs.questionNumber;
  });

  for (const auto& question : result.questions) {
    if (question.status == "MARKED") result.summary.marked += 1;
    else if (question.status == "MULTI") result.summary.multi += 1;
    else if (question.status == "AMBIGUOUS") result.summary.ambiguous += 1;
    else result.summary.blank += 1;
  }
  if (result.summary.total == 0) {
    result.summary.total = static_cast<int>(result.questions.size());
  }

  result.available = true;
  return result;
}

OmrAnswerReadResult AnswerReader::readAnswerBlock(
  const cv::Mat& warpedAnswerBlock,
  const OmrTemplate& tpl,
  const cv::Mat& templateToAnswerBlock
) {
  OmrAnswerReadResult result;
  result.summary.total = tpl.answers.questionCount;

  if (!tpl.answers.enabled || tpl.answers.blocks.empty()) {
    result.reason = "answers_template_missing";
    return result;
  }
  if (warpedAnswerBlock.empty()) {
    result.reason = "warped_answer_block_empty";
    return result;
  }
  if (templateToAnswerBlock.empty()) {
    result.reason = "answer_block_transform_missing";
    return result;
  }

  cv::Mat gray;
  if (warpedAnswerBlock.channels() >= 3) {
    cv::cvtColor(warpedAnswerBlock, gray, cv::COLOR_BGR2GRAY);
  } else {
    gray = warpedAnswerBlock;
  }
  const int markThreshold = std::min(markThresholdFor(gray), 118);
  const std::vector<float> rowYs = authoritativeAnswerRowYs(tpl);
  result.questions.reserve(static_cast<size_t>(std::max(0, tpl.answers.questionCount)));

  for (const auto& block : tpl.answers.blocks) {
    const int rowCount = readableRowsForBlock(rowYs, block);
    for (int row = 0; row < rowCount; ++row) {
      std::vector<OmrAnswerOptionScore> options;
      options.reserve(tpl.answers.options.size());
      for (const auto& option : tpl.answers.options) {
        const auto xIt = block.xByOption.find(option);
        if (xIt == block.xByOption.end()) {
          continue;
        }

        const Point templateCenter{xIt->second, answerRowY(rowYs, block, row)};
        const Point answerCenter = projectPoint(templateToAnswerBlock, templateCenter);
        const float answerRadius = projectedRadius(templateToAnswerBlock, templateCenter, block.bubbleRadius);
        auto score = readInkBubble(
          warpedAnswerBlock,
          option,
          answerCenter.x,
          answerCenter.y,
          answerRadius,
          markThreshold
        );
        applyAnswerCenterMetadata(score, answerCenter, answerCenter, 0.0f, false);
        options.push_back(score);
      }
      result.questions.push_back(classifyQuestion(block.questionStart + row, options, tpl.answers.thresholds));
    }
  }

  std::sort(result.questions.begin(), result.questions.end(), [](const auto& lhs, const auto& rhs) {
    return lhs.questionNumber < rhs.questionNumber;
  });

  for (const auto& question : result.questions) {
    if (question.status == "MARKED") result.summary.marked += 1;
    else if (question.status == "MULTI") result.summary.multi += 1;
    else if (question.status == "AMBIGUOUS") result.summary.ambiguous += 1;
    else result.summary.blank += 1;
  }
  if (result.summary.total == 0) {
    result.summary.total = static_cast<int>(result.questions.size());
  }

  result.available = true;
  return result;
}

} // namespace omr
