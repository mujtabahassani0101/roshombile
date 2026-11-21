#include "DocumentScanner.hpp"
#include <opencv2/imgcodecs.hpp>
#include <iostream>
int main(int argc,char** argv) {
  try {
    cv::setNumThreads(1);
    if(argc==2)std::cout<<yse::document::json(yse::document::detect(cv::imread(argv[1])));
    else if(argc==3)std::cout<<yse::document::processFile(argv[1],argv[2]);
    else {std::cerr<<"document-scanner INPUT_IMAGE [OUTPUT_PNG]\n";return 2;}
    std::cout<<'\n';return 0;
  } catch(const std::exception& error) {std::cerr<<error.what()<<'\n';return 1;}
}
