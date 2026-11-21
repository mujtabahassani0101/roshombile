// A standalone stage profiler. It never opens the app database or uploads data.
#include <fstream>
#include <iostream>
#include <sstream>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include "omr/HeaderEngine.hpp"
#include "omr/OmrTemplate.hpp"
#include "omr/AnswerReader.hpp"
#include "geometry/BubbleGridRefiner.hpp"
#include "geometry/Homography.hpp"
#include "geometry/SheetQuadEstimator.hpp"
#include "rails/SideDotRailDetector.hpp"
#include "utils/Timer.hpp"

int main(int argc, char** argv) {
  if (argc != 3) return 2;
  std::ifstream input(argv[1]); std::ostringstream data; data << input.rdbuf();
  auto tpl = omr::OmrTemplate::fromJson(data.str());
  const auto options = omr::OmrTemplate::optionsFromJson("", tpl);
  const auto header = omr::HeaderEngine{}.scan(argv[2], 2000);
  if (!header.valid()) return 3;
  std::cout << "header_ms=" << header.processingTimeMs << " rotation=" << header.clockwiseRotation << '\n';
  omr::Timer loadTimer;
  cv::Mat image = cv::imread(argv[2]);
  const double scale = 2000. / std::max(image.cols, image.rows);
  if (scale < 1) cv::resize(image, image, {}, scale, scale, cv::INTER_AREA);
  if (header.clockwiseRotation) cv::rotate(image, image, header.clockwiseRotation == 90 ? cv::ROTATE_90_CLOCKWISE :
    header.clockwiseRotation == 180 ? cv::ROTATE_180 : cv::ROTATE_90_COUNTERCLOCKWISE);
  cv::Mat gray; cv::cvtColor(image, gray, cv::COLOR_BGR2GRAY);
  std::cout << "load_resize_ms=" << loadTimer.elapsedMs() << '\n';
  std::vector<omr::OmrTemplate> layouts{tpl};
  layouts.insert(layouts.end(), tpl.geometryVariants.begin(), tpl.geometryVariants.end());
  for (size_t i=0; i<layouts.size(); ++i) {
    auto layout = layouts[i]; layout.studentCode.enabled = false;
    omr::Timer timer;
    const auto rails = omr::SideDotRailDetector{}.detect(gray, layout, options.minDotsPerSideForNormalize);
    std::cout << "layout=" << i << " rails_ms=" << timer.elapsedMs()
      << " left=" << rails.left.dots.size() << " right=" << rails.right.dots.size() << '\n';
    timer = omr::Timer{};
    const auto sheet = omr::SheetQuadEstimator{}.estimate(rails, layout, gray.cols, gray.rows);
    std::cout << "geometry_ms=" << timer.elapsedMs() << " rows=" << sheet.matchedRowsUsed
      << " error=" << sheet.reprojectionError << '\n';
    if (!sheet.found) continue;
    timer = omr::Timer{};
    cv::Mat normalized, normalizedGray;
    cv::warpPerspective(image, normalized, omr::Homography::perspectiveMatrix(sheet.quad, layout),
      {cvRound(layout.width),cvRound(layout.height)}, cv::INTER_LINEAR, cv::BORDER_CONSTANT, {255,255,255});
    cv::cvtColor(normalized, normalizedGray, cv::COLOR_BGR2GRAY);
    std::cout << "warp_ms=" << timer.elapsedMs() << '\n';
    timer = omr::Timer{};
    const auto refinement = omr::BubbleGridRefiner::refine(normalized, normalizedGray, layout);
    std::cout << "refinement_ms=" << timer.elapsedMs() << '\n';
    timer = omr::Timer{};
    const auto context = omr::AnswerReader::prepare(normalized, normalizedGray);
    const auto answers = omr::AnswerReader::read(normalized, layout, context, &refinement, layout.answers.questionCount);
    std::cout << "answer_ms=" << timer.elapsedMs() << " marked=" << answers.summary.marked << '\n';
  }
  return 0;
}
