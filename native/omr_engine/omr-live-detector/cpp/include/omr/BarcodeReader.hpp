#pragma once

#include <opencv2/core.hpp>
#include <string>
#include <vector>
#include "OmrLiveResult.hpp"
#include "OmrTemplate.hpp"

namespace omr {

class BarcodeReader {
public:
  OmrBarcodeResult decode(const cv::Mat& normalizedGray, const OmrTemplate& tpl) const;

private:
  OmrBarcodeResult decodeRoi(
    const cv::Mat& normalizedGray,
    const Rect& roi,
    const std::vector<std::string>& formats,
    const std::string& expectedPayloadRegex
  ) const;
};

} // namespace omr
