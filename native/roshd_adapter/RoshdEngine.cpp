#include "RoshdEngine.hpp"
#include "InkVerification.hpp"
#include "omr/MobileOmrBatch.hpp"
#include "omr/HeaderEngine.hpp"
#include "omr/BarcodeReader.hpp"
#include "omr/QuestionEngines.hpp"
#include "omr/NormalizeEngine.hpp"
#include "omr/AnswerReader.hpp"
#include "geometry/BubbleGridRefiner.hpp"
#include "omr/QrSheetType.hpp"
#include "omr/MobileOmrC.h"
#include "omr/GradedSheetRenderer.hpp"
#include "utils/Json.hpp"
#include "utils/Timer.hpp"
#include "ReadBarcode.h"
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <fstream>
#include <map>
#include <mutex>
#include <atomic>
#include <chrono>
#include <filesystem>
#include <sstream>
#include <stdexcept>
#include <memory>

// Application-only integration. The unchanged Young Scientist source engine
// owns registration, normalization and each reading. The app also compares
// readings of registered color/contrast representations, requiring review for
// every recovered or conflicting answer.
namespace roshd {
namespace {
using namespace omr;
std::mutex progressMutex;
// Batches are usually one print. The calibration that the complete proof last
// accepted for the same candidate set is tried first. Order never authorizes
// geometry: every candidate keeps the same rail and printed-circle proof, an
// old and a new print of one family cannot both pass it (24/29 versus 20/25
// rail rows; old prints fail the new circle layout), and ties still keep the
// original template index, so a failed read reports the same candidate.
std::mutex preferredLayoutMutex;
std::map<std::string,std::string> preferredLayouts;
std::map<std::string,std::vector<std::pair<int,std::string>>> progressPages;
std::string quote(const std::string& value) {
  std::string out="\"";
  for(unsigned char c:value) {
    if(c=='"'||c=='\\') { out+='\\';out+=char(c); }
    else if(c<32) { const char* h="0123456789abcdef";out+="\\u00";out+=h[c>>4];out+=h[c&15]; }
    else out+=char(c);
  }
  return out+'"';
}
std::string readFile(const std::string& path) {
  std::ifstream in(path);if(!in)throw std::runtime_error("template_unavailable");
  return {std::istreambuf_iterator<char>(in),{}};
}
void strings(std::ostream& out,const std::vector<std::string>& values) {
  out<<'[';for(size_t i=0;i<values.size();++i) { if(i)out<<',';out<<quote(values[i]); }out<<']';
}
// Parsing is a pure function of the template text. Each camera read used to
// re-run the regex-based parser on identical files; keep the parsed value for
// byte-identical text only. Any edited, replaced or new template is parsed.
OmrTemplate parsedTemplate(const std::string& text,bool reuse) {
  if(!reuse)return OmrTemplate::fromJson(text);
  static std::mutex mutex;
  static std::map<std::string,std::shared_ptr<const OmrTemplate>> parsed;
  {
    std::lock_guard lock(mutex);
    const auto found=parsed.find(text);
    if(found!=parsed.end())return *found->second;
  }
  auto value=std::make_shared<const OmrTemplate>(OmrTemplate::fromJson(text));
  std::lock_guard lock(mutex);
  if(parsed.size()>=16)parsed.clear();
  parsed.emplace(text,value);
  return *value;
}
std::vector<OmrTemplate> templates(const std::string& request) {
  const bool reuse=json::boolForKey(request,"reuseParsedTemplates").value_or(true);
  std::vector<OmrTemplate> result;
  for(const auto& path:json::stringArrayForKey(request,"templateJsonPaths"))result.push_back(parsedTemplate(readFile(path),reuse));
  if(result.empty()) {
    if(auto path=json::stringForKey(request,"templateJsonPath"))result.push_back(parsedTemplate(readFile(*path),reuse));
    else if(auto text=json::stringForKey(request,"templateJson"))result.push_back(parsedTemplate(*text,reuse));
  }
  return result;
}
bool roshdLayout(const OmrTemplate& t) {
  return t.geometryId=="t1-a5-20261114" || t.geometryId=="t3-a5-20261114" ||
    t.geometryId=="t1-a5-100q-20261115" || t.geometryId=="t1-a5-75q-20261115" ||
    t.geometryId=="t3-a5-40q-20261115" || t.geometryId=="t1-header-20261020" ||
    t.geometryId=="t1-legacy-header" || t.geometryId=="t3-header-20261020" ||
    t.geometryId=="t3-legacy-header" || t.geometryId=="t3-2x20-2026";
}
const OmrTemplate* geometryLayout(const NormalizeResult& sheet,const std::vector<OmrTemplate>& layouts);
OmrTemplate readingLayout(const OmrTemplate& physical) {
  auto reading=physical;
  if(physical.geometryId=="t1-a5-75q-20261115" && !physical.answers.blocks.empty()) {
    // This print's circles drift by 0.13 pt after each five rows relative to
    // the timing dots. Keep the measured rails for homography; use measured
    // circle rows only for the unchanged source refiner and answer reader.
    reading.registration.rowY=physical.answers.blocks.front().yByRow;
  }
  return reading;
}
void readPhysical75(NormalizeResult& sheet,const OmrTemplate& physical) {
  if(!sheet.success || sheet.normalizedImagePath.empty() || !sheet.hasQuad ||
     sheet.fallbackUsed || sheet.matchedRowsUsed!=25 ||
     sheet.homographyStrategyUsed!="all_25_registration_rows" ||
     !std::isfinite(sheet.reprojectionError) || sheet.reprojectionError>1.5)return;
  Timer timer;
  const auto pixels=cv::imread(sheet.normalizedImagePath,cv::IMREAD_COLOR);
  if(pixels.empty())throw std::runtime_error("normalized_image_unavailable");
  cv::Mat gray;cv::cvtColor(pixels,gray,cv::COLOR_BGR2GRAY);
  const auto reading=readingLayout(physical);
  const auto refinement=BubbleGridRefiner::refine(pixels,gray,reading);
  sheet.answers=AnswerReader::read(pixels,reading,&refinement,75);
  sheet.templateRefinementUsed=refinement.templateRefinementUsed;
  sheet.bubbleRefinementMode=refinement.bubbleRefinementMode;
  sheet.answerBlockRefinementStats=refinement.answerBlockStats;
  sheet.answerRowResiduals=refinement.answerRowResiduals;
  sheet.selectedAnswers.clear();
  for(const auto& q:sheet.answers.questions)if(q.status=="MARKED" && q.hasSelectedOptionNumber)
    sheet.selectedAnswers[q.questionNumber]=q.selectedOptionNumber;
  if(AnswerReader::requiresExposureReview(sheet.answers))sheet.reviewReasons.push_back("answer_exposure_clipped");
  if(sheet.answers.summary.multi>0)sheet.reviewReasons.push_back("answers_multiple_marks");
  if(sheet.answers.summary.ambiguous>0)sheet.reviewReasons.push_back("answers_ambiguous");
  if(std::any_of(sheet.answers.questions.begin(),sheet.answers.questions.end(),[](const auto& q) {return q.status=="MARKED" && q.confidence<.85;}))
    sheet.reviewReasons.push_back("answer_confidence_low");
  sheet.reviewRequired=sheet.reviewRequired || !sheet.reviewReasons.empty();
  sheet.processingTimeMs+=timer.elapsedMs();
}
// Both T1 prints carry 25 identical registration rows. Rails alone cannot
// distinguish the three-column 75Q paper from the four-column 100Q paper.
// Verify the printed white-circle edges, not the answers, on the source
// engine's own homography. Filled centers and central printed digits are not
// sampled. This check never moves a bubble or changes a source OMR decision.
double printedLayoutScore(const NormalizeResult& sheet,const OmrTemplate& layout,
                          const std::string& path,int limit,int active=0,bool verifyCircles=false) {
  if(!sheet.success || !sheet.hasQuad || sheet.fallbackUsed ||
     sheet.matchedRowsUsed!=int(layout.registration.rowY.size()) ||
     !std::isfinite(sheet.reprojectionError) || sheet.reprojectionError>1.5)return 0;
  // T3 also has distinct older prints with smaller circles and four header
  // dots. Rails alone do not prove their answer centers or scoring radius.
  cv::Mat gray;
  if(!sheet.normalizedImagePath.empty())gray=cv::imread(sheet.normalizedImagePath,cv::IMREAD_GRAYSCALE);
  if(gray.empty()) {
    gray=cv::imread(json::stripFileUri(path),cv::IMREAD_GRAYSCALE);
    if(gray.empty())return 0;
    if(limit>0 && std::max(gray.rows,gray.cols)>limit) {
      const double scale=double(limit)/std::max(gray.rows,gray.cols);
      cv::resize(gray,gray,cv::Size(cvRound(gray.cols*scale),cvRound(gray.rows*scale)),0,0,cv::INTER_AREA);
    }
    for(const auto& warning:sheet.warnings) {
      if(warning=="input_auto_rotated_90_clockwise")cv::rotate(gray,gray,cv::ROTATE_90_CLOCKWISE);
      else if(warning=="input_auto_rotated_180")cv::rotate(gray,gray,cv::ROTATE_180);
      else if(warning=="input_auto_rotated_90_counterclockwise")cv::rotate(gray,gray,cv::ROTATE_90_COUNTERCLOCKWISE);
    }
    const cv::Mat matrix(3,3,CV_64F,const_cast<double*>(sheet.homography.data()));
    cv::warpPerspective(gray,gray,matrix,cv::Size(cvRound(layout.width),cvRound(layout.height)),cv::INTER_LINEAR,cv::BORDER_CONSTANT,cv::Scalar(255));
  }
  if(gray.cols!=cvRound(layout.width) || gray.rows!=cvRound(layout.height))return 0;
  const auto sample=[&](double x,double y) {
    const int ix=cvFloor(x),iy=cvFloor(y);
    if(ix<0 || iy<0 || ix>=gray.cols-1 || iy>=gray.rows-1)return 0.;
    const double dx=x-ix,dy=y-iy;
    return gray.at<unsigned char>(iy,ix)*(1-dx)*(1-dy)+gray.at<unsigned char>(iy,ix+1)*dx*(1-dy)+
      gray.at<unsigned char>(iy+1,ix)*(1-dx)*dy+gray.at<unsigned char>(iy+1,ix+1)*dx*dy;
  };
  double minimum=1;
  for(const auto& block:layout.answers.blocks) {
    if(active>0 && block.questionStart>active)continue;
    int circles=0,total=0;
    for(const auto y:block.yByRow)for(const auto& [option,x]:block.xByOption) {
      int edges=0;
      for(int angle=0;angle<24;++angle) {
        const double a=angle*2*CV_PI/24,c=std::cos(a),s=std::sin(a),r=block.bubbleRadius;
        // The white fringe survives ordinary full fills (up to 84% radius).
        const double paper=std::max(sample(x+c*r*.82,y+s*r*.82),sample(x+c*r*.88,y+s*r*.88));
        double boundary=255;
        for(double f:{.96,1.02,1.08,1.14})boundary=std::min(boundary,sample(x+c*r*f,y+s*r*f));
        if(!verifyCircles)for(double f:{.90,1.20})boundary=std::min(boundary,sample(x+c*r*f,y+s*r*f));
        // Exposure scales both paper and printed ink. A relative contrast
        // keeps dim but legible old prints from losing their circle evidence.
        // Thin photocopied rings on white rows can have only 4% contrast.
        // Keep the stricter fast-path proof; the ordinary path also requires
        // every registration row and at least 15 agreeing radial samples.
        if(paper-boundary>=std::max(verifyCircles?7.:4.,paper*(verifyCircles?.10:.04)))++edges;
      }
      if(edges>=15)++circles;
      ++total;
    }
    if(total)minimum=std::min(minimum,double(circles)/total);
  }
  // A camera fast return must prove the internal print as well as its rails.
  // Curvature between straight outer rails can otherwise displace bubbles.
  return verifyCircles && minimum<.90 ? 0 : minimum;
}
int selectedCount(const std::string& request,const std::string& page,const HeaderScanResult& header) {
  const int selected=json::intForKey(page,"activeQuestionCount").value_or(0);
  if(selected)return selected;
  int bound=0;
  for(const auto& item:json::objectArrayForKey(request,"qrBindings")) {
    if(json::stringForKey(item,"qrText").value_or("")!=header.qr.text)continue;
    const int count=json::intForKey(item,"activeQuestionCount").value_or(0);
    if(count && bound && count!=bound)return -1;
    if(count)bound=count;
  }
  return bound;
}
std::string binding(const std::string& request,const std::string& qr) {
  std::string found;
  for(const auto& item:json::objectArrayForKey(request,"qrBindings")) {
    if(json::stringForKey(item,"qrText").value_or("")!=qr)continue;
    const auto family=json::stringForKey(item,"sheetType").value_or("");
    if(family!="T1" && family!="T3")return "";
    if(!found.empty() && found!=family)return "";
    found=family;
  }
  return found;
}
bool decodedHistoricalQr(const OmrBarcodeResult& qr) {
  // The source decoder retains successfully decoded text when its stricter
  // Young Scientist payload regex rejects a historical Roshd identifier.
  // Only an exact verified-roster binding below may authorize that text.
  return !qr.text.empty() && ((qr.found && qr.status=="FOUND") ||
    (qr.status=="INVALID_PAYLOAD" && (qr.error=="barcode_invalid_payload" || qr.error=="qr_code_invalid_payload")));
}
void bindHistoricalHeader(HeaderScanResult& header,const std::string& family) {
  header.sheetType=QrSheetTypeResolver::fromCode(family);
  header.qr.found=true;header.qr.status="FOUND";header.qr.error.clear();header.error.clear();
}
std::optional<bool> qrParity(const cv::Mat& gray,const std::string& text) {
  const auto decode=[&](const cv::Mat& pixels)->std::optional<bool> {
    const ZXing::ImageView image(pixels.data,pixels.cols,pixels.rows,ZXing::ImageFormat::Lum,int(pixels.step));
    const auto options=ZXing::ReaderOptions().setFormats(ZXing::BarcodeFormat::QRCode)
      .setTryHarder(true).setTryRotate(true).setTryInvert(true).setMaxNumberOfSymbols(4);
    for(const auto& code:ZXing::ReadBarcodes(image,options))
      if(code.isValid() && code.text()==text)return code.isMirrored();
    return std::nullopt;
  };
  if(gray.empty())return std::nullopt;
  if(auto parity=decode(gray))return parity;
  cv::Mat enlarged;cv::resize(gray,enlarged,cv::Size(),2,2,cv::INTER_CUBIC);
  if(auto parity=decode(enlarged))return parity;
  cv::Mat enhanced;cv::createCLAHE(2.8,cv::Size(6,6))->apply(gray,enhanced);
  cv::resize(enhanced,enhanced,cv::Size(),3,3,cv::INTER_CUBIC);
  return decode(enhanced);
}
HeaderScanResult calibratedHeader(const std::string& request,const std::string& path,
                                  const std::vector<OmrTemplate>& layouts,bool* needsGeometry=nullptr,bool allowExtended=true,bool* mirroredQr=nullptr) {
  if(needsGeometry)*needsGeometry=false;
  if(mirroredQr)*mirroredQr=false;
  const int limit=json::intForKey(request,"maxInputDimension").value_or(2000);
  if(!json::boolForKey(request,"preferCalibratedHeader").value_or(false))return HeaderEngine{}.scan(path,limit);
  Timer timer;
  // The source header engine's nominal QR box belongs to its older print.
  // Try the supplied Roshd print calibration with that exact source decoder.
  // Expanded candidates remain provisional until source geometry proves that
  // the decoded QR lies above the answer region.
  const auto layout=std::find_if(layouts.begin(),layouts.end(),[](const auto& t) {
    return roshdLayout(t);
  });
  if(layout!=layouts.end()) {
    cv::Mat gray=cv::imread(json::stripFileUri(path),cv::IMREAD_GRAYSCALE);
    if(!gray.empty()) {
      const cv::Mat originalGray=gray;
      if(limit>0 && std::max(gray.rows,gray.cols)>limit) {
        const double scale=double(limit)/std::max(gray.rows,gray.cols);
        cv::resize(gray,gray,cv::Size(),scale,scale,cv::INTER_AREA);
      }
      const std::vector<int> rotations=gray.rows>=gray.cols?std::vector<int>{0,180,90,270}:std::vector<int>{90,270,0,180};
      // A cheap, unscaled pass proposes a tight ROI before expensive image
      // enhancement retries. It supplies no authoritative identity: the source
      // BarcodeReader must decode the same original header pixels again.
      for(const bool fullResolution:{false,true}) {
      if(fullResolution && (!allowExtended || originalGray.size()==gray.size()))continue;
      const auto& source=fullResolution?originalGray:gray;
      for(const double fraction:fullResolution?std::vector<double>{.235,.34,1.0}:
          allowExtended && json::boolForKey(request,"preferGeometryCheckedHeader").value_or(true)?std::vector<double>{.235,.34}:std::vector<double>{.235})
      for(const int rotation:rotations) {
        cv::Mat candidate;
        if(rotation==0)candidate=source;
        else cv::rotate(source,candidate,rotation==90?cv::ROTATE_90_CLOCKWISE:rotation==180?cv::ROTATE_180:cv::ROTATE_90_COUNTERCLOCKWISE);
        if(candidate.cols>candidate.rows*.85 || candidate.cols<=candidate.rows*.55)continue;
        const int bottom=std::max(1,cvRound(candidate.rows*fraction));
        const auto strip=candidate(cv::Rect(0,0,candidate.cols,bottom));
        const ZXing::ImageView pixels(strip.data,strip.cols,strip.rows,ZXing::ImageFormat::Lum,int(strip.step));
        const auto options=ZXing::ReaderOptions().setFormats(ZXing::BarcodeFormat::QRCode)
          .setTryHarder(false).setTryRotate(true).setTryInvert(true).setTryDownscale(false).setMaxNumberOfSymbols(fullResolution?4:1);
        const auto proposals=ZXing::ReadBarcodes(pixels,options);
        if(proposals.size()!=1 || !proposals[0].isValid() || proposals[0].bytes().empty())continue;
        // Full-frame proposals must have the printed QR's upright orientation.
        // Its final position still needs the source engine's all-row homography.
        if(fullResolution && std::abs(std::remainder(double(proposals[0].orientation()),360.))>45)continue;
        std::vector<cv::Point> points;
        for(const auto& p:proposals[0].position())points.emplace_back(p.x,p.y);
        const auto box=cv::boundingRect(points);
        const int margin=std::max(12,cvRound(std::max(box.width,box.height)*.15));
        const auto roi=cv::Rect(box.x-margin,box.y-margin,box.width+margin*2,box.height+margin*2)&cv::Rect(0,0,strip.cols,strip.rows);
        auto header=*layout;header.barcode.fallbackRois.clear();
        header.barcode.roi={float(roi.x),float(roi.y),float(roi.width),float(roi.height)};header.examCodeBox=header.barcode.roi;
        auto qr=BarcodeReader{}.decode(strip,header);
        HeaderScanResult result;result.imagePath=path;result.qr=std::move(qr);
        result.sheetType=QrSheetTypeResolver::resolve(result.qr.text);
        if(result.qr.text!=proposals[0].text() || !(result.valid() || (decodedHistoricalQr(result.qr) && !binding(request,result.qr.text).empty())))continue;
        const double sx=double(rotation%180?gray.rows:gray.cols)/candidate.cols;
        const double sy=double(rotation%180?gray.cols:gray.rows)/candidate.rows;
        if(fullResolution) {
          for(auto* point:{&result.qr.quad.topLeft,&result.qr.quad.topRight,&result.qr.quad.bottomRight,&result.qr.quad.bottomLeft}) {
            point->x*=sx;point->y*=sy;
          }
        }
        result.clockwiseRotation=rotation;result.scannedRegion={0,0,float(strip.cols*sx),float(bottom*sy)};
        const auto second=result.qr.text.find(':',3);
        if(second!=std::string::npos && result.qr.text.size()>3) {
          result.studentCode=result.qr.text.substr(3,second-3);result.identifier=result.qr.text.substr(second+1);
        }
        if(needsGeometry)*needsGeometry=fullResolution || fraction>.235;
        if(mirroredQr)*mirroredQr=proposals[0].isMirrored();
        result.processingTimeMs=timer.elapsedMs();return result;
      }
      }
      if(json::boolForKey(request,"fastHeaderOnly").value_or(false)) {
        HeaderScanResult missing;missing.imagePath=path;
        missing.error="sheet_type_qr_missing_or_invalid";
        missing.processingTimeMs=timer.elapsedMs();return missing;
      }
      for(const int rotation:rotations) {
      cv::Mat candidate;
      if(rotation==0)candidate=gray;
      else cv::rotate(gray,candidate,rotation==90?cv::ROTATE_90_CLOCKWISE:rotation==180?cv::ROTATE_180:cv::ROTATE_90_COUNTERCLOCKWISE);
      // Padded/near-square canvases retain the source header-registration path.
      if(candidate.cols>candidate.rows*.85 || candidate.cols<=candidate.rows*.55)continue;
      const float sx=candidate.cols/layout->width,sy=candidate.rows/layout->height;
      const auto scaled=[&](const Rect& r) { return Rect{r.x*sx,r.y*sy,r.width*sx,r.height*sy}; };
      OmrTemplate header=*layout;
      header.barcode.roi=scaled(layout->barcode.roi);
      header.examCodeBox=scaled(layout->examCodeBox);
      header.barcode.fallbackRois.clear();
      const int bottom=std::max(1,cvRound(candidate.rows*.235));
      // A hand-aligned photograph can shift the QR outside the nominal print
      // box. Decode the same bounded header once, rather than exhaust several
      // clipped boxes before the source engine eventually tries this strip.
      // Identity still comes exclusively from the unchanged source decoder.
      if(json::boolForKey(request,"preferWideHeader").value_or(true)) {
        header.barcode.roi={0,0,float(candidate.cols),float(bottom)};
        header.examCodeBox=header.barcode.roi;
      }
      if(header.examCodeBox.y>=0 && header.examCodeBox.y+header.examCodeBox.height<=bottom &&
         header.examCodeBox.x>=0 && header.examCodeBox.x+header.examCodeBox.width<=candidate.cols) {
        auto qr=BarcodeReader{}.decode(candidate(cv::Rect(0,0,candidate.cols,bottom)),header);
        HeaderScanResult result;result.imagePath=path;result.qr=std::move(qr);
        result.sheetType=QrSheetTypeResolver::resolve(result.qr.text);
        if(result.valid() || (decodedHistoricalQr(result.qr) && !binding(request,result.qr.text).empty())) {
          const auto parity=qrParity(candidate(cv::Rect(0,0,candidate.cols,bottom)),result.qr.text);
          if(!parity)continue;
          if(mirroredQr)*mirroredQr=*parity;
          result.clockwiseRotation=rotation;
          result.scannedRegion={0,0,float(candidate.cols),float(bottom)};
          const auto second=result.qr.text.find(':',3);
          if(second!=std::string::npos && result.qr.text.size()>3) {
            result.studentCode=result.qr.text.substr(3,second-3);result.identifier=result.qr.text.substr(second+1);
          }
          result.processingTimeMs=timer.elapsedMs();return result;
        }
      }
      }
    }
  }
  auto result=HeaderEngine{}.scan(path,limit);
  if(result.valid() || decodedHistoricalQr(result.qr)) {
    cv::Mat gray=cv::imread(json::stripFileUri(path),cv::IMREAD_GRAYSCALE);
    if(!gray.empty()) {
      if(limit>0 && std::max(gray.rows,gray.cols)>limit)cv::resize(gray,gray,cv::Size(),double(limit)/std::max(gray.rows,gray.cols),double(limit)/std::max(gray.rows,gray.cols),cv::INTER_AREA);
      if(result.clockwiseRotation)cv::rotate(gray,gray,result.clockwiseRotation==90?cv::ROTATE_90_CLOCKWISE:result.clockwiseRotation==180?cv::ROTATE_180:cv::ROTATE_90_COUNTERCLOCKWISE);
    }
    const auto parity=qrParity(gray,result.qr.text);
    if(parity) {if(mirroredQr)*mirroredQr=*parity;}
    else {result.qr.found=false;result.qr.status="NOT_FOUND";result.qr.error="qr_image_orientation_unverified";}
  }
  result.processingTimeMs=timer.elapsedMs();return result;
}
int activeCount(const std::string& request,const std::string& page,const NormalizeResult& sheet) {
  const int selected=json::intForKey(page,"activeQuestionCount").value_or(0);
  int bound=0;
  for(const auto& item:json::objectArrayForKey(request,"qrBindings")) {
    if(json::stringForKey(item,"qrText").value_or("")==sheet.barcode.text &&
       json::stringForKey(item,"sheetType").value_or("")==sheet.sheetType.code) {
      const int value=json::intForKey(item,"activeQuestionCount").value_or(0);
      if(value) { if(bound && bound!=value)return -1;bound=value; }
    }
  }
  if(selected && selected!=40 && selected!=75 && selected!=100)return -1;
  if(bound && bound!=75)return -1;
  if(selected && bound && selected!=bound)return -1;
  const int count=selected?selected:bound?bound:sheet.activeQuestionCount==75?75:sheet.sheetType.questionCount;
  if((count==40 && sheet.sheetType.code!="T3") || ((count==75 || count==100) && sheet.sheetType.code!="T1"))return -1;
  return count;
}
// An expanded camera header is provisional until the answer engine's own
// all-row homography proves that the independently decoded QR lies entirely
// above the answer region. A QR in the question blocks cannot pass this check.
bool verifiedHeaderPosition(const NormalizeResult& sheet,const HeaderScanResult& header,const OmrTemplate& tpl) {
  const int rows=int(tpl.registration.rowY.size());
  if(!sheet.success || !sheet.hasQuad || sheet.fallbackUsed || sheet.matchedRowsUsed!=rows ||
     sheet.homographyStrategyUsed!="all_"+std::to_string(rows)+"_registration_rows" ||
     !std::isfinite(sheet.reprojectionError) || sheet.reprojectionError>1.5)return false;
  const cv::Mat matrix(3,3,CV_64F,const_cast<double*>(sheet.homography.data()));
  if(!cv::checkRange(matrix))return false;
  const auto& q=header.qr.quad;
  std::vector<cv::Point2f> points={{q.topLeft.x,q.topLeft.y},{q.topRight.x,q.topRight.y},
    {q.bottomRight.x,q.bottomRight.y},{q.bottomLeft.x,q.bottomLeft.y}},normalized;
  cv::perspectiveTransform(points,normalized,matrix);
  return std::all_of(normalized.begin(),normalized.end(),[&](const auto& p) {
    return std::isfinite(p.x) && std::isfinite(p.y) && p.x>=0 && p.x<=tpl.width && p.y>=0 && p.y<tpl.answersRegion.y;
  });
}
void resolveHistorical(const std::string& request,const std::string& page,
                       const std::vector<OmrTemplate>& layouts,NormalizeResult& sheet) {
  if(sheet.success || !decodedHistoricalQr(sheet.barcode) ||
     QrSheetTypeResolver::resolve(sheet.barcode.text).detected)return;
  const auto family=binding(request,sheet.barcode.text);
  const auto tpl=std::find_if(layouts.begin(),layouts.end(),[&](const auto& t){return t.sheetTypeCode==family;});
  if(family.empty() || tpl==layouts.end())return;
  const auto path=json::stringForKey(page,"imagePath").value_or("");
  auto header=HeaderEngine{}.scan(path,json::intForKey(request,"maxInputDimension").value_or(2000));
  if(!decodedHistoricalQr(header.qr) || header.qr.text!=sheet.barcode.text)return;
  bindHistoricalHeader(header,family);
  NormalizeRequest input;input.imagePath=path;
  input.outputDirectory=json::stringForKey(request,"outputDirectory").value_or("");
  input.persistNormalizedImage=json::boolForKey(request,"returnNormalizedImages").value_or(false);
  input.returnDebugImages=json::boolForKey(request,"returnDebugImages").value_or(false);
  input.maxInputDimension=json::intForKey(request,"maxInputDimension").value_or(2000);
  const auto options=OmrTemplate::optionsFromJson("",*tpl);
  sheet=family=="T1"?T1AnswerEngine{}.scan(input,header,*tpl,options):T3AnswerEngine{}.scan(input,header,*tpl,options);
  sheet.warnings.push_back("roshd_verified_historical_qr_binding");
}
MobileOmrBatchResult readRoshdPrints(const std::string& request,const std::vector<std::string>& pages,
                                   const std::vector<OmrTemplate>& layouts) {
  MobileOmrBatchResult result;
  const auto token=json::stringForKey(request,"progressId").value_or("");
  if(!token.empty()) {
    std::lock_guard lock(progressMutex);auto& states=progressPages[token];states.clear();
    for(size_t i=0;i<pages.size();++i)states.emplace_back(json::intForKey(pages[i],"pageNumber").value_or(int(i+1)),"queued");
  }
  const auto state=[&](size_t index,const char* value) {
    if(token.empty())return;std::lock_guard lock(progressMutex);
    const auto found=progressPages.find(token);if(found!=progressPages.end() && index<found->second.size())found->second[index].second=value;
  };
  for(size_t i=0;i<pages.size();++i) {
    BarcodeDecodeCacheScope barcodeCache(json::boolForKey(request,"reuseBarcodeDecodes").value_or(true));
    omr::AdaptiveThresholdStripsScope thresholdStrips(json::boolForKey(request,"stripAdaptiveThreshold").value_or(true));
    omr::DenseRingSearchScope denseRings(json::boolForKey(request,"denseRingSearch").value_or(true));
    state(i,"header");
    auto path=json::stringForKey(pages[i],"imagePath").value_or("");
    struct TemporaryPhoto {
      std::filesystem::path path;
      ~TemporaryPhoto() {if(!path.empty()) {std::error_code error;std::filesystem::remove(path,error);}}
    } temporary;
    bool needsGeometry=false,mirrored=false,mirrorCorrected=false;
    auto header=calibratedHeader(request,path,layouts,&needsGeometry,true,&mirrored);
    if(mirrored) {
      Timer correctionTimer;const auto identity=header.qr.text;const auto originalMs=header.processingTimeMs;
      try {
        cv::Mat color=cv::imread(json::stripFileUri(path),cv::IMREAD_COLOR),flipped;
        if(color.empty())throw std::runtime_error("unreadable_image");
        cv::flip(color,flipped,1);
        static std::atomic<unsigned long long> serial{0};
        auto directory=json::stringForKey(request,"outputDirectory").value_or("");
        const auto folder=directory.empty()?std::filesystem::temp_directory_path():std::filesystem::path(directory);
        std::filesystem::create_directories(folder);
        const auto stamp=std::chrono::steady_clock::now().time_since_epoch().count();
        temporary.path=folder/(".unmirror-"+std::to_string(stamp)+"-"+std::to_string(++serial)+".bmp");
        if(!cv::imwrite(temporary.path.string(),flipped))throw std::runtime_error("write_failed");
        path=temporary.path.string();
        header=calibratedHeader(request,path,layouts,&needsGeometry,true,&mirrored);
        if(mirrored || header.qr.text!=identity)throw std::runtime_error("identity_changed");
        mirrorCorrected=true;
      } catch(...) {
        header.qr.found=false;header.qr.status="NOT_FOUND";header.qr.error="mirrored_image_correction_failed";
      }
      header.processingTimeMs=originalMs+correctionTimer.elapsedMs();
    }
    NormalizeResult sheet;
    double previousHeaderMs=0,previousAnswerMs=0;
    for(int attempt=0;attempt<2;++attempt) {
    sheet=NormalizeResult{};
    bool historical=false;
    if(!header.valid() && decodedHistoricalQr(header.qr)) {
      const auto family=binding(request,header.qr.text);
      if(!family.empty()) { bindHistoricalHeader(header,family);historical=true; }
    }
    std::vector<const OmrTemplate*> candidates;
    const int selected=selectedCount(request,pages[i],header);
    for(const auto& t:layouts)if(t.sheetTypeCode==header.sheetType.code &&
      (t.answers.questionCount!=75 || selected==75 || selected==0))candidates.push_back(&t);
    if(selected==75)std::stable_sort(candidates.begin(),candidates.end(),[](const auto* a,const auto* b) {
      return a->answers.questionCount==75 && b->answers.questionCount!=75;
    });
    if(historical && json::boolForKey(request,"preferLegacyGeometry").value_or(true)) {
      // Opaque identifiers from the verified roster usually belong to the
      // older print. Try that calibration first; identity never selects or
      // authorizes geometry. Every candidate still needs the SAME complete
      // rail and printed-circle proof, and other layouts remain fallbacks.
      std::stable_sort(candidates.begin(),candidates.end(),[&](const auto* a,const auto* b) {
        const auto priority=[&](const auto* t) {
          if(selected==75 && t->answers.questionCount==75)return 0;
          return t->registration.rowY.size()==size_t(t->answers.questionCount==40?24:29)?1:2;
        };
        return priority(a)<priority(b);
      });
    }
    const bool rememberOrder=json::boolForKey(request,"rememberLayoutOrder").value_or(true) && candidates.size()>1 &&
      std::none_of(candidates.begin(),candidates.end(),[](const auto* t){return t->answers.questionCount==75;});
    std::string orderKey;
    if(rememberOrder) {
      std::vector<std::string> ids;for(const auto* t:candidates)ids.push_back(t->geometryId);
      std::sort(ids.begin(),ids.end());for(const auto& id:ids)orderKey+=id+'|';
      std::string preferred;
      {
        std::lock_guard lock(preferredLayoutMutex);
        const auto found=preferredLayouts.find(orderKey);if(found!=preferredLayouts.end())preferred=found->second;
      }
      if(!preferred.empty())std::stable_partition(candidates.begin(),candidates.end(),[&](const auto* t){return t->geometryId==preferred;});
    }
    const OmrTemplate* layout=nullptr;
    if(header.valid() && !candidates.empty()) {
      state(i,"processing");
      NormalizeRequest input;input.imagePath=path;
      input.outputDirectory=json::stringForKey(request,"outputDirectory").value_or("");
      input.maxInputDimension=json::intForKey(request,"maxInputDimension").value_or(2000);
      input.persistNormalizedImage=json::boolForKey(request,"returnNormalizedImages").value_or(false);
      input.reusePersistedGeometry=json::boolForKey(request,"reuseNormalizationGeometry").value_or(true);
      input.deferProbeWarps=json::boolForKey(request,"deferProbeWarps").value_or(true);
      input.returnDebugImages=json::boolForKey(request,"returnDebugImages").value_or(false);
      input.collectQualityMetrics=false;
      // The new Roshd prints have answer rails but no four-dot header. The
      // source engine's default orientation ranking expects those header dots.
      // Its existing typed option preserves the orientation proved by its
      // own QR header engine, while retaining its complete rail validation.
      input.headerOrientationOnly=true;
      double gridScore=0;
      size_t selectedCandidate=layouts.size();
      for(const auto* candidate:candidates) {
        input.headerOrientationOnly=candidate->registration.rowY.size()==
          size_t(candidate->answers.questionCount==40?20:25);
        const auto options=OmrTemplate::optionsFromJson("",*candidate);
        NormalizeResult reading;
        if(candidate->answers.questionCount==75) {
          // T1 QR identity remains 100-capacity by protocol. The new physical
          // paper has exactly 75 bubbles. Invoke the SAME unchanged source
          // normalizer/reader with its 75Q template; never invent 25 questions
          // or relabel the QR as another family.
          if(header.sheetType.code!="T1" || header.sheetType.questionCount!=100 ||
             header.sheetType.templateId!=candidate->templateId)continue;
          auto answerOnly=*candidate;answerOnly.barcode.enabled=false;answerOnly.barcode.required=false;
          answerOnly.studentCode.enabled=false;
          auto physicalInput=input;physicalInput.header=std::make_shared<const HeaderScanResult>(header);
          physicalInput.persistNormalizedImage=true;
          NormalizeEngine engine;engine.configure(answerOnly,options);reading=engine.normalize(physicalInput);
        } else reading=header.sheetType.code=="T1"?T1AnswerEngine{}.scan(input,header,*candidate,options):T3AnswerEngine{}.scan(input,header,*candidate,options);
        const auto* measured=candidate->answers.questionCount==75?candidate:geometryLayout(reading,layouts);
        const double score=measured?printedLayoutScore(reading,*measured,path,input.maxInputDimension,selected,
          json::boolForKey(request,"requirePrintedCircleGeometry").value_or(false)):0;
        if(score>=.60 && candidate->answers.questionCount==75)readPhysical75(reading,*candidate);
        if(candidate->answers.questionCount==75 && !input.persistNormalizedImage && !reading.normalizedImagePath.empty()) {
          std::error_code error;std::filesystem::remove(reading.normalizedImagePath,error);reading.normalizedImagePath.clear();
        }
        const auto discard=[&](const NormalizeResult& value) {
          if(!value.normalizedImagePath.empty() && value.normalizedImagePath!=path) {
            std::error_code error;std::filesystem::remove(value.normalizedImagePath,error);
          }
        };
        const auto candidateIndex=size_t(candidate-layouts.data());
        if(!layout || score>gridScore || (score==gridScore && candidateIndex<selectedCandidate)) {
          if(sheet.normalizedImagePath!=reading.normalizedImagePath)discard(sheet);
          sheet=std::move(reading);layout=measured?measured:candidate;gridScore=score;selectedCandidate=candidateIndex;
        } else if(sheet.normalizedImagePath!=reading.normalizedImagePath)discard(reading);
        if(gridScore>=.60)break;
      }
      if(rememberOrder && gridScore>=.60) {
        std::lock_guard lock(preferredLayoutMutex);preferredLayouts[orderKey]=layouts[selectedCandidate].geometryId;
      }
      if(sheet.success && gridScore<.60) {
        sheet.success=false;sheet.status="READ_FAILED";sheet.error="printed_answer_layout_unconfirmed";
        sheet.reviewRequired=true;sheet.reviewReasons.push_back(sheet.error);sheet.answers.questions.clear();
      }
      if(sheet.success && layout) {
        sheet.warnings.push_back("roshd_printed_layout_verified:"+layout->geometryId);
        if(json::boolForKey(request,"requirePrintedCircleGeometry").value_or(false))
          sheet.warnings.push_back("roshd_printed_circle_geometry_verified");
        if(layout->answers.questionCount==75)sheet.activeQuestionCount=75;
      }
      sheet.headerEngineRuns=1;sheet.answerEngineRuns=1;sheet.headerProcessingTimeMs=header.processingTimeMs;
      sheet.warnings.push_back("roshd_qr_orientation_for_headerless_print");
      if(mirrorCorrected)sheet.warnings.push_back("roshd_mirrored_image_corrected");
      if(historical)sheet.warnings.push_back("roshd_verified_historical_qr_binding");
      if(needsGeometry && (!layout || !verifiedHeaderPosition(sheet,header,*layout))) {
        previousHeaderMs+=header.processingTimeMs;previousAnswerMs+=sheet.processingTimeMs;
        header=calibratedHeader(request,path,layouts,nullptr,false,&mirrored);needsGeometry=false;
        if(mirrored) {header.qr.found=false;header.qr.status="NOT_FOUND";header.qr.error="qr_image_orientation_changed";}
        continue;
      }
      if(needsGeometry)sheet.warnings.push_back("roshd_header_position_verified_by_source_geometry");
    } else {
      sheet.barcode=header.qr;sheet.sheetType=header.sheetType;sheet.reviewRequired=true;
      sheet.status=header.sheetType.detected?"UNSUPPORTED_SHEET_TYPE":"SHEET_TYPE_REQUIRED";
      sheet.error=header.sheetType.detected?"sheet_template_missing":"sheet_type_qr_missing_or_invalid";
      sheet.reviewReasons.push_back(sheet.error);
    }
    sheet.headerProcessingTimeMs=previousHeaderMs+header.processingTimeMs;
    sheet.processingTimeMs+=previousAnswerMs;
    break;
    }
    state(i,sheet.success?"done":"failed");result.sheets.push_back(std::move(sheet));
  }
  return result;
}
const OmrTemplate* geometryLayout(const NormalizeResult& sheet,const std::vector<OmrTemplate>& layouts) {
  const OmrTemplate* best=nullptr;double bestError=1e30;
  const auto consider=[&](const OmrTemplate& t) {
    if(t.sheetTypeCode!=sheet.sheetType.code)return;
    double error=0;int points=0;
    for(const auto& q:sheet.answers.questions)for(const auto& o:q.options) {
      for(const auto& b:t.answers.blocks) {
        const int row=q.questionNumber-b.questionStart;const auto x=b.xByOption.find(o.option);
        if(row<0 || row>=b.questionCount || size_t(row)>=b.yByRow.size() || x==b.xByOption.end())continue;
        error+=std::hypot(o.expectedX-x->second,o.expectedY-b.yByRow[row]);++points;
      }
    }
    if(points>0 && error/points<bestError) { bestError=error/points;best=&t; }
  };
  for(const auto& t:layouts) { consider(t);for(const auto& v:t.geometryVariants)consider(v); }
  return bestError<1.0?best:nullptr;
}
void writeSheet(std::ostream& out,const NormalizeResult& s,int page,const std::vector<OmrTemplate>& layouts,const InkVerification& verification,bool registrationGeometry) {
  out<<"{\"page\":"<<page<<",\"success\":"<<(s.success?"true":"false")<<",\"status\":"<<quote(s.status)
     <<",\"error\":"<<quote(s.error)<<",\"reviewRequired\":"<<(s.reviewRequired?"true":"false")<<",\"reviewReasons\":";
  strings(out,s.reviewReasons);out<<",\"warnings\":";strings(out,s.warnings);
  out<<",\"normalizedImagePath\":"<<quote(s.normalizedImagePath)<<",\"activeQuestionCount\":"<<s.activeQuestionCount;
  out<<",\"sheetType\":{\"detected\":"<<(s.sheetType.detected?"true":"false")<<",\"code\":"<<quote(s.sheetType.code)
     <<",\"templateId\":"<<quote(s.sheetType.templateId)<<",\"questionCount\":"<<s.sheetType.questionCount<<",\"status\":"<<quote(s.sheetType.status)<<'}';
  const auto barcode=[&] { out<<"{\"found\":"<<(s.barcode.found?"true":"false")<<",\"status\":"<<quote(s.barcode.status)
    <<",\"text\":"<<quote(s.barcode.text)<<",\"format\":"<<quote(s.barcode.format)<<",\"confidence\":"<<s.barcode.confidence<<'}'; };
  out<<",\"qrCode\":";barcode();out<<",\"barcode\":";barcode();
  out<<",\"processingTimeMs\":"<<s.processingTimeMs<<",\"headerProcessingTimeMs\":"<<s.headerProcessingTimeMs<<",\"homographyStrategyUsed\":"<<quote(s.homographyStrategyUsed)
     <<",\"matchedRowsUsed\":"<<s.matchedRowsUsed<<",\"reprojectionError\":"<<s.reprojectionError<<",\"questions\":[";
  bool first=true;
  for(const auto& q:s.answers.questions) {
    if(!first)out<<',';first=false;
    out<<"{\"question\":"<<q.questionNumber<<",\"status\":"<<quote(q.status)<<",\"selectedOption\":";
    if(q.hasSelectedOption)out<<quote(q.selectedOption);else out<<"null";
    out<<",\"selectedOptionNumber\":";if(q.hasSelectedOptionNumber)out<<q.selectedOptionNumber;else out<<"null";
    out<<",\"selectedOptions\":";strings(out,q.selectedOptions);out<<",\"confidence\":"<<q.confidence;
    std::vector<double> darkness;
    for(const auto& option:q.options)darkness.push_back(option.inkDarkness);
    std::sort(darkness.begin(),darkness.end(),std::greater<double>());
    // Preserve the source engine's decision. A faint residual that it returned
    // as blank is review evidence, never permission to invent another answer.
    const bool faintBlank=q.status=="BLANK" && darkness.size()>=2 && darkness[0]>=0.08 && darkness[0]-darkness[1]>=0.035;
    if(faintBlank && verification.questions.find(q.questionNumber)==verification.questions.end())out<<",\"needsReview\":true,\"reviewReason\":\"faint_ink_in_blank_candidate\"";
    if(const auto found=verification.questions.find(q.questionNumber);found!=verification.questions.end()) {
      const auto& evidence=found->second;
      if(evidence.confirmedMultipleOptions.empty())out<<",\"needsReview\":true";
      else {
        out<<",\"verifiedMultipleOptions\":[";
        for(size_t i=0;i<evidence.confirmedMultipleOptions.size();++i) {if(i)out<<',';out<<evidence.confirmedMultipleOptions[i];}
        out<<']';
      }
      out<<",\"reviewReason\":"<<quote(evidence.reason)
         <<",\"sourceOriginalStatus\":"<<quote(evidence.original.status)
         <<",\"sourceOriginalOption\":";
      if(evidence.original.hasSelectedOptionNumber)out<<evidence.original.selectedOptionNumber;else out<<"null";
      out<<",\"verificationPasses\":"<<evidence.agreeingPasses;
    }
    out<<",\"rawFillRatios\":[";
    for(int n=1;n<=4;++n) {
      if(n>1)out<<',';const auto o=std::find_if(q.options.begin(),q.options.end(),[&](const auto& x){return x.hasOptionNumber && x.optionNumber==n;});
      out<<(o==q.options.end()?0:o->fillRatio);
    }out<<"]}";
  }out<<']';
  const auto* layout=geometryLayout(s,layouts);
  if(s.success && layout && !s.normalizedImagePath.empty()) {
    out<<",\"annotationGeometry\":{\"width\":"<<layout->width<<",\"height\":"<<layout->height<<",\"questions\":[";first=true;
    for(const auto& q:s.answers.questions) {
      if(!first)out<<',';first=false;out<<"{\"questionNumber\":"<<q.questionNumber<<",\"options\":[";
      bool firstOption=true;
      for(const auto& o:q.options) {
        const auto block=std::find_if(layout->answers.blocks.begin(),layout->answers.blocks.end(),[&](const auto& b){return q.questionNumber>=b.questionStart && q.questionNumber<b.questionStart+b.questionCount;});
        if(block==layout->answers.blocks.end())continue;
        if(!firstOption)out<<',';firstOption=false;
        out<<"{\"option\":"<<o.optionNumber<<",\"x\":"<<o.refinedX<<",\"y\":"<<o.refinedY<<",\"radius\":"<<block->bubbleRadius<<'}';
      }out<<"]}";
    }out<<"]}";
  }
  if(registrationGeometry && s.success && s.hasQuad) {
    out<<",\"registrationGeometry\":{\"frame\":\"input_after_size_cap_and_orientation\",\"homography\":[";
    const auto precision=out.precision();out.precision(12);
    for(size_t i=0;i<s.homography.size();++i) {if(i)out<<',';out<<s.homography[i];}
    out.precision(precision);out<<"]}";
  }
  out<<",\"inkVerification\":{\"applied\":"<<(verification.applied?"true":"false")
     <<",\"reason\":"<<quote(verification.reason)<<",\"processingTimeMs\":"<<verification.milliseconds
     <<",\"reviewQuestions\":"<<std::count_if(verification.questions.begin(),verification.questions.end(),[](const auto& entry){return entry.second.confirmedMultipleOptions.empty();})<<",\"recoveredQuestions\":"
     <<std::count_if(verification.questions.begin(),verification.questions.end(),[](const auto& entry){return entry.second.recovered;})<<'}';
  out<<'}';
}
}
std::string processJson(const std::string& request) {
  const auto layouts=templates(request);const auto pages=omr::json::objectArrayForKey(request,"pages");
  const bool roshdPrints=!layouts.empty() && !pages.empty() && std::all_of(layouts.begin(),layouts.end(),[](const auto& t) {
    return roshdLayout(t);
  });
  auto result=roshdPrints?readRoshdPrints(request,pages,layouts):omr::MobileOmrBatchProcessor::processJson(request);
  if(result.sheets.empty())return result.json;
  std::ostringstream out;out<<"{\"schemaVersion\":2,\"sourceEngine\":"<<quote(omr_mobile_engine_revision())<<",\"sheets\":[";
  for(size_t i=0;i<result.sheets.size();++i) {
    auto& sheet=result.sheets[i];const auto page=i<pages.size()?pages[i]:"{}";
    if(!roshdPrints)resolveHistorical(request,page,layouts,sheet);
    const int count=activeCount(request,page,sheet);
    if(sheet.success && count<0) {
      sheet.success=false;sheet.status="READ_FAILED";sheet.error="selected_question_count_mismatch";
      sheet.reviewRequired=true;sheet.reviewReasons.push_back(sheet.error);sheet.answers.questions.clear();
    } else if(sheet.success && count>0) {
      sheet.activeQuestionCount=count;
      auto& qs=sheet.answers.questions;qs.erase(std::remove_if(qs.begin(),qs.end(),[&](const auto& q){return q.questionNumber>count;}),qs.end());
    }
    InkVerification verification;verification.reason="disabled";
    if(roshdPrints && json::boolForKey(request,"enableInkVerification").value_or(true)) {
      if(const auto* layout=geometryLayout(sheet,layouts)) {
        try {
          auto verifiedSheet=sheet;
          verification=verifyInk(verifiedSheet,readingLayout(*layout));sheet=std::move(verifiedSheet);
        } catch(...) { verification=InkVerification{};verification.reason="verification_failed"; }
      }
    }
    if(i)out<<',';writeSheet(out,sheet,omr::json::intForKey(page,"pageNumber").value_or(int(i+1)),layouts,verification,
      json::boolForKey(request,"includeRegistrationGeometry").value_or(false));
  }
  out<<"],\"success\":"<<(std::all_of(result.sheets.begin(),result.sheets.end(),[](const auto& s){return s.success;})?"true":"false")<<'}';return out.str();
}
std::string progressJson(const std::string& id) {
  std::lock_guard lock(progressMutex);const auto found=progressPages.find(id);
  if(found==progressPages.end())return omr::MobileOmrBatchProcessor::progressJson(id);
  std::ostringstream out;out<<"{\"pages\":[";
  for(size_t i=0;i<found->second.size();++i) {
    if(i)out<<',';out<<"{\"page\":"<<found->second[i].first<<",\"state\":"<<quote(found->second[i].second)<<'}';
  }out<<"]}";return out.str();
}
void releaseProgress(const std::string& id) {
  { std::lock_guard lock(progressMutex);progressPages.erase(id); }
  omr::MobileOmrBatchProcessor::releaseProgress(id);
}
}
extern "C" const char* roshd_process_json(const char* request) {
  try { const auto value=roshd::processJson(request?request:"");auto* copy=new char[value.size()+1];std::memcpy(copy,value.c_str(),value.size()+1);return copy; }
  catch(...) { const std::string error="{\"success\":false,\"error\":\"roshd_integration_failed\",\"sheets\":[]}";auto* copy=new char[error.size()+1];std::memcpy(copy,error.c_str(),error.size()+1);return copy; }
}
extern "C" void roshd_free_string(const char* value) { delete[] value; }
extern "C" const char* roshd_annotate_json(const char* request) {
  const auto value=omr::renderGradedSheetJson(request?request:"");
  auto* copy=new char[value.size()+1];std::memcpy(copy,value.c_str(),value.size()+1);return copy;
}
