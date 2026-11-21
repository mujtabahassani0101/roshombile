#pragma once

#include <algorithm>
#include <array>
#include <cmath>

namespace omr {

struct Point {
  float x = 0.0f;
  float y = 0.0f;
};

struct Quad {
  Point topLeft;
  Point topRight;
  Point bottomRight;
  Point bottomLeft;
};

struct Rect {
  float x = 0.0f;
  float y = 0.0f;
  float width = 0.0f;
  float height = 0.0f;
};

inline float clamp01(float value) {
  return std::max(0.0f, std::min(1.0f, value));
}

inline float distance(Point a, Point b) {
  const float dx = a.x - b.x;
  const float dy = a.y - b.y;
  return std::sqrt(dx * dx + dy * dy);
}

inline Point lerp(Point a, Point b, float t) {
  return {a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t};
}

inline float polygonArea(const Quad& q) {
  const std::array<Point, 4> points = {q.topLeft, q.topRight, q.bottomRight, q.bottomLeft};
  float area = 0.0f;
  for (size_t i = 0; i < points.size(); ++i) {
    const Point& p = points[i];
    const Point& n = points[(i + 1) % points.size()];
    area += p.x * n.y - n.x * p.y;
  }
  return std::abs(area) * 0.5f;
}

inline bool pointInside(Point p, int width, int height, float margin = 0.0f) {
  return p.x >= margin && p.y >= margin && p.x <= static_cast<float>(width) - margin &&
         p.y <= static_cast<float>(height) - margin;
}

} // namespace omr
