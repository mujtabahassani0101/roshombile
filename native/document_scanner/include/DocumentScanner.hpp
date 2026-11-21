#pragma once
#include <array>
#include <string>
#include <opencv2/core.hpp>

namespace yse::document {
using Quad = std::array<cv::Point2f, 4>; // clockwise TL, TR, BR, BL; normalized upright pixels
struct Detection {
  bool found = false;
  Quad corners{};
  double confidence = 0, area = 0, edgeSupport = 0, brightness = 0, sharpness = 0, glare = 0;
  int width = 0, height = 0;
  std::string reason = "no_document";
};
Detection detect(const cv::Mat& input, int limit = 960);
bool validQuad(const Quad& points, double minimumArea = .06);
cv::Mat rectify(const cv::Mat& upright, const Quad& corners, int maxDimension = 2000, int turns = 0, double padding = .012);
std::string json(const Detection& value);
std::string processFile(const std::string& input, const std::string& output, const Quad* manual = nullptr,
                        int maxDimension = 2000, int turns = 0);
}
