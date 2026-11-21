#!/usr/bin/env python3
"""Prove adaptiveThresholdStrips (BarcodeReader.cpp, 1.1.54) equals one cv::adaptiveThreshold call.

The function is copied verbatim out of the engine source, compiled against the host
OpenCV the engine links, and compared pixel for pixel with the whole-image call on
random noise, blurred noise and crops/2x enlargements of the given images, for every odd
block size from 3 to 81, both adaptive methods and both binary types.

  python3 tools/check_strip_threshold.py [images...]
"""
import subprocess, sys, tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OPENCV = Path('/home/you/.local/opt/yse-opencv4/usr')
source = (ROOT / 'native/omr_engine/omr-mobile-engine/cpp/src/BarcodeReader.cpp').read_text()
start = source.index('void adaptiveThresholdStrips(')
function = source[start:source.index('\n} // namespace', start)]

TEST = r'''
#include <opencv2/core.hpp>
#include <opencv2/core/utility.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <cstdio>
#include <random>
#include <vector>
thread_local bool stripThreshold = true;
FUNCTION
int main(int argc, char** argv) {
  std::mt19937 rng(1154); long cases = 0, failures = 0;
  std::vector<cv::Mat> images;
  for (int i = 1; i < argc; ++i) { cv::Mat g = cv::imread(argv[i], cv::IMREAD_GRAYSCALE); if (!g.empty()) images.push_back(g); }
  for (int iteration = 0; iteration < 1600; ++iteration) {
    cv::Mat image;
    int rows = std::uniform_int_distribution<int>(40, 1400)(rng), cols = std::uniform_int_distribution<int>(20, 1300)(rng);
    if (!images.empty() && iteration % 2 == 0) {
      const cv::Mat& base = images[iteration / 2 % images.size()];
      rows = std::min(rows, base.rows); cols = std::min(cols, base.cols);
      const int y = std::uniform_int_distribution<int>(0, base.rows - rows)(rng);
      const int x = std::uniform_int_distribution<int>(0, base.cols - cols)(rng);
      image = base(cv::Rect(x, y, cols, rows)).clone();
      if (iteration % 4 == 0) {
        cv::Mat up; cv::resize(image, up, cv::Size(), 2.0, 2.0, cv::INTER_CUBIC);
        image = up(cv::Rect(0, 0, std::min(up.cols, 1400), std::min(up.rows, 1600))).clone();
      }
    } else {
      image = cv::Mat(rows, cols, CV_8UC1); cv::randu(image, 0, 256);
      if (iteration % 3 == 0) cv::GaussianBlur(image, image, cv::Size(0, 0), 3.0);
    }
    const int shortest = std::min(image.rows, image.cols);
    const int block = std::min(3 + 2 * std::uniform_int_distribution<int>(0, 39)(rng), shortest % 2 == 0 ? shortest - 1 : shortest);
    if (block < 3) continue;
    const int method = iteration % 5 == 0 ? cv::ADAPTIVE_THRESH_MEAN_C : cv::ADAPTIVE_THRESH_GAUSSIAN_C;
    const int type = iteration % 7 == 0 ? cv::THRESH_BINARY_INV : cv::THRESH_BINARY;
    const double delta = iteration % 3 == 0 ? 5.0 : std::uniform_real_distribution<double>(-10, 10)(rng);
    cv::Mat whole, strips;
    cv::adaptiveThreshold(image, whole, 255.0, method, type, block, delta);
    adaptiveThresholdStrips(image, strips, 255.0, method, type, block, delta);
    ++cases;
    if (whole.size() != strips.size() || cv::countNonZero(whole != strips) != 0) ++failures;
  }
  std::printf("{\"threads\": %d, \"cases\": %ld, \"failures\": %ld}\n", cv::getNumThreads(), cases, failures);
  return failures ? 1 : 0;
}
'''.replace('FUNCTION', function)

with tempfile.TemporaryDirectory() as folder:
    cpp = Path(folder) / 'strips.cpp'; binary = Path(folder) / 'strips'
    cpp.write_text(TEST)
    subprocess.run(['g++', '-O2', '-std=c++17', str(cpp), f'-I{OPENCV}/include/opencv4', f'-L{OPENCV}/lib',
                    '-lopencv_imgcodecs', '-lopencv_imgproc', '-lopencv_core', f'-Wl,-rpath,{OPENCV}/lib', '-o', str(binary)], check=True)
    sys.exit(subprocess.call([str(binary), *sys.argv[1:]], env={'LD_LIBRARY_PATH': str(OPENCV / 'lib')}))
