#include "DocumentScanner.hpp"
#include <opencv2/imgproc.hpp>
#include <opencv2/imgcodecs.hpp>
#include <algorithm>
#include <cmath>
#include <filesystem>
#include <iomanip>
#include <locale>
#include <sstream>
#include <stdexcept>
#include <vector>

namespace yse::document {
namespace {
double cross(cv::Point2f a, cv::Point2f b, cv::Point2f c) {
  return (b.x-a.x)*(c.y-b.y)-(b.y-a.y)*(c.x-b.x);
}
double area(const Quad& q) {
  double sum=0;
  for(int i=0;i<4;++i) sum+=q[i].x*q[(i+1)%4].y-q[(i+1)%4].x*q[i].y;
  return sum*.5;
}
Quad ordered(std::vector<cv::Point> points) {
  cv::Point2f center{}; for(auto p:points) center+=cv::Point2f(p)*.25f;
  std::sort(points.begin(),points.end(),[&](auto a,auto b) {
    return std::atan2(a.y-center.y,a.x-center.x)<std::atan2(b.y-center.y,b.x-center.x);
  });
  int top=0;
  for(int i=1;i<4;++i) if(points[i].x+points[i].y<points[top].x+points[top].y) top=i;
  Quad q; for(int i=0;i<4;++i) q[i]=points[(top+i)%4];
  if(area(q)<0) std::swap(q[1],q[3]);
  return q;
}
double edgeEvidence(const Quad& q,const cv::Mat& edges) {
  double total=0,weakest=1;
  for(int side=0;side<4;++side) {
    const auto a=q[side],b=q[(side+1)%4]; int hits=0,count=0;
    const int samples=std::clamp(cvRound(cv::norm(a-b)),32,250);
    for(int i=2;i<samples-2;++i) {
      const auto p=a+(b-a)*(static_cast<float>(i)/samples);
      const int x=std::clamp(cvRound(p.x),0,edges.cols-1),y=std::clamp(cvRound(p.y),0,edges.rows-1);
      hits+=edges.at<unsigned char>(y,x)>0; ++count;
    }
    const double ratio=static_cast<double>(hits)/std::max(1,count);
    total+=ratio; weakest=std::min(weakest,ratio);
  }
  return total/4*.75+weakest*.25;
}
double overlap(const Quad& a,const Quad& b) {
  std::vector<cv::Point2f> intersection;
  const double shared=cv::intersectConvexConvex(std::vector<cv::Point2f>(a.begin(),a.end()),
      std::vector<cv::Point2f>(b.begin(),b.end()),intersection);
  // Containment is not a second document (for example a printed table inside a page).
  return shared/std::max(1.,std::min(area(a),area(b)));
}
double boundaryContrast(const Quad& q,const cv::Mat& gray) {
  double total=0;int supportedSides=0;
  for(int side=0;side<4;++side) {
    const auto a=q[side],b=q[(side+1)%4],delta=b-a;
    const float length=cv::norm(delta);
    const cv::Point2f inward(-delta.y/length,delta.x/length);
    std::vector<float> differences;
    for(int i=4;i<36;++i) {
      const auto p=a+delta*(i/40.f);double inside=0,outside=0;int count=0;
      // Sample beyond a thin printed stroke, not directly on its black pixels.
      for(float offset:{11.f,15.f,19.f}) {
        const auto in=p+inward*offset,out=p-inward*offset;
        if(in.x<0||in.y<0||out.x<0||out.y<0||in.x>=gray.cols||out.x>=gray.cols||in.y>=gray.rows||out.y>=gray.rows)continue;
        inside+=gray.at<uchar>(std::clamp(cvRound(in.y),0,gray.rows-1),std::clamp(cvRound(in.x),0,gray.cols-1));
        outside+=gray.at<uchar>(std::clamp(cvRound(out.y),0,gray.rows-1),std::clamp(cvRound(out.x),0,gray.cols-1));++count;
      }
      if(count)differences.push_back(static_cast<float>((inside-outside)/count));
    }
    if(differences.empty())continue;
    auto middle=differences.begin()+differences.size()/2;
    std::nth_element(differences.begin(),middle,differences.end());
    // A dark printed table surrounded by the same white sheet is not its outer boundary.
    // Automatic capture is conservative for light paper; dark pages remain manually editable.
    const double contrast=*middle;total+=contrast;
    if(contrast>=7)supportedSides++;
  }
  return supportedSides>=3?total/4:0;
}
}

bool validQuad(const Quad& q,double minimumArea) {
  for(auto p:q) if(!std::isfinite(p.x)||!std::isfinite(p.y)||p.x<0||p.y<0||p.x>1||p.y>1) return false;
  if(area(q)<minimumArea) return false;
  for(int i=0;i<4;++i) {
    if(cross(q[i],q[(i+1)%4],q[(i+2)%4])<=.0001) return false;
    if(cv::norm(q[i]-q[(i+1)%4])<.035) return false;
  }
  return true;
}

Detection detect(const cv::Mat& input,int limit) {
  Detection result;
  if(input.empty()||input.depth()!=CV_8U||input.cols<64||input.rows<64||
      (input.channels()!=1&&input.channels()!=3&&input.channels()!=4)) { result.reason="invalid_image"; return result; }
  if(limit<128||limit>1600) throw std::invalid_argument("Invalid detection dimension");
  cv::Mat image,gray;
  const double scale=std::min(1.,static_cast<double>(limit)/std::max(input.cols,input.rows));
  if(scale<1) cv::resize(input,image,cv::Size(),scale,scale,cv::INTER_AREA); else image=input;
  if(image.channels()==1) gray=image; else cv::cvtColor(image,gray,image.channels()==4?cv::COLOR_BGRA2GRAY:cv::COLOR_BGR2GRAY);
  result.width=input.cols;result.height=input.rows;
  cv::Mat smooth,equalized,edges,adaptive,threshold,lightEdges;
  cv::GaussianBlur(gray,smooth,{5,5},0);
  cv::createCLAHE(2.,{8,8})->apply(smooth,equalized);
  cv::Canny(smooth,edges,35,110);
  cv::Canny(equalized,adaptive,45,125);
  cv::bitwise_or(edges,adaptive,edges);
  cv::threshold(smooth,threshold,0,255,cv::THRESH_BINARY|cv::THRESH_OTSU);
  cv::Canny(threshold,lightEdges,60,150);
  cv::bitwise_or(edges,lightEdges,edges);
  cv::Mat support;
  cv::dilate(edges,support,cv::getStructuringElement(cv::MORPH_ELLIPSE,{7,7}));
  cv::Mat closed;
  cv::morphologyEx(edges,closed,cv::MORPH_CLOSE,cv::getStructuringElement(cv::MORPH_RECT,{5,5}));
  std::vector<cv::Mat> masks{closed,threshold};
  if(image.channels()!=1) {
    cv::Mat bgr,hsv,white;
    if(image.channels()==4) cv::cvtColor(image,bgr,cv::COLOR_BGRA2BGR); else bgr=image;
    cv::cvtColor(bgr,hsv,cv::COLOR_BGR2HSV);
    cv::inRange(hsv,cv::Scalar(0,0,95),cv::Scalar(180,100,255),white);
    cv::morphologyEx(white,white,cv::MORPH_CLOSE,cv::getStructuringElement(cv::MORPH_RECT,{9,9}));
    masks.push_back(white);
  }
  struct Candidate { Quad pixels; double score,area,support; };
  std::vector<Candidate> candidates;
  const double imageArea=gray.total();
  for(const auto& mask:masks) {
    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(mask,contours,cv::RETR_LIST,cv::CHAIN_APPROX_SIMPLE);
    // Compute each area once; sorting thousands of text contours repeatedly was expensive.
    std::vector<std::pair<double,size_t>> eligible;
    for(size_t i=0;i<contours.size();++i) {
      const double size=std::abs(cv::contourArea(contours[i]));
      if(size>=imageArea*.065)eligible.emplace_back(size,i);
    }
    std::sort(eligible.begin(),eligible.end(),[](auto a,auto b){return a.first>b.first;});
    if(eligible.size()>60)eligible.resize(60);
    for(auto [size,index]:eligible) {
      const auto& contour=contours[index];
      std::vector<cv::Point> hull;cv::convexHull(contour,hull);
      for(double epsilon:{.009,.016,.025}) {
        std::vector<cv::Point> polygon;
        cv::approxPolyDP(hull,polygon,cv::arcLength(hull,true)*epsilon,true);
        if(polygon.size()!=4||!cv::isContourConvex(polygon)) continue;
        auto q=ordered(polygon);Quad norm;
        for(int i=0;i<4;++i) norm[i]={q[i].x/(gray.cols-1),q[i].y/(gray.rows-1)};
        const double fraction=area(norm);
        if(!validQuad(norm,.07)||fraction>.965) continue;
        bool clipped=false;
        for(auto p:q) if(p.x<3||p.y<3||p.x>gray.cols-4||p.y>gray.rows-4) clipped=true;
        if(clipped) continue;
        double minEdge=1e9,maxEdge=0,minSine=1;
        for(int i=0;i<4;++i) {
          const auto a=q[(i+3)%4]-q[i],b=q[(i+1)%4]-q[i];
          const double length=cv::norm(b);minEdge=std::min(minEdge,length);maxEdge=std::max(maxEdge,length);
          const double cosine=std::abs(a.dot(b))/std::max(1.,cv::norm(a)*cv::norm(b));
          minSine=std::min(minSine,std::sqrt(std::max(0.,1-cosine*cosine)));
        }
        if(minEdge< std::min(gray.cols,gray.rows)*.14||maxEdge/minEdge>3.3||minSine<.48) continue;
        const double evidence=edgeEvidence(q,support);
        if(evidence<.60) continue;
        if(boundaryContrast(q,smooth)<7)continue;
        bool duplicate=false;
        for(const auto& prior:candidates) {
          double distance=0;for(int i=0;i<4;++i)distance+=cv::norm(q[i]-prior.pixels[i]);
          if(distance<12) { duplicate=true;break; }
        }
        if(duplicate)break;
        // A real outer paper boundary should contain a substantial neutral/light region.
        cv::Mat interior=cv::Mat::zeros(gray.size(),CV_8U);
        std::vector<cv::Point> qi;for(auto p:q) qi.emplace_back(cvRound(p.x),cvRound(p.y));
        cv::fillConvexPoly(interior,qi,255);
        const double light=cv::mean(gray,interior)[0]/255.;
        if(light<.20) continue;
        const double score=.44*evidence+.34*std::sqrt(fraction)+.12*minSine+.10*std::min(1.,light/.65);
        candidates.push_back({q,score,fraction,evidence});
        break;
      }
    }
  }
  if(candidates.empty()) return result;
  std::sort(candidates.begin(),candidates.end(),[](auto& a,auto& b){return a.score>b.score;});
  const auto& best=candidates.front();
  for(size_t i=1;i<candidates.size();++i) {
    if(candidates[i].area>best.area*.25&&candidates[i].score>.70&&overlap(best.pixels,candidates[i].pixels)<.20) {
      result.reason="multiple_documents";return result;
    }
  }
  if(best.area<.18) { result.reason="document_too_small";return result; }
  if(best.score<.70) { result.reason="uncertain_edges";return result; }
  auto refined=best.pixels;
  std::vector<cv::Point2f> corners(refined.begin(),refined.end());
  cv::cornerSubPix(gray,corners,{4,4},{-1,-1},{cv::TermCriteria::COUNT|cv::TermCriteria::EPS,15,.05});
  for(int i=0;i<4;++i) if(cv::norm(corners[i]-refined[i])<5) refined[i]=corners[i];
  for(int i=0;i<4;++i) result.corners[i]={refined[i].x/(gray.cols-1),refined[i].y/(gray.rows-1)};
  if(!validQuad(result.corners,.18)) return result;
  cv::Mat interior=cv::Mat::zeros(gray.size(),CV_8U);std::vector<cv::Point> qi;
  for(auto p:refined) qi.emplace_back(cvRound(p.x),cvRound(p.y));
  cv::fillConvexPoly(interior,qi,255);
  cv::erode(interior,interior,cv::getStructuringElement(cv::MORPH_RECT,{9,9}));
  result.brightness=cv::mean(gray,interior)[0];
  cv::Mat laplacian;cv::Laplacian(gray,laplacian,CV_32F);
  cv::Scalar mean,deviation;cv::meanStdDev(laplacian,mean,deviation,interior);result.sharpness=deviation[0]*deviation[0];
  // Clipped uniform highlights are reported, not interpreted as an accuracy percentage.
  cv::Mat clipped;cv::inRange(gray,cv::Scalar(254),cv::Scalar(255),clipped);cv::bitwise_and(clipped,interior,clipped);
  result.glare=static_cast<double>(cv::countNonZero(clipped))/std::max(1,cv::countNonZero(interior));
  result.found=true;result.confidence=best.score;result.area=best.area;result.edgeSupport=best.support;result.reason="detected";
  return result;
}

cv::Mat rectify(const cv::Mat& input,const Quad& corners,int maxDimension,int turns,double padding) {
  if(input.empty()||!validQuad(corners)||maxDimension<128||maxDimension>3200||turns<0||turns>3||padding<0||padding>.04)
    throw std::invalid_argument("Invalid document crop");
  Quad q;cv::Point2f center{};for(auto p:corners)center+=p*.25f;
  for(int i=0;i<4;++i) {
    auto p=center+(corners[i]-center)*static_cast<float>(1+padding);
    q[i]={std::clamp(p.x,0.f,1.f)*(input.cols-1),std::clamp(p.y,0.f,1.f)*(input.rows-1)};
  }
  const double width=std::max(cv::norm(q[1]-q[0]),cv::norm(q[2]-q[3]));
  const double height=std::max(cv::norm(q[3]-q[0]),cv::norm(q[2]-q[1]));
  if(width<32||height<32) throw std::invalid_argument("Document crop is too small");
  const double scale=std::min(1.,maxDimension/std::max(width,height));
  const int w=std::max(32,cvRound(width*scale)),h=std::max(32,cvRound(height*scale));
  Quad target{{{0,0},{static_cast<float>(w-1),0},{static_cast<float>(w-1),static_cast<float>(h-1)},{0,static_cast<float>(h-1)}}};
  cv::Mat output;
  cv::warpPerspective(input,output,cv::getPerspectiveTransform(q.data(),target.data()),{w,h},cv::INTER_LINEAR,cv::BORDER_REPLICATE);
  if(turns) cv::rotate(output,output,turns==1?cv::ROTATE_90_CLOCKWISE:turns==2?cv::ROTATE_180:cv::ROTATE_90_COUNTERCLOCKWISE);
  return output;
}

std::string json(const Detection& r) {
  std::ostringstream s;s.imbue(std::locale::classic());s<<std::fixed<<std::setprecision(6);
  s<<"{\"engine\":\"yse-document-scanner-v2\",\"found\":"<<(r.found?"true":"false")
   <<",\"reason\":\""<<r.reason<<"\",\"width\":"<<r.width<<",\"height\":"<<r.height
   <<",\"confidence\":"<<r.confidence<<",\"area\":"<<r.area<<",\"edgeSupport\":"<<r.edgeSupport
   <<",\"brightness\":"<<r.brightness<<",\"sharpness\":"<<r.sharpness<<",\"clippedHighlights\":"<<r.glare<<",\"corners\":[";
  if(r.found) for(int i=0;i<4;++i) s<<(i?",":"")<<r.corners[i].x<<","<<r.corners[i].y;
  s<<"]}";return s.str();
}

std::string processFile(const std::string& input,const std::string& output,const Quad* manual,int maxDimension,int turns) {
  if(input.empty()||output.empty()||std::filesystem::absolute(input).lexically_normal()==std::filesystem::absolute(output).lexically_normal())
    throw std::invalid_argument("Original image must not be overwritten");
  const auto source=cv::imread(input,cv::IMREAD_COLOR);
  if(source.empty()||static_cast<int64_t>(source.total())>24000000) throw std::invalid_argument("Invalid or oversized image");
  Detection detection;
  if(manual) { detection.width=source.cols;detection.height=source.rows;detection.reason="manual_crop"; }
  else detection=detect(source);
  const bool applied=manual!=nullptr||detection.found;
  if(applied) {
    const auto rendered=rectify(source,manual?*manual:detection.corners,maxDimension,turns,manual?0:.012);
    const std::filesystem::path destination(output),partial(output+".partial.png");
    if(!destination.parent_path().empty()) std::filesystem::create_directories(destination.parent_path());
    try {
      if(!cv::imwrite(partial.string(),rendered,{cv::IMWRITE_PNG_COMPRESSION,3})) throw std::runtime_error("Image encoding failed");
      std::filesystem::rename(partial,destination);
    } catch(...) { std::error_code error;std::filesystem::remove(partial,error);throw; }
  }
  return std::string("{\"applied\":")+(applied?"true":"false")+",\"manual\":"+(manual?"true":"false")+",\"detection\":"+json(detection)+"}";
}
}
