#pragma once

#include "omr/OmrLiveResult.hpp"
#include "omr/OmrTemplate.hpp"
#include <map>
#include <string>
#include <vector>

namespace roshd {
struct InkQuestionEvidence {
  std::string reason;
  omr::OmrAnswerQuestionResult original;
  int agreeingPasses=0;
  bool recovered=false;
  std::vector<int> confirmedMultipleOptions;
};
struct InkVerification {
  bool applied=false;
  std::string reason="unverified_geometry";
  double milliseconds=0;
  std::map<int,InkQuestionEvidence> questions;
};

// The original source reader owns every classification. The app compares its
// readings of registered image representations; it never draws or guesses ink.
InkVerification verifyInk(omr::NormalizeResult& sheet,const omr::OmrTemplate& layout);
}
