#include "omr/GradedSheetRenderer.hpp"
#include "utils/Json.hpp"
#include <atomic>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <set>
#include <sstream>
#include <stdexcept>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

namespace omr {
namespace {
std::atomic<unsigned long> serial{0};
void require(bool condition) { if(!condition)throw std::invalid_argument("invalid_annotation"); }
std::string quote(const std::string& value) {
  std::string out="\"";
  for(unsigned char ch:value) {
    if(ch=='"' || ch=='\\') { out+='\\';out+=char(ch); }
    else if(ch<32) { const char* hex="0123456789abcdef";out+="\\u00";out+=hex[ch>>4];out+=hex[ch&15]; }
    else out+=char(ch);
  }
  return out+'"';
}
enum class MarkKind { Correct,Incorrect,BlankCorrect,MissedCorrect };
struct Mark { int question,option;double x,y,radius;MarkKind kind; };
cv::Rect markBox(const Mark& mark) {
  const int ring=cvRound(mark.radius*1.25),stroke=std::max(2,cvRound(mark.radius*.17));
  const cv::Point center(cvRound(mark.x),cvRound(mark.y));
  return {center.x-ring-stroke,center.y-ring-stroke,2*(ring+stroke)+1,2*(ring+stroke)+1};
}
/** The app draws the score text (Persian shaping needs the platform font engine)
 *  as premultiplied RGBA; it is placed below the answer grid, never over a bubble. */
bool blendFooter(cv::Mat& image,const std::string& request,const std::vector<Mark>& marks) {
  const auto footer=json::topLevelObjectForKey(request,"footer");
  if(!footer)return false;
  const std::string path=json::stringForKey(*footer,"path").value_or("");
  // Distinct key names: request-level width/height lookups are not top-level only.
  const int x=json::intForKey(*footer,"left").value_or(-1),y=json::intForKey(*footer,"top").value_or(-1);
  const int w=json::intForKey(*footer,"pixelWidth").value_or(0),h=json::intForKey(*footer,"pixelHeight").value_or(0);
  require(!path.empty() && x>=0 && y>=0 && w>=50 && h>=20 && x+w<=image.cols && y+h<=image.rows);
  const cv::Rect area(x,y,w,h);
  for(const auto& mark:marks)require((area&markBox(mark)).empty());
  std::ifstream input(path,std::ios::binary);
  require(input.good());
  cv::Mat rgba(h,w,CV_8UC4);
  input.read(reinterpret_cast<char*>(rgba.data),static_cast<std::streamsize>(rgba.total()*4));
  require(input.gcount()==static_cast<std::streamsize>(rgba.total()*4) && input.peek()==std::char_traits<char>::eof());
  for(int row=0;row<h;++row) {
    const auto* src=rgba.ptr<cv::Vec4b>(row);auto* dst=image.ptr<cv::Vec3b>(y+row)+x;
    for(int col=0;col<w;++col) {
      const int alpha=src[col][3];
      if(alpha==0)continue;
      for(int channel=0;channel<3;++channel) {
        // RGBA source, BGR image; the source is already multiplied by alpha.
        const int value=src[col][2-channel]+(dst[col][channel]*(255-alpha)+127)/255;
        dst[col][channel]=static_cast<uchar>(std::min(255,value));
      }
    }
  }
  return true;
}
}

std::string renderGradedSheetJson(const std::string& request) {
  namespace fs=std::filesystem;
  fs::path temporary;
  try {
    const fs::path input=json::topLevelStringForKey(request,"imagePath").value_or("");
    const fs::path output=json::topLevelStringForKey(request,"outputPath").value_or("");
    const int width=json::intForKey(request,"width").value_or(0),height=json::intForKey(request,"height").value_or(0);
    const int count=json::intForKey(request,"questionCount").value_or(0);
    require(!input.empty() && !output.empty() && fs::is_regular_file(input) && !fs::exists(output));
    require(fs::weakly_canonical(input)!=fs::weakly_canonical(output));
    require(width>=100 && height>=100 && static_cast<long long>(width)*height<=24'000'000 && count>=1 && count<=100);
    std::vector<Mark> marks;
    std::set<std::pair<int,int>> seen;
    const auto objects=json::objectArrayForKey(request,"marks");
    require(objects.size()<=400);
    for(const auto& object:objects) {
      const int q=json::intForKey(object,"questionNumber").value_or(0),option=json::intForKey(object,"option").value_or(0);
      const auto x=json::numberForKey(object,"x"),y=json::numberForKey(object,"y"),r=json::numberForKey(object,"radius");
      const auto verdict=json::stringForKey(object,"verdict").value_or("");
      require(q>=1 && q<=count && option>=1 && option<=4 && seen.emplace(q,option).second);
      require(x && y && r && std::isfinite(*x) && std::isfinite(*y) && std::isfinite(*r));
      require(*r>=2 && *r<=100 && *x-*r*1.5>=0 && *x+*r*1.5<width && *y-*r*1.5>=0 && *y+*r*1.5<height);
      require(verdict=="correct" || verdict=="incorrect" || verdict=="blank_correct" || verdict=="missed_correct");
      marks.push_back({q,option,*x,*y,*r,verdict=="correct"?MarkKind::Correct:verdict=="incorrect"?MarkKind::Incorrect:
        verdict=="missed_correct"?MarkKind::MissedCorrect:MarkKind::BlankCorrect});
    }
    auto image=cv::imread(input.string(),cv::IMREAD_COLOR);
    require(!image.empty() && image.cols==width && image.rows==height);
    int green=0,red=0,yellow=0,missed=0;
    for(const auto& mark:marks) {
      // Green also shows the key's option on a wrong answer (ring without a tick).
      const cv::Scalar color=mark.kind==MarkKind::Correct || mark.kind==MarkKind::MissedCorrect?cv::Scalar(90,160,22):
        mark.kind==MarkKind::Incorrect?cv::Scalar(62,52,227):cv::Scalar(0,195,245);
      const cv::Point center(cvRound(mark.x),cvRound(mark.y));
      const int radius=cvRound(mark.radius),ring=cvRound(mark.radius*1.25),stroke=std::max(2,cvRound(mark.radius*.17));
      const cv::Rect box=markBox(mark);
      require(box.x>=0 && box.y>=0 && box.br().x<=width && box.br().y<=height);
      auto roi=image(box);auto tint=roi.clone();
      cv::circle(tint,center-box.tl(),radius,color,cv::FILLED,cv::LINE_AA);
      cv::addWeighted(tint,.25,roi,.75,0,roi);
      cv::circle(image,center,ring,color,stroke,cv::LINE_AA);
      const int arm=std::max(3,cvRound(mark.radius*.55));
      if(mark.kind==MarkKind::Correct) {
        cv::line(image,center+cv::Point(-arm,0),center+cv::Point(-arm/4,arm/2),color,stroke,cv::LINE_AA);
        cv::line(image,center+cv::Point(-arm/4,arm/2),center+cv::Point(arm,-arm/2),color,stroke,cv::LINE_AA);++green;
      } else if(mark.kind==MarkKind::Incorrect) {
        cv::line(image,center+cv::Point(-arm,-arm),center+cv::Point(arm,arm),color,stroke,cv::LINE_AA);
        cv::line(image,center+cv::Point(-arm,arm),center+cv::Point(arm,-arm),color,stroke,cv::LINE_AA);++red;
      } else if(mark.kind==MarkKind::MissedCorrect) ++missed;
      else ++yellow;
    }
    const bool footer=blendFooter(image,request,marks);
    fs::create_directories(output.parent_path());
    temporary=output.parent_path()/(output.stem().string()+"-partial-"+std::to_string(++serial)+".jpg");
    require(cv::imwrite(temporary.string(),image,{cv::IMWRITE_JPEG_QUALITY,95}));
    fs::rename(temporary,output);temporary.clear();
    std::ostringstream result;result<<"{\"success\":true,\"outputPath\":"<<quote(output.string())
      <<",\"width\":"<<width<<",\"height\":"<<height<<",\"correctMarks\":"<<green<<",\"incorrectMarks\":"<<red<<",\"blankCorrectMarks\":"<<yellow
      <<",\"missedCorrectMarks\":"<<missed<<",\"footer\":"<<(footer?"true":"false")<<'}';
    return result.str();
  } catch(...) {
    if(!temporary.empty()) { std::error_code ignored;fs::remove(temporary,ignored); }
    return "{\"success\":false,\"error\":\"annotation_failed\"}";
  }
}
}
