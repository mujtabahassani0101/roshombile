#include <jni.h>

#include <string>

#include "omr/MobileOmrC.h"

namespace {

class UtfChars final {
public:
  UtfChars(JNIEnv* env, jstring value)
    : env_(env), value_(value), chars_(value == nullptr ? nullptr : env->GetStringUTFChars(value, nullptr)) {}

  ~UtfChars() {
    if (chars_ != nullptr) {
      env_->ReleaseStringUTFChars(value_, chars_);
    }
  }

  const char* get() const { return chars_; }

private:
  JNIEnv* env_;
  jstring value_;
  const char* chars_;
};

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_lajvard_omr_OmrNative_apiVersion(JNIEnv*, jclass) {
  return static_cast<jint>(omr_mobile_api_version());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lajvard_omr_OmrNative_engineRevision(JNIEnv* env, jclass) {
  return env->NewStringUTF(omr_mobile_engine_revision());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lajvard_omr_OmrNative_processJson(JNIEnv* env, jclass, jstring requestJson) {
  const UtfChars request(env, requestJson);
  if (env->ExceptionCheck()) {
    return nullptr;
  }

  const char* nativeResult = omr_mobile_process_json(request.get());
  const std::string result = nativeResult == nullptr ? "" : nativeResult;
  omr_mobile_free_string(nativeResult);
  return env->NewStringUTF(result.c_str());
}
