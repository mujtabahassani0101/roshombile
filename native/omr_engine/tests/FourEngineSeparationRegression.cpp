#include <algorithm>
#include <fstream>
#include <iostream>
#include <sstream>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include "omr/HeaderEngine.hpp"
#include "omr/QuestionEngines.hpp"
#include "omr/OmrTemplate.hpp"
#include "BarcodeFormat.h"
#include "BitMatrix.h"
#include "MultiFormatWriter.h"

namespace {
const std::string root = "/data/local/tmp/omr-four108";
std::string read(const std::string& path) {
  std::ifstream input(path); std::ostringstream out; out << input.rdbuf(); return out.str();
}
void putQr(cv::Mat& image, cv::Rect box, const std::string& payload) {
  ZXing::MultiFormatWriter writer(ZXing::BarcodeFormat::QRCode);
  writer.setMargin(4).setEccLevel(2);
  const auto matrix = writer.encode(payload, box.width, box.height);
  for (int y=0;y<matrix.height();++y) for (int x=0;x<matrix.width();++x)
    image.at<cv::Vec3b>(box.y+y,box.x+x) = matrix.get(x,y) ? cv::Vec3b(0,0,0) : cv::Vec3b(255,255,255);
}
cv::Mat render(const omr::OmrTemplate& tpl, const std::string& payload) {
  cv::Mat page(cvRound(tpl.height),cvRound(tpl.width),CV_8UC3,cv::Scalar(245,245,245));
  for(bool left:{true,false}) for(float y:tpl.registration.rowsForSide(left)) {
    const float x=left?tpl.registration.leftRailX:tpl.registration.rightRailX;
    cv::circle(page,{cvRound(x),cvRound(y)},cvRound(tpl.registration.dotRadius),cv::Scalar(0,0,0),cv::FILLED,cv::LINE_AA);
  }
  for(const auto& block:tpl.answers.blocks) for(int row=0;row<block.questionCount;++row) {
    const int q=block.questionStart+row;
    for(const auto& [option,x]:block.xByOption) {
      const cv::Point center(cvRound(x),cvRound(block.yByRow[row]));
      cv::circle(page,center,cvRound(block.bubbleRadius),cv::Scalar(60,60,60),2,cv::LINE_AA);
      if(std::stoi(option)==(q-1)%4+1)
        cv::circle(page,center,cvRound(block.bubbleRadius*.73f),cv::Scalar(0,0,0),cv::FILLED,cv::LINE_AA);
    }
  }
  const auto& r=tpl.barcode.roi;
  putQr(page,{cvRound(r.x),cvRound(r.y),cvRound(r.width),cvRound(r.height)},payload);
  return page;
}
bool exact(const omr::NormalizeResult& result,int count) {
  if(!result.success || result.answers.questions.size()!=static_cast<std::size_t>(count)) return false;
  for(int i=0;i<count;++i) if(result.answers.questions[i].status!="MARKED" ||
      result.answers.questions[i].selectedOptionNumber!=i%4+1) return false;
  return true;
}
}

