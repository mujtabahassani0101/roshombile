#include <algorithm>
#include <cmath>
#include <fstream>
#include <iostream>
#include <sstream>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include "omr/HeaderEngine.hpp"
#include "omr/QuestionEngines.hpp"
#include "omr/OmrTemplate.hpp"

namespace {
const std::string root = "/data/local/tmp/omr-bench/";
bool sameAnswers(const omr::NormalizeResult& a, const omr::NormalizeResult& b, int count) {
  if (!a.success || !b.success || a.answers.questions.size() != count || b.answers.questions.size() != count) return false;
  for (int i=0; i<count; ++i) {
    const auto& x=a.answers.questions[i]; const auto& y=b.answers.questions[i];
    if (x.status!="MARKED" || x.selectedOptionNumber!=i%4+1 || x.status!=y.status ||
        x.selectedOptionNumber!=y.selectedOptionNumber || x.selectedOptions!=y.selectedOptions ||
        std::abs(x.confidence-y.confidence)>1e-12 || x.options.size()!=y.options.size()) return false;
    for (size_t j=0; j<x.options.size(); ++j) if (x.options[j].option!=y.options[j].option ||
        std::abs(x.options[j].fillRatio-y.options[j].fillRatio)>1e-12 ||
        std::abs(x.options[j].score-y.options[j].score)>1e-12) return false;
  }
  return true;
}
}
int main() {
  omr::T1AnswerEngine t1; omr::T2AnswerEngine t2; omr::T3AnswerEngine t3;
  const std::vector<omr::QuestionEngine*> engines{&t1,&t2,&t3,&t3};
  const std::vector<std::string> names{"yse_omr_a5_t1_100q_v2.json","yse_omr_a5_t2_60q_v1.json",
    "yse_omr_a5_t3_40q_v1.json","yse_omr_a5_t3_40q_v1.json"};
  bool ok=true; int checked=0, fastCount=0, primaryFastCount=0;
  for (int kind=0; kind<4; ++kind) {
    std::ifstream input(root+names[kind]); std::ostringstream text; text<<input.rdbuf();
    const auto tpl=omr::OmrTemplate::fromJson(text.str());
    const auto options=omr::OmrTemplate::optionsFromJson("",tpl);
    const auto source=cv::imread(root+(kind==3?"t3-geometry-2.jpg":"r11-T"+std::to_string(kind+1)+".jpg"));
    if (source.empty()) return 2;
    for (int rotation : {0,90,180,270}) {
      cv::Mat page=source;
      if(rotation) cv::rotate(source,page,rotation==90?cv::ROTATE_90_CLOCKWISE:
        rotation==180?cv::ROTATE_180:cv::ROTATE_90_COUNTERCLOCKWISE);
      const auto path=root+"speed-orientation-"+std::to_string(kind)+"-"+std::to_string(rotation)+".png";
      cv::imwrite(path,page);
      auto header=omr::HeaderEngine{}.scan(path,2000);
      if(!header.valid()) {ok=false;continue;}
      omr::NormalizeRequest request; request.imagePath=path; request.maxInputDimension=2000;
      request.persistNormalizedImage=false; request.collectQualityMetrics=false;
      const auto fast=engines[kind]->scan(request,header,tpl,options);
      header.clockwiseRotation=(header.clockwiseRotation+90)%360;
      const auto fallback=engines[kind]->scan(request,header,tpl,options);
      const bool used=std::find(fast.warnings.begin(),fast.warnings.end(),"header_orientation_geometry_verified")!=fast.warnings.end();
      const bool fallbackUsed=std::find(fallback.warnings.begin(),fallback.warnings.end(),"header_orientation_geometry_verified")==fallback.warnings.end();
      const bool same=sameAnswers(fast,fallback,engines[kind]->capacity());
      const bool primaryFast=std::find(fast.warnings.begin(),fast.warnings.end(),"primary_layout_geometry_verified")!=fast.warnings.end();
      const bool wrongHintFast=std::find(fallback.warnings.begin(),fallback.warnings.end(),"primary_layout_geometry_verified")!=fallback.warnings.end();
      ok &= same && fallbackUsed;
      ok &= !wrongHintFast && (kind!=3 || !primaryFast);
      if(used) ++fastCount;
      if(primaryFast) ++primaryFastCount;
      if(same) checked+=engines[kind]->capacity();
      std::cout<<"kind="<<kind<<" rotation="<<rotation<<" verified_hint="<<used<<" primary_verified="<<primaryFast<<" exact_answers="<<same<<'\n';
    }
  }
  ok &= fastCount>=12 && primaryFastCount>=12 && checked==960;
  // A missing answer-rail dot deliberately prevents the complete-rail fast
  // path. Its cached probe must match an independent persisted full read.
  std::ifstream input(root+names[2]); std::ostringstream text; text<<input.rdbuf();
  const auto tpl=omr::OmrTemplate::fromJson(text.str());
  const auto& layout=tpl.geometryVariants.front();
  auto partial=cv::imread(root+"t3-geometry-2.jpg");
  cv::circle(partial,{cvRound(layout.registration.leftRailX),cvRound(layout.registration.rowY[11])},
    cvRound(layout.registration.dotRadius*1.5f),cv::Scalar(245,245,245),cv::FILLED);
  const auto path=root+"speed-partial-rail.png"; cv::imwrite(path,partial);
  const auto header=omr::HeaderEngine{}.scan(path,2000);
  omr::NormalizeRequest cachedRequest; cachedRequest.imagePath=path; cachedRequest.maxInputDimension=2000;
  cachedRequest.persistNormalizedImage=false; cachedRequest.collectQualityMetrics=false;
  const auto options=omr::OmrTemplate::optionsFromJson("",tpl);
  const auto cached=t3.scan(cachedRequest,header,tpl,options);
  auto fullRequest=cachedRequest; fullRequest.persistNormalizedImage=true; fullRequest.outputDirectory=root;
  const auto full=t3.scan(fullRequest,header,tpl,options);
  const bool reused=std::find(cached.warnings.begin(),cached.warnings.end(),"normalization_probe_reused")!=cached.warnings.end();
  const bool partialExact=sameAnswers(cached,full,40);
  const bool partialFast=std::find(cached.warnings.begin(),cached.warnings.end(),"primary_layout_geometry_verified")!=cached.warnings.end();
  ok &= reused && partialExact && !partialFast;
  std::cout<<"partial_rail_cached_probe="<<reused<<" exact_40="<<partialExact<<'\n';
  // A second layout with identical registration cannot be excluded by rail
  // geometry, even when the primary detection itself is perfect.
  auto ambiguous=tpl;
  auto equivalent=tpl; equivalent.geometryVariants.clear(); equivalent.geometryId="equivalent-registration";
  ambiguous.geometryVariants.push_back(equivalent);
  cachedRequest.imagePath=root+"r11-T3.jpg";
  const auto primaryHeader=omr::HeaderEngine{}.scan(cachedRequest.imagePath,2000);
  const auto ambiguousResult=t3.scan(cachedRequest,primaryHeader,ambiguous,options);
  fullRequest=cachedRequest; fullRequest.persistNormalizedImage=true; fullRequest.outputDirectory=root;
  const auto ambiguousFull=t3.scan(fullRequest,primaryHeader,ambiguous,options);
  const bool ambiguousFast=std::find(ambiguousResult.warnings.begin(),ambiguousResult.warnings.end(),"primary_layout_geometry_verified")!=ambiguousResult.warnings.end();
  const bool ambiguousExact=sameAnswers(ambiguousResult,ambiguousFull,40);
  ok &= !ambiguousFast && ambiguousExact;
  std::cout<<"ambiguous_registration_full_search="<<!ambiguousFast<<" exact_40="<<ambiguousExact<<'\n';
  auto primaryPartial=cv::imread(cachedRequest.imagePath);
  cv::circle(primaryPartial,{cvRound(tpl.registration.leftRailX),cvRound(tpl.registration.rowY[11])},
    cvRound(tpl.registration.dotRadius*1.5f),cv::Scalar(245,245,245),cv::FILLED);
  cachedRequest.imagePath=root+"speed-primary-partial-rail.png";cv::imwrite(cachedRequest.imagePath,primaryPartial);
  const auto partialHeader=omr::HeaderEngine{}.scan(cachedRequest.imagePath,2000);
  const auto primaryPartialResult=t3.scan(cachedRequest,partialHeader,tpl,options);
  fullRequest=cachedRequest; fullRequest.persistNormalizedImage=true; fullRequest.outputDirectory=root;
  const auto primaryPartialFull=t3.scan(fullRequest,partialHeader,tpl,options);
  const bool primaryPartialFast=std::find(primaryPartialResult.warnings.begin(),primaryPartialResult.warnings.end(),"primary_layout_geometry_verified")!=primaryPartialResult.warnings.end();
  const bool primaryPartialExact=sameAnswers(primaryPartialResult,primaryPartialFull,40);
  ok &= !primaryPartialFast && primaryPartialExact;
  std::cout<<"primary_missing_dot_full_search="<<!primaryPartialFast<<" exact_40="<<primaryPartialExact<<'\n';
  std::cout<<(ok?"PASS":"FAIL")<<": verified header orientation and incorrect-hint fallback; "
    <<checked<<" exact answers, fill ratios and confidence; fast_cases="<<fastCount<<" primary_fast_cases="<<primaryFastCount<<std::endl;
  return ok?0:1;
}
