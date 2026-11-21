#include <iostream>
#include <opencv2/imgproc.hpp>
#include "utils/EllipticalMorphology.hpp"

int main() {
  cv::RNG random(180019);
  int checked = 0;
  for (const cv::Size size : {cv::Size(7, 9), cv::Size(63, 47), cv::Size(180, 240)}) {
    for (int pattern = 0; pattern < 5; ++pattern) {
      cv::Mat parent(size.height + 4, size.width + 6, CV_8UC1);
      random.fill(parent, cv::RNG::UNIFORM, 0, 256);
      // Also exercise non-contiguous ROI input and pixels touching the border.
      cv::Mat image = parent(cv::Rect(3, 2, size.width, size.height));
      if (pattern == 1) image.setTo(255);
      if (pattern == 2) image.setTo(0);
      if (pattern >= 3) {
        image.setTo(230);
        cv::circle(image, cv::Point(0, 0), 6, cv::Scalar(0), cv::FILLED);
        cv::circle(image, cv::Point(image.cols - 1, image.rows - 1), 9, cv::Scalar(10), cv::FILLED);
        cv::rectangle(image, cv::Rect(image.cols / 3, 0, 2, image.rows), cv::Scalar(25), cv::FILLED);
        if (pattern == 4) cv::GaussianBlur(image, image, cv::Size(3, 3), 0);
      }
      for (int kernelSize = 3; kernelSize <= 101; kernelSize += 2) {
        cv::Mat expected;
        cv::morphologyEx(image, expected, cv::MORPH_BLACKHAT,
          cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(kernelSize, kernelSize)),
          cv::Point(-1, -1), 1, cv::BORDER_CONSTANT | cv::BORDER_ISOLATED);
        const auto actual = omr::ellipticalBlackHat(image, kernelSize);
        if (cv::norm(expected, actual, cv::NORM_INF) != 0) {
          std::cerr << "FAIL: mask differs at size=" << size << " pattern=" << pattern
            << " kernel=" << kernelSize << '\n';
          return 1;
        }
        ++checked;
      }
    }
  }
  std::cout << "PASS: " << checked << " exact elliptical masks, kernels 3..101, noise, borders, tiny images and strided ROIs\n";
  return 0;
}
