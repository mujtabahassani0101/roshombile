#pragma once

#include <cstdint>

namespace omr {

enum class ImageFormat {
  Gray8,
  Bgr24,
  Rgba32,
};

struct ImageView {
  const uint8_t* data = nullptr;
  int width = 0;
  int height = 0;
  int rowStride = 0;
  ImageFormat format = ImageFormat::Gray8;
};

} // namespace omr