int main() {
  bool ok=true;
  omr::T1AnswerEngine t1; omr::T2AnswerEngine t2; omr::T3AnswerEngine t3;
  const std::vector<omr::QuestionEngine*> engines{&t1,&t2,&t3};
  const std::vector<std::string> files{"yse_omr_a5_t1_100q_v2.json","yse_omr_a5_t2_60q_v1.json","yse_omr_a5_t3_40q_v1.json"};
  for(int i=0;i<3;++i) {
    const auto tpl=omr::OmrTemplate::fromJson(read("/data/local/tmp/omr-bench/"+files[i]));
    const auto options=omr::OmrTemplate::optionsFromJson("",tpl);
    const std::string payload=std::string(engines[i]->code())+":001234:Ab1Cd2E3";
    auto page=render(tpl,payload);
    const std::string path=root+"/no-qr-"+engines[i]->code()+".png";
    cv::imwrite(path,page);
    auto header=omr::HeaderEngine{}.scan(path,2000);
    ok &= header.valid() && header.sheetType.code==engines[i]->code() && header.studentCode=="001234";
    // The header is now an immutable typed handoff. Remove all QR pixels from
    // this same source before the question module runs: no second QR is possible.
    const auto& r=tpl.examCodeBox;
    cv::rectangle(page,{cvRound(r.x-10),cvRound(r.y-10)},
      {cvRound(r.x+r.width+10),cvRound(r.y+r.height+10)},cv::Scalar(245,245,245),cv::FILLED);
    cv::imwrite(path,page);
    ok &= !omr::HeaderEngine{}.scan(path,2000).valid();
    omr::NormalizeRequest request; request.imagePath=path; request.maxInputDimension=2000;
    request.persistNormalizedImage=false; request.collectQualityMetrics=false;
    const auto answers=engines[i]->scan(request,header,tpl,options);
    const bool correct=exact(answers,engines[i]->capacity());
    std::cout << engines[i]->code() << " answers_after_QR_removed=" << correct << std::endl;
    ok &= correct;
    for(int other=0;other<3;++other) if(other!=i) {
      const auto wrong=engines[other]->scan(request,header,tpl,options);
      ok &= !wrong.success && wrong.answers.questions.empty();
    }
    // A valid-looking credential in the answer area must never select a type,
    // including one positioned to resemble a header after a 180-degree flip.
    for(const bool atBottom:{false,true}) {
      auto bodyQr=page.clone();
      const int side=cvRound(tpl.barcode.roi.width);
      const int x=atBottom ? cvRound(tpl.width*.70f) : cvRound(tpl.width*.45f);
      const int y=atBottom ? cvRound(tpl.height*.80f) : cvRound(tpl.height*.48f);
      putQr(bodyQr,{x,y,side,side},payload);
      const std::string bodyPath=root+"/body-"+engines[i]->code()+(atBottom?"-bottom.png":"-middle.png");
      cv::imwrite(bodyPath,bodyQr);
      const bool ignored=!omr::HeaderEngine{}.scan(bodyPath,2000).valid();
      std::cout << engines[i]->code() << " QR_in_answers_ignored=" << ignored << std::endl;
      ok &= ignored;
    }
    auto original=render(tpl,payload);
    for(const int rotation:{0,90,180,270}) {
      cv::Mat turned;
      if(!rotation) turned=original;
      else cv::rotate(original,turned,rotation==90?cv::ROTATE_90_CLOCKWISE:rotation==180?cv::ROTATE_180:cv::ROTATE_90_COUNTERCLOCKWISE);
      const std::string rotated=root+"/rotation-"+engines[i]->code()+"-"+std::to_string(rotation)+".png";
      cv::imwrite(rotated,turned);
      const auto found=omr::HeaderEngine{}.scan(rotated,2000);
      ok &= found.valid() && found.qr.text==payload;
      const int edge=std::max(turned.rows,turned.cols)+120;
      cv::Mat canvas(edge+120,edge,CV_8UC3,cv::Scalar(245,245,245));
      turned.copyTo(canvas(cv::Rect((canvas.cols-turned.cols)/2,(canvas.rows-turned.rows)/2,turned.cols,turned.rows)));
      const std::string padded=rotated+"-padded.png";
      cv::imwrite(padded,canvas);
      const auto onCanvas=omr::HeaderEngine{}.scan(padded,2000);
      if(!onCanvas.valid() || onCanvas.qr.text!=payload) {
        std::cout << engines[i]->code() << " padded_rotation_failed=" << rotation << std::endl;
        ok=false;
      }
    }
  }
  std::cout << (ok?"PASS":"FAIL") << ": four engines, 200 answers after removing QR, wrong-engine rejection, 6 body QR negatives, 24 header rotations including padded canvases" << std::endl;
  return ok?0:1;
}
