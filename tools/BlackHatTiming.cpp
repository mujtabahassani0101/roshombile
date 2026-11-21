#include "utils/EllipticalMorphology.hpp"
#include "omr/OmrTemplate.hpp"
#include "utils/Timer.hpp"
#include <opencv2/imgproc.hpp>
#include <opencv2/imgcodecs.hpp>
#include <fstream>
#include <iostream>
namespace omr { cv::Mat ellipticalBlackHatBaseline(const cv::Mat&,int); }
int main(int argc,char** argv) {
 if(argc!=3)return 2;cv::setNumThreads(8);
 std::ifstream in(argv[1]);std::string data{std::istreambuf_iterator<char>(in),{}};auto tpl=omr::OmrTemplate::fromJson(data);
 auto gray=cv::imread(argv[2],cv::IMREAD_GRAYSCALE);double scale=2000./std::max(gray.rows,gray.cols);
 if(scale<1)cv::resize(gray,gray,{},scale,scale,cv::INTER_AREA);
 const float radius=std::max(2.5f,tpl.registration.dotRadius*gray.cols/tpl.width);
 int kernel=std::max(15,int(std::ceil(radius*5)));if(kernel%2==0)++kernel;
 const int width=std::min(gray.cols,std::max(int(std::ceil(gray.cols*.28)),int(std::ceil(radius*12))));
 cv::Mat band;cv::GaussianBlur(gray(cv::Rect(0,0,width,gray.rows)),band,{3,3},0);
 std::cout<<"band="<<band.cols<<"x"<<band.rows<<" kernel="<<kernel<<'\n';
 for(int i=0;i<6;++i) {
  cv::Mat before,after;omr::Timer timer;double oldMs,newMs;
  if(i%2==0){before=omr::ellipticalBlackHatBaseline(band,kernel);oldMs=timer.elapsedMs();timer=omr::Timer{};after=omr::ellipticalBlackHat(band,kernel);newMs=timer.elapsedMs();}
  else{after=omr::ellipticalBlackHat(band,kernel);newMs=timer.elapsedMs();timer=omr::Timer{};before=omr::ellipticalBlackHatBaseline(band,kernel);oldMs=timer.elapsedMs();}
  if(cv::norm(before,after,cv::NORM_INF)!=0)return 1;
  std::cout<<"round="<<i<<" baseline_ms="<<oldMs<<" candidate_ms="<<newMs<<" pixel_exact=true"<<std::endl;
 }
}
