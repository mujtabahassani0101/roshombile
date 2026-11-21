// Exact-result regression for page-local QR decode reuse. Synthetic fixtures only.
#include "omr/BarcodeReader.hpp"
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <chrono>
#include <future>
#include <iostream>
#include <stdexcept>

bool same(const omr::OmrBarcodeResult& a,const omr::OmrBarcodeResult& b) {
  bool equal=a.found==b.found && a.attempted==b.attempted && a.status==b.status && a.text==b.text &&
    a.format==b.format && a.confidence==b.confidence && a.error==b.error && a.skippedReason==b.skippedReason;
  const omr::Point ap[]{a.quad.topLeft,a.quad.topRight,a.quad.bottomRight,a.quad.bottomLeft};
  const omr::Point bp[]{b.quad.topLeft,b.quad.topRight,b.quad.bottomRight,b.quad.bottomLeft};
  for(int i=0;i<4;++i)equal=equal && ap[i].x==bp[i].x && ap[i].y==bp[i].y;
  return equal;
}
int main(int argc,char** argv) {
  if(argc!=3)return 2;
  cv::setNumThreads(2);
  const auto source=cv::imread(argv[1],cv::IMREAD_GRAYSCALE);
  const auto other=cv::imread(argv[2],cv::IMREAD_GRAYSCALE);
  if(source.empty() || other.empty())return 2;
  int checks=0;
  const auto verify=[&](bool ok) {if(!ok)throw std::runtime_error("QR decode reuse changed a result");++checks;};
  omr::OmrTemplate tpl;
  tpl.barcode.enabled=true;tpl.barcode.required=true;tpl.barcode.qrCodeOnly=true;
  tpl.barcode.preferredFormats={"QR_CODE"};
  tpl.barcode.expectedPayloadRegex="^T[123]:[0-9]{1,32}:(S2[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{18}|[A-Za-z0-9]{8})$";
  const auto decode=[&](const cv::Mat& photo,omr::OmrTemplate layout,bool enabled) {
    layout.barcode.roi={0,0,float(photo.cols),float(cvRound(photo.rows*.235))};
    layout.examCodeBox=layout.barcode.roi;
    omr::BarcodeDecodeCacheScope scope(enabled);
    return omr::BarcodeReader{}.decode(photo,layout);
  };
  const auto expected=decode(source,tpl,false);
  verify(expected.found);
  omr::BarcodeDecodeCacheScope outer;
  auto image=source.clone();
  tpl.barcode.roi={0,0,float(image.cols),float(cvRound(image.rows*.235))};tpl.examCodeBox=tpl.barcode.roi;
  verify(same(expected,omr::BarcodeReader{}.decode(image,tpl)));
  verify(same(expected,omr::BarcodeReader{}.decode(image,tpl)));
  // Same pixels in a non-contiguous view must preserve the result.
  cv::Mat padded(image.rows+8,image.cols+13,CV_8U,cv::Scalar(255));
  image.copyTo(padded(cv::Rect(5,3,image.cols,image.rows)));
  verify(same(expected,omr::BarcodeReader{}.decode(padded(cv::Rect(5,3,image.cols,image.rows)),tpl)));
  // Mutating the SAME allocation must invalidate the former identity.
  image.setTo(255);
  const auto blank=decode(image,tpl,false);
  verify(!blank.found && same(blank,omr::BarcodeReader{}.decode(image,tpl)));
  source.copyTo(image);verify(same(expected,omr::BarcodeReader{}.decode(image,tpl)));
  auto invalid=tpl;invalid.barcode.expectedPayloadRegex="^invalid$";
  const auto rejected=decode(image,invalid,false);
  verify(!rejected.found && same(rejected,omr::BarcodeReader{}.decode(image,invalid)));
  auto format=tpl;format.barcode.preferredFormats={"CODE_128"};
  verify(same(decode(image,format,false),omr::BarcodeReader{}.decode(image,format)));
  // A nested disabled scope and separate threads may not consume outer entries.
  verify(same(expected,decode(image,tpl,false)));
  const auto task=[&](const cv::Mat& input) {
    const auto baseline=decode(input,tpl,false);
    for(int i=0;i<3;++i)if(!same(baseline,decode(input,tpl,true)))return false;
    return true;
  };
  auto a=std::async(std::launch::async,task,source),b=std::async(std::launch::async,task,other);
  verify(a.get() && b.get());
  verify(same(expected,omr::BarcodeReader{}.decode(image,tpl)));
  std::cout<<"Exact QR reuse checks passed: "<<checks<<" (pixels, stride, mutation, regex, format, nested scope and threads)\n";
}
