#pragma once

#include <string>
#include <memory>
#include <vector>
#include "HeaderEngine.hpp"
#include "Geometry.hpp"
#include "OmrLiveOptions.hpp"
#include "OmrLiveResult.hpp"
#include "OmrTemplate.hpp"

namespace omr {

struct NormalizeRequest {
  // Internal typed handoff. No JSON field accepts a fabricated header result.
  // Mixed-mode answer engines supply this; fixed-family mode reads identity
  // only after normalizing its already-selected layout.
  std::shared_ptr<const HeaderScanResult> header;
  // Fixed-family mode bypasses HeaderEngine. QR identity is still required.
  bool knownSheetType = false;
  std::shared_ptr<int> identityQrDecodeCalls;
  std::string imagePath;
  std::string outputDirectory;
  bool returnDebugImages = false;
  // Desktop callers keep the normalized sheet by default. Mobile callers can
  // disable this to avoid a full-resolution image encode and filesystem write.
  bool persistNormalizedImage = true;
  // Persisting pixels must not repeat the already selected geometry search.
  // Kept switchable for exact before/after regression and device timings.
  bool reusePersistedGeometry = true;
  // Optional memory guard for mobile captures. Zero keeps the original resolution.
  int maxInputDimension = 0;
  // Brightness/sharpness diagnostics do not participate in still-image OMR
  // decisions. Minimal mobile scans can skip them without changing detection.
  bool collectQualityMetrics = true;
  // Header-only diagnostics / internal geometry-only probes. No bubble reading.
  bool qrOnly = false;
  // Internal geometry comparisons bypass the header-only early return. The
  // compatibility name is retained; mixed-mode QR is never decoded twice.
  bool allowRawQrShortcut = true;
  // Internal alternate-layout probe; retained only after geometric validation.
  bool headerOrientationOnly = false;
  // Layout probes rank rails and homographies only. When set, a probe keeps
  // its exact warp inputs and the page is warped once, for the selected
  // layout, with identical pixels. Kept switchable for paired timings.
  bool deferProbeWarps = true;
  // Optional caller-supplied sheet corner markers in percentages of the input image.
  // Each point uses x/y in the 0..100 range. The normalizer converts them to pixels,
  // orders them, builds a homography, and runs answer reading on the warped sheet.
  std::vector<Point> anchorPointsPercent;
};

class NormalizeEngine {
public:
  void configure(const OmrTemplate& tpl, const OmrLiveOptions& opts);
  NormalizeResult normalize(const NormalizeRequest& request);

private:
  struct PreparedNormalization;
  NormalizeResult normalizeImpl(const NormalizeRequest& request, PreparedNormalization* prepared,
    const PreparedNormalization* reusable = nullptr);
  NormalizeResult readAnswers(const NormalizeRequest& request, const PreparedNormalization& prepared) const;
  OmrTemplate template_;
  OmrLiveOptions options_;
  bool configured_ = false;
};

} // namespace omr
