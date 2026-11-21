// On-device timings against the exact packaged source engines; no account/network access.
#include "RoshdEngine.hpp"
#include "DocumentScanner.hpp"
#include "omr/HeaderEngine.hpp"
#include <opencv2/core.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <chrono>
#include <fstream>
#include <iostream>
#include <string>

int main(int argc,char** argv) {
  if(argc!=5)return 2;
  cv::setNumThreads(std::stoi(argv[4]));
  auto begin=std::chrono::steady_clock::now();
  int samples=1;
  if(std::string(argv[1])=="document") {
    const auto report=yse::document::processFile(argv[2],argv[3]);
    std::cout<<report<<'\n';
  } else if(std::string(argv[1])=="frame") {
    auto gray=cv::imread(argv[2],cv::IMREAD_GRAYSCALE);
    const double scale=std::stod(argv[3])/std::max(gray.rows,gray.cols);
    cv::resize(gray,gray,cv::Size(),scale,scale,cv::INTER_AREA);
    yse::document::Detection detected;
    samples=5;begin=std::chrono::steady_clock::now();
    for(int i=0;i<samples;++i)detected=yse::document::detect(gray);
    std::cout<<yse::document::json(detected)<<'\n';
  } else if(std::string(argv[1])=="header") {
    const auto header=omr::HeaderEngine{}.scan(argv[2],2000);
    std::cout<<"header_valid="<<header.valid()<<" header_ms="<<header.processingTimeMs<<'\n';
  } else if(std::string(argv[1])=="omr") {
    std::ifstream input(argv[2]);
    const std::string request{std::istreambuf_iterator<char>(input),{}};
    const char* response=roshd_process_json(request.c_str());
    if(!response)return 3;
    std::ofstream(argv[3])<<response;roshd_free_string(response);
  } else return 2;
  const auto ms=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-begin).count();
  std::cout<<"elapsed_ms="<<ms/samples<<" threads="<<cv::getNumThreads()<<'\n';
}
