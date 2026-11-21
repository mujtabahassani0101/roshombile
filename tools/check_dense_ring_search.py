#!/usr/bin/env python3
"""Prove the window-classified dense ring search (BubbleGridRefiner.cpp, 1.1.56) is exact.

BubbleGridRefiner.cpp is compiled directly into a test program, so its internal
snapCenterLocal() can be called with the original per-sample loop and with
denseLocalSearch(). Both runs must return bit-identical LocalBubbleCenter values
(expected, detected and snapped points, confidence, accepted, hasRing), and the
dense search's best score and point must equal those of the original loop text,
which is extracted verbatim from the same file.

Inputs: the given normalized pages (gray from cvtColor and, separately, an
independent gray image), shifted copies whose bubbles cross the image edges,
pages with drawn magenta rings and dark marks, and random color noise. Points:
every template bubble center with every template radius, plus random points
inside, near and outside the image with random radii.

It compiles once with g++ -O3 and once with clang++ -O3 -march=x86-64-v3, so
fused multiply-add contraction is also covered, as on ARM64 phones.

  python3 tools/check_dense_ring_search.py [normalized images...]
"""
import json, os, subprocess, sys, tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OPENCV = Path('/home/you/.local/opt/yse-opencv4/usr')
ENGINE = ROOT / 'native/omr_engine/omr-mobile-engine/cpp'
TEMPLATES = ROOT / 'app/src/main/assets/templates'
source = (ENGINE / 'src/geometry/BubbleGridRefiner.cpp').read_text()
marker = 'if (!(denseSearch && denseLocalSearch(bgr, gray, expected, radius, bestScore, best)))\n'
start = source.index(marker) + len(marker)
# The original dense loop, verbatim: from its first `for` to the matching brace.
depth, index, opened = 0, start, False
while True:
    char = source[index]
    if char == '{': depth += 1; opened = True
    elif char == '}':
        depth -= 1
        if opened and depth == 0: break
    index += 1
original_loop = source[start:index + 1]

