#pragma once

#include "HeaderEngine.hpp"
#include "NormalizeEngine.hpp"

namespace omr {

// Each concrete engine is permanently bound to one physical family. Shared
// alignment and bubble measurement do not decode QR or infer another family.
class QuestionEngine {
public:
  virtual ~QuestionEngine() = default;
  virtual const char* code() const = 0;
  virtual int capacity() const = 0;
  NormalizeResult scan(const NormalizeRequest& request, const HeaderScanResult& header,
                       const OmrTemplate& tpl, const OmrLiveOptions& options) const;
};

class T1AnswerEngine final : public QuestionEngine {
public:
  const char* code() const override;
  int capacity() const override;
};
class T2AnswerEngine final : public QuestionEngine {
public:
  const char* code() const override;
  int capacity() const override;
};
class T3AnswerEngine final : public QuestionEngine {
public:
  const char* code() const override;
  int capacity() const override;
};

} // namespace omr
