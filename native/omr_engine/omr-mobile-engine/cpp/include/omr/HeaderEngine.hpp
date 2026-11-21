#pragma once

#include <string>
#include "Geometry.hpp"
#include "OmrLiveResult.hpp"

namespace omr {

struct HeaderScanResult {
  std::string imagePath;
  OmrBarcodeResult qr;
  OmrQrSheetType sheetType;
  std::string studentCode;
  std::string identifier;
  Rect scannedRegion;
  int clockwiseRotation = 0;
  double processingTimeMs = 0;
  std::string error;
  bool valid() const { return sheetType.detected && qr.found && qr.status == "FOUND"; }
};

// Mixed-mode router: decodes the printed header before choosing an answer
// family. Fixed-family imports identify the normalized header in their own
// worker instead. Neither path searches the question blocks for identity.
class HeaderEngine {
public:
  HeaderScanResult scan(const std::string& imagePath, int maxInputDimension = 2000) const;
};

} // namespace omr
