#include "omr/QuestionEngines.hpp"

#include <memory>

namespace omr {
NormalizeResult QuestionEngine::scan(const NormalizeRequest& request,
    const HeaderScanResult& header, const OmrTemplate& tpl, const OmrLiveOptions& options) const {
  if (!header.valid() || header.imagePath != request.imagePath ||
      header.sheetType.code != code() || tpl.sheetTypeCode != code() ||
      header.sheetType.templateId != tpl.templateId ||
      header.sheetType.questionCount != capacity() ||
      tpl.answers.questionCount != capacity()) {
    NormalizeResult rejected;
    rejected.status = "UNSUPPORTED_SHEET_TYPE";
    rejected.error = "sheet_template_mismatch:header_binding";
    rejected.reviewRequired = true;
    rejected.reviewReasons.push_back("sheet_template_mismatch");
    return rejected;
  }
  auto answerTemplate = tpl;
  const auto answerOnly = [](OmrTemplate& layout) {
    layout.barcode.enabled = false;
    layout.barcode.required = false;
    layout.studentCode.enabled = false;
  };
  answerOnly(answerTemplate);
  for (auto& variant : answerTemplate.geometryVariants) answerOnly(variant);
  NormalizeRequest answerRequest = request;
  answerRequest.header = std::make_shared<const HeaderScanResult>(header);
  answerRequest.qrOnly = false;
  NormalizeEngine geometry;
  geometry.configure(answerTemplate, options);
  return geometry.normalize(answerRequest);
}
} // namespace omr
