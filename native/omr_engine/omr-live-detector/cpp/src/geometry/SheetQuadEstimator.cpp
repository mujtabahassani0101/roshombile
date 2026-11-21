#include "geometry/SheetQuadEstimator.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <iterator>
#include <limits>
#include <vector>

namespace omr {

namespace {

struct PointPair {
  float sourceX = 0.0f;
  float sourceY = 0.0f;
  Point target;
};

struct RailRowCandidate {
  size_t dotIndex = 0;
  size_t rowIndex = 0;
  double error = 0.0;
};

struct RailAssignment {
  std::vector<RailRowCandidate> matches;
  double rmse = 0.0;
  double maxError = 0.0;
};

float scoreRatio(float a, float b) {
  const float larger = std::max(std::abs(a), std::abs(b));
  const float smaller = std::min(std::abs(a), std::abs(b));
  if (larger <= 1e-5f) {
    return 0.0f;
  }
  return clamp01(smaller / larger);
}

double railDotPosition(const DotCandidate& dot, const RailModel& rail) {
  double dx = -static_cast<double>(rail.lineB);
  double dy = static_cast<double>(rail.lineA);
  if (std::abs(dx) < 1e-6 && std::abs(dy) < 1e-6) {
    return dot.y;
  }
  // cv::fitLine may return either sign for the same geometric line. Normalize
  // the rail direction so projected positions always increase from the top of
  // the sheet toward the bottom; otherwise all anchor spans become negative
  // and row matching nondeterministically returns zero correspondences.
  if (dy < 0.0 || (std::abs(dy) < 1e-6 && dx < 0.0)) {
    dx = -dx;
    dy = -dy;
  }
  return static_cast<double>(dot.x) * dx + static_cast<double>(dot.y) * dy;
}

float medianFloat(std::vector<float> values) {
  if (values.empty()) {
    return 0.0f;
  }

  const size_t middle = values.size() / 2;
  std::nth_element(values.begin(), values.begin() + middle, values.end());
  float median = values[middle];
  if (values.size() % 2 == 0) {
    std::nth_element(values.begin(), values.begin() + (middle - 1), values.end());
    median = (median + values[middle - 1]) * 0.5f;
  }
  return median;
}

float medianRailDotRadius(const RailDetection& rails) {
  std::vector<float> radii;
  radii.reserve(rails.left.dots.size() + rails.right.dots.size());
  for (const auto& dot : rails.left.dots) {
    if (dot.radius > 0.0f) {
      radii.push_back(dot.radius);
    }
  }
  for (const auto& dot : rails.right.dots) {
    if (dot.radius > 0.0f) {
      radii.push_back(dot.radius);
    }
  }
  return medianFloat(std::move(radii));
}

std::vector<float> answerRowsFromTemplate(const OmrTemplate& tpl) {
  std::vector<float> registrationRows;
  const float top = tpl.answersRegion.y - 1.0f;
  const float bottom = tpl.answersRegion.y + tpl.answersRegion.height + 1.0f;
  for (const float row : tpl.registration.rowY) {
    if (row >= top && row <= bottom) {
      registrationRows.push_back(row);
    }
  }
  if (registrationRows.size() >= 4) {
    std::sort(registrationRows.begin(), registrationRows.end());
    return registrationRows;
  }

  if (tpl.registration.rowY.size() <= 30) {
    return tpl.registration.rowY;
  }

  for (const auto& block : tpl.answers.blocks) {
    if (block.yByRow.size() >= 4) {
      std::vector<float> rows = block.yByRow;
      std::sort(rows.begin(), rows.end());
      return rows;
    }
  }

  return std::vector<float>(tpl.registration.rowY.end() - 30, tpl.registration.rowY.end());
}

std::vector<DotCandidate> sortedDotsByY(std::vector<DotCandidate> dots) {
  std::sort(dots.begin(), dots.end(), [](const DotCandidate& lhs, const DotCandidate& rhs) {
    return lhs.y < rhs.y;
  });
  return dots;
}

std::vector<std::vector<DotCandidate>> splitDotsIntoVerticalGroups(
  const std::vector<DotCandidate>& dots,
  int frameHeight
) {
  std::vector<std::vector<DotCandidate>> groups;
  const std::vector<DotCandidate> sorted = sortedDotsByY(dots);
  if (sorted.empty()) {
    return groups;
  }

  if (sorted.size() == 1) {
    groups.push_back(sorted);
    return groups;
  }

  std::vector<float> gaps;
  gaps.reserve(sorted.size() - 1);
  for (size_t index = 1; index < sorted.size(); ++index) {
    const float gap = sorted[index].y - sorted[index - 1].y;
    if (gap > 1.0f) {
      gaps.push_back(gap);
    }
  }

  const float medianGap = std::max(1.0f, medianFloat(gaps));
  const float splitGap = std::max(static_cast<float>(frameHeight) * 0.025f, medianGap * 2.35f);
  groups.emplace_back();
  groups.back().push_back(sorted.front());
  for (size_t index = 1; index < sorted.size(); ++index) {
    const float gap = sorted[index].y - sorted[index - 1].y;
    if (gap > splitGap && !groups.back().empty()) {
      groups.emplace_back();
    }
    groups.back().push_back(sorted[index]);
  }

  return groups;
}

float timingRunRegularityScore(const std::vector<DotCandidate>& run) {
  if (run.size() < 3) {
    return 0.0f;
  }

  std::vector<float> gaps;
  gaps.reserve(run.size() - 1);
  for (size_t index = 1; index < run.size(); ++index) {
    gaps.push_back(std::max(1.0f, run[index].y - run[index - 1].y));
  }

  const float medianGap = std::max(1.0f, medianFloat(gaps));
  float gapError = 0.0f;
  for (const float gap : gaps) {
    gapError += std::abs(gap - medianGap) / medianGap;
  }

  std::vector<float> xs;
  xs.reserve(run.size());
  for (const auto& dot : run) {
    xs.push_back(dot.x);
  }
  const float centerX = medianFloat(xs);
  for (float& x : xs) {
    x = std::abs(x - centerX);
  }
  const float xJitter = medianFloat(xs) / std::max(1.0f, medianGap * 2.0f);
  return gapError / static_cast<float>(gaps.size()) + xJitter * 0.35f;
}

std::vector<DotCandidate> normalizeTimingMarkCount(std::vector<DotCandidate> dots, size_t expectedRows) {
  dots = sortedDotsByY(std::move(dots));
  if (expectedRows == 0 || dots.size() <= expectedRows) {
    return dots;
  }

  std::vector<DotCandidate> best;
  float bestScore = std::numeric_limits<float>::infinity();
  for (size_t start = 0; start + expectedRows <= dots.size(); ++start) {
    std::vector<DotCandidate> run(dots.begin() + static_cast<std::ptrdiff_t>(start),
                                  dots.begin() + static_cast<std::ptrdiff_t>(start + expectedRows));
    const float score = timingRunRegularityScore(run);
    if (score < bestScore) {
      bestScore = score;
      best = std::move(run);
    }
  }

  return best.empty() ? dots : best;
}

std::vector<DotCandidate> selectAnswerRailDots(const RailModel& rail, int frameHeight, size_t expectedRows) {
  const auto groups = splitDotsIntoVerticalGroups(rail.dots, frameHeight);
  if (groups.empty()) {
    return {};
  }

  const size_t minUsefulGroup = std::max<size_t>(8, static_cast<size_t>(std::round(expectedRows * 0.45)));
  const float imageHeight = std::max(1.0f, static_cast<float>(frameHeight));
  std::vector<DotCandidate> bestGroup;
  float bestScore = -std::numeric_limits<float>::infinity();

  for (const auto& group : groups) {
    if (group.size() < minUsefulGroup) {
      continue;
    }

    const auto sorted = sortedDotsByY(group);
    const float top = sorted.front().y;
    const float bottom = sorted.back().y;
    const float centerRatio = ((top + bottom) * 0.5f) / imageHeight;
    const float spanRatio = (bottom - top) / imageHeight;
    const float countRatio = static_cast<float>(sorted.size()) / std::max(1.0f, static_cast<float>(expectedRows));
    const float countScore = countRatio <= 1.0f ? countRatio : clamp01(1.0f - (countRatio - 1.0f) * 0.55f);
    const float lowerScore = clamp01((centerRatio - 0.28f) / 0.52f);
    const float spanScore = clamp01(spanRatio / 0.42f);
    const float oversizePenalty = sorted.size() > expectedRows + 8 ? 1.2f : 0.0f;
    const float score = countScore * 5.0f + lowerScore * 2.5f + spanScore * 1.2f - oversizePenalty;

    if (score > bestScore) {
      bestScore = score;
      bestGroup = sorted;
    }
  }

  if (bestGroup.empty()) {
    bestGroup = sortedDotsByY(rail.dots);
  }

  return normalizeTimingMarkCount(std::move(bestGroup), expectedRows);
}

std::vector<float> canonicalRowsForPairCount(const std::vector<float>& rows, size_t pairCount) {
  if (pairCount == 0 || rows.empty()) {
    return {};
  }
  if (pairCount == rows.size()) {
    return rows;
  }

  std::vector<float> sampled;
  sampled.reserve(pairCount);
  const float first = rows.front();
  const float last = rows.back();
  for (size_t index = 0; index < pairCount; ++index) {
    const float t = pairCount == 1 ? 0.0f : static_cast<float>(index) / static_cast<float>(pairCount - 1);
    sampled.push_back(first + (last - first) * t);
  }
  return sampled;
}

std::vector<size_t> candidateAnchorIndexes(size_t length, bool fromEnd) {
  const size_t limit = std::min<size_t>(4, length);
  std::vector<size_t> indexes;
  indexes.reserve(limit);
  for (size_t index = 0; index < limit; ++index) {
    indexes.push_back(fromEnd ? length - 1 - index : index);
  }
  return indexes;
}

RailAssignment assignRailRowsByTransform(
  const std::vector<DotCandidate>& dots,
  const std::vector<double>& positions,
  const std::vector<float>& rows,
  double scale,
  double offset
) {
  std::vector<float> rowGaps;
  rowGaps.reserve(rows.size() > 1 ? rows.size() - 1 : 0);
  for (size_t index = 1; index < rows.size(); ++index) {
    const float gap = rows[index] - rows[index - 1];
    if (gap > 1.0f) {
      rowGaps.push_back(gap);
    }
  }
  const double medianRowGap = std::max(1.0f, medianFloat(rowGaps));
  // Stay below half a normal row pitch so a missing timing dot cannot be
  // greedily relabelled as its neighbour. The previous hard-coded 23 canonical
  // pixels only happened to fit one DPI and admitted adjacent rows at others.
  const double tolerance = std::max(3.0, std::abs(scale) * medianRowGap * 0.46);
  std::vector<RailRowCandidate> candidates;
  for (size_t dotIndex = 0; dotIndex < dots.size(); ++dotIndex) {
    const double approximateRowY = (positions[dotIndex] - offset) / std::max(1e-6, scale);
    const auto lower = std::lower_bound(rows.begin(), rows.end(), static_cast<float>(approximateRowY));
    const size_t centerRowIndex = static_cast<size_t>(std::distance(rows.begin(), lower == rows.end() ? rows.end() - 1 : lower));
    const size_t rowStart = centerRowIndex > 2 ? centerRowIndex - 2 : 0;
    const size_t rowEnd = std::min(rows.size() - 1, centerRowIndex + 2);
    for (size_t rowIndex = rowStart; rowIndex <= rowEnd; ++rowIndex) {
      const double expected = offset + scale * rows[rowIndex];
      const double error = std::abs(positions[dotIndex] - expected);
      if (error <= tolerance) {
        candidates.push_back({dotIndex, rowIndex, error});
      }
    }
  }

  std::sort(candidates.begin(), candidates.end(), [](const auto& lhs, const auto& rhs) {
    return lhs.error < rhs.error;
  });

  std::vector<bool> usedDots(dots.size(), false);
  std::vector<bool> usedRows(rows.size(), false);
  RailAssignment assignment;
  for (const auto& candidate : candidates) {
    if (usedDots[candidate.dotIndex] || usedRows[candidate.rowIndex]) {
      continue;
    }
    usedDots[candidate.dotIndex] = true;
    usedRows[candidate.rowIndex] = true;
    assignment.matches.push_back(candidate);
  }

  std::sort(assignment.matches.begin(), assignment.matches.end(), [](const auto& lhs, const auto& rhs) {
    return lhs.rowIndex < rhs.rowIndex;
  });

  double squaredError = 0.0;
  for (const auto& match : assignment.matches) {
    squaredError += match.error * match.error;
    assignment.maxError = std::max(assignment.maxError, match.error);
  }
  assignment.rmse = std::sqrt(squaredError / std::max<size_t>(1, assignment.matches.size()));
  return assignment;
}

std::vector<std::pair<float, DotCandidate>> matchRailRowsToTemplate(
  const RailModel& rail,
  const std::vector<float>& rows
) {
  std::vector<DotCandidate> dots = rail.dots;
  std::sort(dots.begin(), dots.end(), [&](const DotCandidate& lhs, const DotCandidate& rhs) {
    return railDotPosition(lhs, rail) < railDotPosition(rhs, rail);
  });
  if (dots.size() >= 2 && dots.front().y > dots.back().y) {
    std::reverse(dots.begin(), dots.end());
  }
  if (dots.size() < 2 || rows.size() < 2) {
    return {};
  }

  // When the detector found the complete timing rail, its ordered marks have
  // an unambiguous one-to-one relationship with the template rows.  Prefer
  // that relationship directly: fitting an affine 1-D transform along a rail
  // can discard the widely separated header/student groups under perspective,
  // even though the final 2-D homography models them correctly.
  if (dots.size() == rows.size()) {
    std::vector<std::pair<float, DotCandidate>> completeMatches;
    completeMatches.reserve(rows.size());
    for (size_t index = 0; index < rows.size(); ++index) {
      completeMatches.emplace_back(rows[index], dots[index]);
    }
    return completeMatches;
  }

  std::vector<double> positions;
  positions.reserve(dots.size());
  for (const auto& dot : dots) {
    positions.push_back(railDotPosition(dot, rail));
  }

  const auto startIndexes = candidateAnchorIndexes(dots.size(), false);
  const auto endIndexes = candidateAnchorIndexes(dots.size(), true);
  RailAssignment best;
  double bestScore = -std::numeric_limits<double>::infinity();

  for (const size_t dotStart : startIndexes) {
    for (const size_t dotEnd : endIndexes) {
      if (dotEnd <= dotStart || dotEnd - dotStart < std::min<size_t>(5, dots.size() - 1)) {
        continue;
      }

      for (size_t rowStart = 0; rowStart + 1 < rows.size(); ++rowStart) {
        for (size_t rowEnd = rowStart + 1; rowEnd < rows.size(); ++rowEnd) {
          const double rowSpan = rows[rowEnd] - rows[rowStart];
          const double positionSpan = positions[dotEnd] - positions[dotStart];
          if (rowSpan <= 0.0 || positionSpan <= 0.0) {
            continue;
          }

          const double scale = positionSpan / rowSpan;
          const double offset = positions[dotStart] - scale * rows[rowStart];
          RailAssignment assigned = assignRailRowsByTransform(dots, positions, rows, scale, offset);
          if (assigned.matches.size() < std::min<size_t>(6, dots.size())) {
            continue;
          }

          const double score =
            static_cast<double>(assigned.matches.size()) * 1000.0 -
            assigned.rmse * 18.0 -
            assigned.maxError * 2.0 -
            std::abs(static_cast<double>(rowEnd - rowStart) - static_cast<double>(dotEnd - dotStart)) * 1.5;

          if (score > bestScore) {
            bestScore = score;
            best = std::move(assigned);
          }
        }
      }
    }
  }

  std::vector<std::pair<float, DotCandidate>> matches;
  matches.reserve(best.matches.size());
  for (const auto& match : best.matches) {
    matches.emplace_back(rows[match.rowIndex], dots[match.dotIndex]);
  }
  return matches;
}

bool solve8x8(std::array<std::array<double, 9>, 8>& matrix, std::array<double, 8>& out) {
  constexpr size_t size = 8;
  for (size_t col = 0; col < size; ++col) {
    size_t pivot = col;
    for (size_t row = col + 1; row < size; ++row) {
      if (std::abs(matrix[row][col]) > std::abs(matrix[pivot][col])) {
        pivot = row;
      }
    }

    if (std::abs(matrix[pivot][col]) < 1e-9) {
      return false;
    }

    if (pivot != col) {
      std::swap(matrix[pivot], matrix[col]);
    }

    const double pivotValue = matrix[col][col];
    for (size_t item = col; item <= size; ++item) {
      matrix[col][item] /= pivotValue;
    }

    for (size_t row = 0; row < size; ++row) {
      if (row == col) {
        continue;
      }
      const double factor = matrix[row][col];
      for (size_t item = col; item <= size; ++item) {
        matrix[row][item] -= factor * matrix[col][item];
      }
    }
  }

  for (size_t index = 0; index < size; ++index) {
    out[index] = matrix[index][size];
  }
  return true;
}

bool homographyFromPointPairs(const std::vector<PointPair>& pairs, std::array<double, 9>& h) {
  if (pairs.size() < 4) {
    return false;
  }

  std::array<std::array<double, 9>, 8> normal{};
  const auto addEquation = [&](const std::array<double, 8>& coefficients, double value) {
    for (size_t row = 0; row < 8; ++row) {
      for (size_t col = 0; col < 8; ++col) {
        normal[row][col] += coefficients[row] * coefficients[col];
      }
      normal[row][8] += coefficients[row] * value;
    }
  };

  for (const auto& pair : pairs) {
    const double x = pair.sourceX;
    const double y = pair.sourceY;
    const double u = pair.target.x;
    const double v = pair.target.y;
    addEquation({x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y}, u);
    addEquation({0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y}, v);
  }

  std::array<double, 8> solved{};
  if (!solve8x8(normal, solved)) {
    return false;
  }

  for (size_t index = 0; index < solved.size(); ++index) {
    h[index] = solved[index];
  }
  h[8] = 1.0;
  return true;
}

Point projectPoint(const std::array<double, 9>& h, float x, float y) {
  const double denominator = h[6] * x + h[7] * y + h[8];
  if (std::abs(denominator) < 1e-9) {
    return {};
  }
  return {
    static_cast<float>((h[0] * x + h[1] * y + h[2]) / denominator),
    static_cast<float>((h[3] * x + h[4] * y + h[5]) / denominator),
  };
}

double medianError(std::vector<double> values) {
  if (values.empty()) {
    return 0.0;
  }

  const size_t middle = values.size() / 2;
  std::nth_element(values.begin(), values.begin() + middle, values.end());
  double median = values[middle];
  if (values.size() % 2 == 0) {
    std::nth_element(values.begin(), values.begin() + (middle - 1), values.end());
    median = (median + values[middle - 1]) * 0.5;
  }
  return median;
}

bool robustHomographyFromPointPairs(std::vector<PointPair> pairs, std::array<double, 9>& h) {
  if (!homographyFromPointPairs(pairs, h)) {
    return false;
  }
  if (pairs.size() < 8) {
    return true;
  }

  for (int iteration = 0; iteration < 2; ++iteration) {
    std::vector<double> errors;
    errors.reserve(pairs.size());
    for (const auto& pair : pairs) {
      const Point projected = projectPoint(h, pair.sourceX, pair.sourceY);
      errors.push_back(distance(projected, pair.target));
    }

    const double threshold = std::max(6.0, medianError(errors) * 2.8);
    std::vector<PointPair> kept;
    kept.reserve(pairs.size());
    for (size_t index = 0; index < pairs.size(); ++index) {
      if (errors[index] <= threshold) {
        kept.push_back(pairs[index]);
      }
    }

    if (kept.size() < 4 || kept.size() == pairs.size()) {
      break;
    }

    pairs = std::move(kept);
    if (!homographyFromPointPairs(pairs, h)) {
      return false;
    }
  }

  return true;
}

double reprojectionErrorForPairs(const std::array<double, 9>& h, const std::vector<PointPair>& pairs) {
  if (pairs.empty()) {
    return 0.0;
  }
  double error = 0.0;
  for (const auto& pair : pairs) {
    error += distance(projectPoint(h, pair.sourceX, pair.sourceY), pair.target);
  }
  return error / static_cast<double>(pairs.size());
}

void scoreQuad(SheetDetection& detection, const OmrTemplate& tpl, int frameWidth, int frameHeight) {
  detection.coverage = clamp01(polygonArea(detection.quad) / static_cast<float>(frameWidth * frameHeight));
  detection.allCornersInside =
    pointInside(detection.quad.topLeft, frameWidth, frameHeight) &&
    pointInside(detection.quad.topRight, frameWidth, frameHeight) &&
    pointInside(detection.quad.bottomRight, frameWidth, frameHeight) &&
    pointInside(detection.quad.bottomLeft, frameWidth, frameHeight);

  const float topWidth = distance(detection.quad.topLeft, detection.quad.topRight);
  const float bottomWidth = distance(detection.quad.bottomLeft, detection.quad.bottomRight);
  const float leftHeight = distance(detection.quad.topLeft, detection.quad.bottomLeft);
  const float rightHeight = distance(detection.quad.topRight, detection.quad.bottomRight);
  const float parallelScore = scoreRatio(topWidth, bottomWidth) * 0.5f + scoreRatio(leftHeight, rightHeight) * 0.5f;
  const float aspect = ((topWidth + bottomWidth) * 0.5f) / std::max(1.0f, (leftHeight + rightHeight) * 0.5f);
  const float templateAspect = tpl.width / tpl.height;
  const float aspectScore = clamp01(1.0f - std::abs(aspect - templateAspect) / templateAspect);
  detection.angleScore = clamp01(parallelScore * 0.55f + aspectScore * 0.45f);
}

bool estimateFromIndexedAnswerRails(
  const RailDetection& rails,
  const OmrTemplate& tpl,
  SheetDetection& detection,
  int frameHeight
) {
  const std::vector<float> answerRows = answerRowsFromTemplate(tpl);
  const size_t expectedRows = std::min<size_t>(30, answerRows.size());
  if (expectedRows < 4) {
    return false;
  }

  const std::vector<DotCandidate> leftDots = selectAnswerRailDots(rails.left, frameHeight, expectedRows);
  const std::vector<DotCandidate> rightDots = selectAnswerRailDots(rails.right, frameHeight, expectedRows);
  const size_t pairCount = std::min({leftDots.size(), rightDots.size(), expectedRows});
  const size_t requiredPairs = expectedRows >= 30 ? 26 : std::max<size_t>(4, static_cast<size_t>(std::ceil(expectedRows * 0.85)));
  if (pairCount < requiredPairs) {
    return false;
  }

  const std::vector<float> canonicalRows = canonicalRowsForPairCount(answerRows, pairCount);
  std::vector<PointPair> pairs;
  pairs.reserve(pairCount * 2);
  for (size_t index = 0; index < pairCount; ++index) {
    pairs.push_back({tpl.registration.leftRailX, canonicalRows[index], {leftDots[index].x, leftDots[index].y}});
    pairs.push_back({tpl.registration.rightRailX, canonicalRows[index], {rightDots[index].x, rightDots[index].y}});
  }

  std::array<double, 9> h{};
  if (!robustHomographyFromPointPairs(pairs, h)) {
    return false;
  }
  detection.leftDotsUsed = static_cast<int>(pairCount);
  detection.rightDotsUsed = static_cast<int>(pairCount);
  detection.matchedRowsUsed = static_cast<int>(pairCount);
  detection.reprojectionError = reprojectionErrorForPairs(h, pairs);
  detection.homographyStrategyUsed = "answer_30_registration_rows";
  detection.fallbackUsed = true;
  detection.matchedRailRows.clear();
  detection.matchedRailRows.reserve(pairCount * 2);
  for (size_t index = 0; index < pairCount; ++index) {
    detection.matchedRailRows.push_back({
      {leftDots[index].x, leftDots[index].y},
      tpl.registration.leftRailX,
      canonicalRows[index]
    });
    detection.matchedRailRows.push_back({
      {rightDots[index].x, rightDots[index].y},
      tpl.registration.rightRailX,
      canonicalRows[index]
    });
  }

  detection.quad = {
    projectPoint(h, 0.0f, 0.0f),
    projectPoint(h, tpl.width, 0.0f),
    projectPoint(h, tpl.width, tpl.height),
    projectPoint(h, 0.0f, tpl.height),
  };
  detection.reason = "answer_rail_indexed_homography";
  return true;
}

bool estimateFromRailHomography(const RailDetection& rails, const OmrTemplate& tpl, SheetDetection& detection) {
  const auto leftMatches = matchRailRowsToTemplate(rails.left, tpl.registration.rowY);
  const auto rightMatches = matchRailRowsToTemplate(rails.right, tpl.registration.rowY);
  detection.leftRowMatches = static_cast<int>(leftMatches.size());
  detection.rightRowMatches = static_cast<int>(rightMatches.size());
  std::vector<PointPair> pairs;
  pairs.reserve(leftMatches.size() + rightMatches.size());

  for (const auto& match : leftMatches) {
    pairs.push_back({tpl.registration.leftRailX, match.first, {match.second.x, match.second.y}});
  }
  for (const auto& match : rightMatches) {
    pairs.push_back({tpl.registration.rightRailX, match.first, {match.second.x, match.second.y}});
  }
  if (leftMatches.size() < 8 || rightMatches.size() < 8 || pairs.size() < 24) {
    return false;
  }

  std::array<double, 9> h{};
  if (!robustHomographyFromPointPairs(pairs, h)) {
    return false;
  }
  detection.leftDotsUsed = static_cast<int>(leftMatches.size());
  detection.rightDotsUsed = static_cast<int>(rightMatches.size());
  detection.matchedRowsUsed = static_cast<int>(std::min(leftMatches.size(), rightMatches.size()));
  detection.reprojectionError = reprojectionErrorForPairs(h, pairs);
  detection.homographyStrategyUsed =
    "all_" + std::to_string(tpl.registration.rowY.size()) + "_registration_rows";
  detection.fallbackUsed = false;
  detection.matchedRailRows.clear();
  detection.matchedRailRows.reserve(leftMatches.size() + rightMatches.size());
  for (const auto& match : leftMatches) {
    detection.matchedRailRows.push_back({
      {match.second.x, match.second.y},
      tpl.registration.leftRailX,
      match.first
    });
  }
  for (const auto& match : rightMatches) {
    detection.matchedRailRows.push_back({
      {match.second.x, match.second.y},
      tpl.registration.rightRailX,
      match.first
    });
  }

  detection.quad = {
    projectPoint(h, 0.0f, 0.0f),
    projectPoint(h, tpl.width, 0.0f),
    projectPoint(h, tpl.width, tpl.height),
    projectPoint(h, 0.0f, tpl.height),
  };
  detection.reason = "all_registration_rows_homography";
  return true;
}

void estimateFromRailExtents(const RailDetection& rails, const OmrTemplate& tpl, SheetDetection& detection) {
  const float topY = std::min(rails.left.topY, rails.right.topY);
  const float bottomY = std::max(rails.left.bottomY, rails.right.bottomY);
  const Point topLeftRail{rails.left.xAtY(topY), topY};
  const Point topRightRail{rails.right.xAtY(topY), topY};
  const Point bottomLeftRail{rails.left.xAtY(bottomY), bottomY};
  const Point bottomRightRail{rails.right.xAtY(bottomY), bottomY};

  const float leftMarginRatio = tpl.registration.leftRailX / tpl.width;
  const float rightMarginRatio = (tpl.width - tpl.registration.rightRailX) / tpl.width;
  const float innerRatio = std::max(0.1f, 1.0f - leftMarginRatio - rightMarginRatio);

  const auto extrapolateLeft = [&](Point leftRail, Point rightRail) {
    return Point{
      leftRail.x + (leftRail.x - rightRail.x) * leftMarginRatio / innerRatio,
      leftRail.y + (leftRail.y - rightRail.y) * leftMarginRatio / innerRatio,
    };
  };
  const auto extrapolateRight = [&](Point leftRail, Point rightRail) {
    return Point{
      rightRail.x + (rightRail.x - leftRail.x) * rightMarginRatio / innerRatio,
      rightRail.y + (rightRail.y - leftRail.y) * rightMarginRatio / innerRatio,
    };
  };

  detection.quad = {
    extrapolateLeft(topLeftRail, topRightRail),
    extrapolateRight(topLeftRail, topRightRail),
    extrapolateRight(bottomLeftRail, bottomRightRail),
    extrapolateLeft(bottomLeftRail, bottomRightRail),
  };
  detection.leftDotsUsed = static_cast<int>(rails.left.dots.size());
  detection.rightDotsUsed = static_cast<int>(rails.right.dots.size());
  detection.matchedRowsUsed = 0;
  detection.reprojectionError = 0.0;
  detection.homographyStrategyUsed = "rail_extents";
  detection.fallbackUsed = true;
  detection.matchedRailRows.clear();
  detection.reason = "rail_extents";
}

} // namespace

SheetDetection SheetQuadEstimator::estimate(
  const RailDetection& rails,
  const OmrTemplate& tpl,
  int frameWidth,
  int frameHeight
) const {
  SheetDetection detection;
  detection.railScore = rails.railScore;

  if (!rails.left.found || !rails.right.found) {
    detection.reason = "not_enough_side_dots";
    return detection;
  }

  SheetDetection allRowsCandidate = detection;
  SheetDetection answerRowsCandidate = detection;
  const bool hasAllRows = estimateFromRailHomography(rails, tpl, allRowsCandidate);
  const bool hasAnswerRows = estimateFromIndexedAnswerRails(rails, tpl, answerRowsCandidate, frameHeight);

  // A bad independent row assignment on one rail can still maximize the match
  // count while giving the two rails different row identities.  Its resulting
  // homography has a visibly large residual and, more importantly, shifts the
  // answer bubbles enough to create false marks.  Prefer the answer-run model
  // only when the all-row fit exceeds a scale-aware error envelope and the
  // fallback materially improves the observed fit.  Header/student geometry
  // remains explicitly unverified for that fallback in NormalizeEngine.
  const double allRowsErrorLimit = std::max(
    3.0,
    static_cast<double>(medianRailDotRadius(rails)) * 0.20
  );
  const bool allRowsReliable = hasAllRows && allRowsCandidate.reprojectionError <= allRowsErrorLimit;
  const bool answerRowsMateriallyBetter =
    hasAnswerRows &&
    (!hasAllRows || answerRowsCandidate.reprojectionError + 0.5 < allRowsCandidate.reprojectionError * 0.80);

  if (allRowsReliable || (hasAllRows && !answerRowsMateriallyBetter)) {
    detection = std::move(allRowsCandidate);
  } else if (hasAnswerRows) {
    detection = std::move(answerRowsCandidate);
  } else if (hasAllRows) {
    detection = std::move(allRowsCandidate);
  } else {
    estimateFromRailExtents(rails, tpl, detection);
  }

  scoreQuad(detection, tpl, frameWidth, frameHeight);
  detection.found = true;
  return detection;
}

} // namespace omr
