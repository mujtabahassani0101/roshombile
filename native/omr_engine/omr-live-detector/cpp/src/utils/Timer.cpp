#include "utils/Timer.hpp"

namespace omr {

Timer::Timer() : start_(std::chrono::steady_clock::now()) {}

double Timer::elapsedMs() const {
  const auto elapsed = std::chrono::steady_clock::now() - start_;
  return std::chrono::duration<double, std::milli>(elapsed).count();
}

} // namespace omr
