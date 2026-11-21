#include <algorithm>
#include <array>
#include <filesystem>
#include <fstream>
#include <future>
#include <iostream>
#include <set>
#include <sstream>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include "omr/MobileOmrBatch.hpp"
#include "omr/OmrTemplate.hpp"
#include "BarcodeFormat.h"
#include "BitMatrix.h"
#include "MultiFormatWriter.h"

namespace {
const std::array<std::string, 3> names{
  "yse_omr_a5_t1_100q_v2.json", "yse_omr_a5_t2_60q_v1.json", "yse_omr_a5_t3_40q_v1.json"
};
bool require(bool condition, const std::string& message) {
  if (!condition) std::cerr << "FAIL: " << message << '\n';
  return condition;
}
std::string read(const std::filesystem::path& path) {
  std::ifstream input(path); std::ostringstream out; out << input.rdbuf(); return out.str();
}
void render(const omr::OmrTemplate& tpl, const std::string& path) {
  cv::Mat page(cvRound(tpl.height), cvRound(tpl.width), CV_8UC3, cv::Scalar(245,245,245));
  for (bool left : {true,false}) for (float y : tpl.registration.rowsForSide(left)) {
    const float x=left?tpl.registration.leftRailX:tpl.registration.rightRailX;
    cv::circle(page, {cvRound(x),cvRound(y)}, cvRound(tpl.registration.dotRadius), cv::Scalar(0,0,0), cv::FILLED, cv::LINE_AA);
  }
  for (const auto& block : tpl.answers.blocks) for (int row=0; row<block.questionCount; ++row) {
    for (const auto& [option,x] : block.xByOption) {
      cv::Point center(cvRound(x),cvRound(block.yByRow[row]));
      cv::circle(page,center,cvRound(block.bubbleRadius),cv::Scalar(60,60,60),2,cv::LINE_AA);
      if (std::stoi(option)==(block.questionStart+row-1)%4+1)
        cv::circle(page,center,cvRound(block.bubbleRadius*.73f),cv::Scalar(0,0,0),cv::FILLED,cv::LINE_AA);
    }
  }
  const auto& r=tpl.barcode.roi;
  ZXing::MultiFormatWriter writer(ZXing::BarcodeFormat::QRCode);
  writer.setMargin(4).setEccLevel(2);
  auto qr=writer.encode(tpl.sheetTypeCode+":001234:Ab1Cd2E3",cvRound(r.width),cvRound(r.height));
  for(int y=0;y<qr.height();++y) for(int x=0;x<qr.width();++x)
    page.at<cv::Vec3b>(cvRound(r.y)+y,cvRound(r.x)+x)=qr.get(x,y)?cv::Vec3b(0,0,0):cv::Vec3b(255,255,255);
  cv::imwrite(path,page);
}
bool exact(const omr::NormalizeResult& sheet, int capacity) {
  if (!sheet.success || sheet.answers.questions.size()!=size_t(capacity)) return false;
  for (int q=0;q<capacity;++q)
    if (sheet.answers.questions[q].status!="MARKED" || sheet.answers.questions[q].selectedOptionNumber!=q%4+1) return false;
  return true;
}
}

