#include "DocumentScanner.hpp"
#include <jni.h>
#include <opencv2/imgproc.hpp>
#include <opencv2/imgcodecs.hpp>
#include <stdexcept>
#include <cstdint>
#include <vector>
namespace {
std::string string(JNIEnv* env,jstring value) {
  if(!value) throw std::invalid_argument("Missing image path");
  const char* text=env->GetStringUTFChars(value,nullptr);
  if(!text) throw std::runtime_error("Image path unavailable");
  std::string result(text);env->ReleaseStringUTFChars(value,text);return result;
}
jstring failure(JNIEnv* env,const std::exception& error) {
  env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),error.what());return nullptr;
}
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_haseebullahjan_youngscientist_scanner_DocumentScannerNative_detectBuffer(JNIEnv* env,jobject,jobject buffer,jint offset,jint limit,jint stride,jint left,jint top,jint width,jint height,jint rotation) {
  try {
    auto* address=buffer?static_cast<unsigned char*>(env->GetDirectBufferAddress(buffer)):nullptr;
    const auto capacity=buffer?env->GetDirectBufferCapacity(buffer):-1;
    const int64_t end=static_cast<int64_t>(offset)+(static_cast<int64_t>(top)+height-1)*stride+left+width;
    if(!address||offset<0||limit>capacity||limit<offset||left<0||top<0||width<64||height<64||
        width>8192||height>8192||static_cast<int64_t>(width)*height>24000000||
        stride<static_cast<int64_t>(left)+width||end>limit||
        (rotation!=0&&rotation!=90&&rotation!=180&&rotation!=270))throw std::invalid_argument("Invalid direct camera plane");
    // The ImageProxy remains owned by the caller until this synchronous method returns.
    cv::Mat source(height,width,CV_8U,address+offset+static_cast<int64_t>(top)*stride+left,stride),bounded;
    const double scale=std::min(1.,960./std::max(width,height));
    if(scale<1)cv::resize(source,bounded,cv::Size(),scale,scale,cv::INTER_AREA);else bounded=source;
    cv::Mat oriented;
    if(rotation)cv::rotate(bounded,oriented,rotation==90?cv::ROTATE_90_CLOCKWISE:rotation==180?cv::ROTATE_180:cv::ROTATE_90_COUNTERCLOCKWISE);
    else oriented=bounded;
    auto result=yse::document::detect(oriented);
    result.width=rotation%180?height:width;result.height=rotation%180?width:height;
    return env->NewStringUTF(yse::document::json(result).c_str());
  } catch(const std::exception& error){return failure(env,error);}
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_haseebullahjan_youngscientist_scanner_DocumentScannerNative_detectLuma(JNIEnv* env,jobject,jbyteArray data,jint width,jint height,jint rotation) {
  try {
    if(!data||width<64||height<64||width>1600||height>1600||env->GetArrayLength(data)!=width*height||
        (rotation!=0&&rotation!=90&&rotation!=180&&rotation!=270)) throw std::invalid_argument("Invalid camera frame");
    cv::Mat gray(height,width,CV_8U);env->GetByteArrayRegion(data,0,width*height,reinterpret_cast<jbyte*>(gray.data));
    if(env->ExceptionCheck()) return nullptr;
    if(rotation)cv::rotate(gray,gray,rotation==90?cv::ROTATE_90_CLOCKWISE:rotation==180?cv::ROTATE_180:cv::ROTATE_90_COUNTERCLOCKWISE);
    return env->NewStringUTF(yse::document::json(yse::document::detect(gray)).c_str());
  } catch(const std::exception& error){return failure(env,error);}
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_haseebullahjan_youngscientist_scanner_DocumentScannerNative_inspectFile(JNIEnv* env,jobject,jstring path) {
  try { return env->NewStringUTF(yse::document::json(yse::document::detect(cv::imread(string(env,path)))).c_str()); }
  catch(const std::exception& error){return failure(env,error);}
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_haseebullahjan_youngscientist_scanner_DocumentScannerNative_rectifyFile(JNIEnv* env,jobject,jstring input,jstring output,jdoubleArray points,jint limit,jint turns) {
  try {
    yse::document::Quad quad;bool manual=points!=nullptr;
    if(manual) {
      if(env->GetArrayLength(points)!=8)throw std::invalid_argument("Four document corners required");
      jdouble values[8];env->GetDoubleArrayRegion(points,0,8,values);if(env->ExceptionCheck())return nullptr;
      for(int i=0;i<4;++i)quad[i]={static_cast<float>(values[i*2]),static_cast<float>(values[i*2+1])};
    }
    return env->NewStringUTF(yse::document::processFile(string(env,input),string(env,output),manual?&quad:nullptr,limit,turns).c_str());
  } catch(const std::exception& error){return failure(env,error);}
}
