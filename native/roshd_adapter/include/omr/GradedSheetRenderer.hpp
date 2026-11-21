#pragma once
#include <string>

namespace omr {
// Draws only derived verdicts at measured normalized bubble positions. No answer key is accepted.
std::string renderGradedSheetJson(const std::string& request);
}
