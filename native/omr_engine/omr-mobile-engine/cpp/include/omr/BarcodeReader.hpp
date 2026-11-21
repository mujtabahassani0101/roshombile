#pragma once

#include <opencv2/core.hpp>
#include <string>
#include <vector>
#include "OmrLiveResult.hpp"
#include "OmrTemplate.hpp"

namespace omr {

// Owns exact decode reuse for one page on the calling thread. No identity or
// failed decode can survive the scope or be shared with another request.
class BarcodeDecodeCacheScope {
public:
  explicit BarcodeDecodeCacheScope(bool enabled = true);
  ~BarcodeDecodeCacheScope();
  BarcodeDecodeCacheScope(const BarcodeDecodeCacheScope&) = delete;
  BarcodeDecodeCacheScope& operator=(const BarcodeDecodeCacheScope&) = delete;
private:
  void* previous_;
  void* owned_;
};

// Selects the exact strip-parallel adaptive threshold (default) or the single
// whole-image call on the calling thread. Both give identical pixels; the
// switch exists for paired before/after device timings.
class AdaptiveThresholdStripsScope {
public:
  explicit AdaptiveThresholdStripsScope(bool enabled = true);
  ~AdaptiveThresholdStripsScope();
  AdaptiveThresholdStripsScope(const AdaptiveThresholdStripsScope&) = delete;
  AdaptiveThresholdStripsScope& operator=(const AdaptiveThresholdStripsScope&) = delete;
private:
  bool previous_;
};

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