int main() {
  namespace fs=std::filesystem;
  const auto output=fs::temp_directory_path()/"omr-parallel-queues-regression";
  fs::create_directories(output);
  omr::MobileOmrBatchRequest request;
  request.outputDirectory=output.string();
  request.maxInputDimension=2000;
  request.returnNormalizedImages=true;
  for (const auto& name:names) request.templateJsons.push_back(read(fs::path(OMR_PARALLEL_TEMPLATE_DIR)/name));
  std::array<std::string,3> images;
  for(size_t i=0;i<3;++i) {
    images[i]=(output/("T"+std::to_string(i+1)+".png")).string();
    render(omr::OmrTemplate::fromJson(request.templateJsons[i]),images[i]);
  }
  // A slow-family backlog must not hold T2/T3 behind its pending answers.
  for(int round=0;round<4;++round) for(size_t family=0;family<3;++family)
    request.pages.push_back({int(request.pages.size()+10),images[family],{}});
  request.pages.insert(request.pages.begin()+2,{902,"",{}});
  request.pages.insert(request.pages.begin()+6,{906,(output/"missing.png").string(),{}});
  const auto result=omr::MobileOmrBatchProcessor::process(request);
  bool ok=require(result.status=="PARTIAL" && result.sheets.size()==request.pages.size(),"preserve every input including failures");
  std::array<std::vector<const omr::NormalizeResult*>,3> byFamily;
  std::set<std::string> answerThreads, headerThreads, outputPaths;
  int answerCount=0;
  for(size_t i=0;i<result.sheets.size();++i) {
    const auto& sheet=result.sheets[i];
    if(request.pages[i].imagePath.empty() || request.pages[i].imagePath.find("missing.png")!=std::string::npos) {
      ok &= require(!sheet.success && sheet.answerEngineRuns==0,"invalid input never enters answer queues");
      continue;
    }
    const size_t family=sheet.sheetType.code=="T1"?0:sheet.sheetType.code=="T2"?1:2;
    const int capacity=family==0?100:family==1?60:40;
    ok &= require(request.pages[i].imagePath==images[family] && exact(sheet,capacity),"correct family and independently known answers at original input slot");
    ok &= require(sheet.headerEngineRuns==1 && sheet.answerEngineRuns==1,"one header and exactly one answer engine");
    ok &= require(sheet.answerStartedMs>=sheet.headerFinishedMs && sheet.answerFinishedMs>sheet.answerStartedMs,"header handoff precedes answer scan");
    ok &= require(sheet.headerQueueWaitMs>=0 && sheet.answerQueueWaitMs>=0,"nonnegative queue measurements");
    byFamily[family].push_back(&sheet);
    headerThreads.insert(sheet.headerWorkerThread);
    answerThreads.insert(sheet.answerWorkerThread);
    outputPaths.insert(sheet.normalizedImagePath);
    answerCount+=capacity;
  }
  ok &= require(headerThreads.size()==1 && answerThreads.size()==3,"one header thread and three distinct answer threads");
  for(const auto& thread:answerThreads) ok &= require(!headerThreads.contains(thread),"header worker is independent of all answer workers");
  ok &= require(outputPaths.size()==12 && !outputPaths.contains(""),"parallel output files have distinct paths");
  for(const auto& family:byFamily) for(size_t i=1;i<family.size();++i)
    ok &= require(family[i]->answerStartedMs>=family[i-1]->answerFinishedMs &&
      family[i]->answerWorkerThread==family[i-1]->answerWorkerThread,"each family is FIFO on its own worker");
  double tripleOverlap=0;
  for(const auto* t1:byFamily[0]) for(const auto* t2:byFamily[1]) for(const auto* t3:byFamily[2])
    tripleOverlap=std::max(tripleOverlap,std::min({t1->answerFinishedMs,t2->answerFinishedMs,t3->answerFinishedMs})-
      std::max({t1->answerStartedMs,t2->answerStartedMs,t3->answerStartedMs}));
  ok &= require(tripleOverlap>0,"measured simultaneous T1/T2/T3 execution");
  bool headerOverlap=false;
  for(const auto& h:result.sheets) for(const auto& a:result.sheets)
    if(h.headerEngineRuns && a.answerEngineRuns &&
      std::max(h.headerStartedMs,a.answerStartedMs)<std::min(h.headerFinishedMs,a.answerFinishedMs)) headerOverlap=true;
  ok &= require(headerOverlap,"header scanning overlaps answer work");
  std::ofstream(output/"mixed-response.json") << result.json;

  // Concurrent single-image C ABI callers must share the same three workers.
  std::vector<std::future<omr::MobileOmrBatchResult>> calls;
  for(int i=0;i<9;++i) {
    auto single=request;
    single.returnNormalizedImages=false;
    single.pages={{i+1,images[i%3],{}}};
    calls.push_back(std::async(std::launch::async,[single] {return omr::MobileOmrBatchProcessor::process(single);}));
  }
  for(size_t i=0;i<calls.size();++i) {
    const auto single=calls[i].get();
    const auto& sheet=single.sheets.front();
    ok &= require(single.success && exact(sheet,i%3==0?100:i%3==1?60:40),"concurrent single-page requests keep their own results");
    ok &= require(answerThreads.contains(sheet.answerWorkerThread) && headerThreads.contains(sheet.headerWorkerThread),"queues and workers are shared across requests");
  }
  auto qrOnly=request; qrOnly.pages={{1,images[1],{}}}; qrOnly.qrOnly=true;
  const auto qrResult=omr::MobileOmrBatchProcessor::process(qrOnly);
  ok &= require(qrResult.success && qrResult.sheets.front().answerEngineRuns==0,"QR-only mode bypasses all answer queues");
  auto missingTemplate=request; missingTemplate.templateJsons.resize(1); missingTemplate.pages={{1,images[2],{}}};
  const auto missing=omr::MobileOmrBatchProcessor::process(missingTemplate);
  ok &= require(!missing.success && missing.sheets.front().answerEngineRuns==0,"missing template never routes to another family");
  // Fixed-family input skips the header FIFO, without replacing QR identity.
  // Compare every reported observation against the mixed path on the same pixels.
  for (size_t family=0;family<3;++family) {
    auto fixed=request;
    fixed.knownSheetType="T"+std::to_string(family+1);
    fixed.returnNormalizedImages=false;
    fixed.pages={{1,images[family],{}}};
    fixed.progressId="fixed-family-"+std::to_string(family);
    const auto direct=omr::MobileOmrBatchProcessor::process(fixed);
    const auto& actual=direct.sheets.front();
    const auto& baseline=*byFamily[family].front();
    ok &= require(exact(actual,family==0?100:family==1?60:40),"fixed family retains all known answers");
    ok &= require(actual.headerEngineRuns==0 && actual.headerWorkerThread.empty() &&
      actual.answerEngineRuns==1 && actual.answerWorkerThread==baseline.answerWorkerThread,
      "fixed family uses its existing FIFO without running or waiting for a header");
    ok &= require(actual.barcode.text==baseline.barcode.text,"fixed family reads printed identity");
    ok &= require(actual.answerQrDecodeCalls==1,"fixed family decodes identity only once after choosing geometry");
    if (std::find(baseline.warnings.begin(),baseline.warnings.end(),"primary_layout_geometry_verified")!=baseline.warnings.end())
      ok &= require(std::find(actual.warnings.begin(),actual.warnings.end(),"primary_layout_geometry_verified")!=actual.warnings.end(),
        "fixed family preserves the verified-primary geometry shortcut");
    if (actual.answers.questions.size()==baseline.answers.questions.size())
      for (size_t q=0;q<actual.answers.questions.size();++q) {
        const auto& a=actual.answers.questions[q]; const auto& b=baseline.answers.questions[q];
        ok &= require(std::abs(a.confidence-b.confidence)<.000001,"fixed and mixed confidence identical");
        for(size_t o=0;o<a.options.size();++o)
          ok &= require(std::abs(a.options[o].fillRatio-b.options[o].fillRatio)<.000001,"fixed and mixed fill ratios identical");
      }
    const auto live=omr::MobileOmrBatchProcessor::progressJson(fixed.progressId);
    ok &= require(live.find("\"type\":\""+fixed.knownSheetType+"\"")!=std::string::npos &&
      live.find("header")==std::string::npos,"fixed progress tracks the selected family directly");
    omr::MobileOmrBatchProcessor::releaseProgress(fixed.progressId);
    std::ofstream(output/(fixed.knownSheetType+"-fixed-response.json")) << direct.json;
    for (int rotation:{90,180,270}) {
      auto source=cv::imread(images[family]); cv::Mat rotated;
      cv::rotate(source,rotated,rotation==90?cv::ROTATE_90_CLOCKWISE:rotation==180?cv::ROTATE_180:cv::ROTATE_90_COUNTERCLOCKWISE);
      const auto path=(output/(fixed.knownSheetType+"-rotated-"+std::to_string(rotation)+".png")).string();
      cv::imwrite(path,rotated); fixed.pages={{1,path,{}}}; fixed.progressId="";
      const auto turned=omr::MobileOmrBatchProcessor::process(fixed);
      ok &= require(turned.success && exact(turned.sheets.front(),family==0?100:family==1?60:40),"fixed family retains rotated geometry fallback");
    }
    // Preserve the selected layout but change the printed prefix. Geometry alone
    // must never authorize a fabricated identity or a mismatched family.
    const auto tpl=omr::OmrTemplate::fromJson(request.templateJsons[family]);
    auto wrongTpl=tpl; wrongTpl.sheetTypeCode=family==0?"T2":"T1";
    const auto wrongPath=(output/(fixed.knownSheetType+"-wrong-qr.png")).string();
    render(wrongTpl,wrongPath); fixed.pages={{1,wrongPath,{}}};
    const auto wrong=omr::MobileOmrBatchProcessor::process(fixed);
    ok &= require(!wrong.success && wrong.sheets.front().answers.questions.empty(),"wrong printed family cannot be graded in fixed mode");
    auto noQr=cv::imread(images[family]);
    const auto& qrBox=tpl.barcode.roi;
    cv::rectangle(noQr,{cvRound(qrBox.x),cvRound(qrBox.y),cvRound(qrBox.width),cvRound(qrBox.height)},cv::Scalar(245,245,245),cv::FILLED);
    const auto blankPath=(output/(fixed.knownSheetType+"-no-qr.png")).string();
    cv::imwrite(blankPath,noQr); fixed.pages={{1,blankPath,{}}};
    const auto blank=omr::MobileOmrBatchProcessor::process(fixed);
    ok &= require(!blank.success && blank.sheets.front().answers.questions.empty(),"no identity never invents answers in fixed mode");
    std::cout << fixed.knownSheetType << " mixed_ms=" << baseline.processingTimeMs
      << " fixed_ms=" << actual.processingTimeMs << " fixed_header_runs=" << actual.headerEngineRuns << '\n';
  }
  auto invalidMode=request; invalidMode.knownSheetType="T4";
  ok &= require(!omr::MobileOmrBatchProcessor::process(invalidMode).success,"unknown fixed family is rejected");
  std::cout << "known_answers=" << answerCount << " triple_overlap_ms=" << tripleOverlap
    << " answer_threads=" << answerThreads.size() << " header_threads=" << headerThreads.size() << '\n';
  std::cout << (ok?"PASS":"FAIL") << ": mixed FIFO queues, real overlap, failed-page order, isolated outputs, 9 concurrent requests, QR-only and missing template\n";
  return ok?0:1;
}