TEST = r'''
#include "geometry/BubbleGridRefiner.cpp"
#include <fstream>
#include <iterator>
#include <opencv2/imgcodecs.hpp>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <random>
namespace omr { namespace {
void originalDense(const cv::Mat& bgr, const cv::Mat& gray, Point expected, float radius, float& bestScore, Point& best) {
  const PixelEvidenceView pixels(gray, bgr);
  ORIGINAL_LOOP
}
bool same(float a, float b) { return std::memcmp(&a, &b, sizeof a) == 0; }
bool same(Point a, Point b) { return same(a.x, b.x) && same(a.y, b.y); }
}}
using namespace omr;
struct Case { int image; Point point; float radius; };
int main(int argc, char** argv) {
  std::vector<float> radii;
  std::vector<Point> centers;
  for (int i = 1; i < argc; ++i) {
    std::string path(argv[i]);
    if (path.size() > 5 && path.substr(path.size() - 5) == ".json") {
      std::ifstream in(path); std::string text((std::istreambuf_iterator<char>(in)), {});
      const auto tpl = OmrTemplate::fromJson(text);
      std::vector<OmrTemplate> all{tpl}; all.insert(all.end(), tpl.geometryVariants.begin(), tpl.geometryVariants.end());
      for (const auto& t : all) for (const auto& b : t.answers.blocks) {
        radii.push_back(b.bubbleRadius);
        for (const auto y : b.yByRow) for (const auto& [option, x] : b.xByOption) centers.push_back({x, y});
      }
    }
  }
  std::mt19937 rng(1156);
  std::vector<std::pair<cv::Mat, cv::Mat>> images;
  auto add = [&](const cv::Mat& bgr, bool independentGray) {
    cv::Mat gray;
    if (independentGray) { gray.create(bgr.size(), CV_8UC1); cv::randu(gray, 0, 256); cv::GaussianBlur(gray, gray, cv::Size(5, 5), 0); }
    else cv::cvtColor(bgr, gray, cv::COLOR_BGR2GRAY);
    images.emplace_back(bgr, gray);
  };
  for (int i = 1; i < argc; ++i) {
    std::string path(argv[i]);
    if (path.size() > 5 && path.substr(path.size() - 5) == ".json") continue;
    cv::Mat page = cv::imread(path, cv::IMREAD_COLOR);
    if (page.empty()) continue;
    add(page, false);
    if (i % 3 == 0) add(page, true);
    // Shift the page so bubbles approach and cross every image edge.
    const int dx = std::uniform_int_distribution<int>(-60, 60)(rng), dy = std::uniform_int_distribution<int>(-60, 60)(rng);
    cv::Mat moved; const cv::Mat shift = (cv::Mat_<double>(2, 3) << 1, 0, dx, 0, 1, dy);
    cv::warpAffine(page, moved, shift, page.size(), cv::INTER_NEAREST, cv::BORDER_CONSTANT, cv::Scalar(255, 255, 255));
    add(moved, false);
    // Magenta rings and dark pencil marks near bubble centers.
    cv::Mat marked = page.clone();
    for (int k = 0; k < 160 && !centers.empty(); ++k) {
      const auto c = centers[std::uniform_int_distribution<size_t>(0, centers.size() - 1)(rng)];
      const cv::Point p(cvRound(c.x * page.cols / 1750.0) + std::uniform_int_distribution<int>(-9, 9)(rng),
                        cvRound(c.y * page.rows / 2480.0) + std::uniform_int_distribution<int>(-9, 9)(rng));
      if (k % 2) cv::circle(marked, p, std::uniform_int_distribution<int>(10, 30)(rng), cv::Scalar(180, 60, 220), std::uniform_int_distribution<int>(1, 4)(rng));
      else cv::circle(marked, p, std::uniform_int_distribution<int>(4, 14)(rng), cv::Scalar(40, 45, 50), cv::FILLED);
    }
    add(marked, false);
  }
  for (int k = 0; k < 6; ++k) {
    cv::Mat noise(std::uniform_int_distribution<int>(200, 900)(rng), std::uniform_int_distribution<int>(200, 900)(rng), CV_8UC3);
    cv::randu(noise, 0, 256);
    if (k % 2) cv::GaussianBlur(noise, noise, cv::Size(7, 7), 0);
    add(noise, k == 3);
  }
  if (radii.empty()) radii.push_back(24.0f);
  std::vector<Case> cases;
  for (int image = 0; image < static_cast<int>(images.size()); ++image) {
    const auto& bgr = images[static_cast<size_t>(image)].first;
    const float sx = bgr.cols / 1750.0f, sy = bgr.rows / 2480.0f;
    for (int k = 0; k < 900; ++k) {
      Point p; float r;
      if (k % 3 && !centers.empty()) {
        const auto c = centers[std::uniform_int_distribution<size_t>(0, centers.size() - 1)(rng)];
        p = {c.x * sx + std::uniform_real_distribution<float>(-3, 3)(rng), c.y * sy + std::uniform_real_distribution<float>(-3, 3)(rng)};
        r = radii[std::uniform_int_distribution<size_t>(0, radii.size() - 1)(rng)] * sx;
      } else {
        p = {std::uniform_real_distribution<float>(-50, bgr.cols + 50)(rng), std::uniform_real_distribution<float>(-50, bgr.rows + 50)(rng)};
        r = std::uniform_real_distribution<float>(2.5f, 42.0f)(rng);
        if (k % 7 == 0) p = {std::round(p.x) + 0.5f, std::round(p.y) + 0.5f};
      }
      cases.push_back({image, p, r});
    }
  }
  std::vector<std::vector<RingCandidate>> rings(images.size());
  for (size_t i = 0; i < images.size(); ++i) rings[i] = detectRingCandidates(createMagentaMask(images[i].first), radii);
  std::atomic<long> failures{0}, denseCalls{0}, scored{0};
  cv::parallel_for_(cv::Range(0, static_cast<int>(cases.size())), [&](const cv::Range& range) {
    for (int i = range.start; i < range.end; ++i) {
      const auto& c = cases[static_cast<size_t>(i)];
      const auto& [bgr, gray] = images[static_cast<size_t>(c.image)];
      const auto a = snapCenterLocal(bgr, gray, rings[static_cast<size_t>(c.image)], c.point, c.radius, false);
      const auto b = snapCenterLocal(bgr, gray, rings[static_cast<size_t>(c.image)], c.point, c.radius, true);
      if (!(same(a.expected, b.expected) && same(a.detected, b.detected) && same(a.snapped, b.snapped) &&
            same(a.confidence, b.confidence) && a.accepted == b.accepted && a.hasRing == b.hasRing)) ++failures;
      float s1 = -1.0f, s2 = -1.0f; Point p1 = c.point, p2 = c.point;
      originalDense(bgr, gray, c.point, c.radius, s1, p1);
      if (denseLocalSearch(bgr, gray, c.point, c.radius, s2, p2)) {
        ++denseCalls; if (s1 > -1.0f) ++scored;
        if (!same(s1, s2) || !same(p1, p2)) {
          if (failures < 10) std::printf("MISMATCH image %d (%a,%a) r=%a: %a vs %a\n", c.image, c.point.x, c.point.y, c.radius, s1, s2);
          ++failures;
        }
      }
    }
  });
  std::printf("{\"images\":%zu,\"cases\":%zu,\"denseComparisons\":%ld,\"withScore\":%ld,\"failures\":%ld}\n",
              images.size(), cases.size(), denseCalls.load(), scored.load(), failures.load());
  return failures ? 1 : 0;
}
'''


def build_and_run(compiler, flags, arguments, work):
    program = work / ('check-' + compiler)
    test = work / 'check_dense_ring_search.cpp'
    test.write_text(TEST.replace('ORIGINAL_LOOP', original_loop))
    command = [compiler, '-std=c++17', '-O3', *flags, str(test), '-o', str(program),
               f'-I{ENGINE / "src"}', f'-I{ENGINE / "include"}', f'-I{OPENCV / "include/opencv4"}',
               f'-L{OPENCV / "lib"}', f'-Wl,-rpath,{OPENCV / "lib"}',
               '-lopencv_imgcodecs', '-lopencv_imgproc', '-lopencv_calib3d', '-lopencv_core']
    lib = ROOT / 'output/omr-speed-1.1.56/lib-new-gcc'
    command += [f'-L{lib}', f'-Wl,-rpath,{lib}', '-llajvard_omr_mobile']
    subprocess.run(command, check=True)
    environment = dict(os.environ, LD_LIBRARY_PATH=f'{lib}:{OPENCV / "lib"}')
    result = subprocess.run([str(program), *arguments], capture_output=True, text=True, env=environment)
    return result.returncode, (result.stdout + result.stderr).strip()


if __name__ == '__main__':
    templates = [str(p) for p in sorted(TEMPLATES.glob('*.json'))]
    report = {}
    with tempfile.TemporaryDirectory() as directory:
        work = Path(directory)
        failed = False
        for compiler, flags in (('g++', []), ('clang++', ['-march=x86-64-v3'])):
            code, output = build_and_run(compiler, flags, templates + sys.argv[1:], work)
            print(compiler, output)
            last = output.splitlines()[-1] if output else '{}'
            report[compiler] = json.loads(last) if last.startswith('{') else {'error': output}
            failed = failed or code != 0
    sys.exit(1 if failed else 0)
