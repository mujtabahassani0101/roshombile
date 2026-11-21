#pragma once

#include <chrono>

namespace omr {

class Timer {
public:
  Timer();
  double elapsedMs() const;

private:
  std::chrono::steady_clock::time_point start_;
};

} // namespace omr
