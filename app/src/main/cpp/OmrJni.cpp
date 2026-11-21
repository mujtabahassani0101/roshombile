#include <jni.h>
#include "omr/MobileOmrC.h"
#include "RoshdEngine.hpp"
#include "omr/GradedSheetRenderer.hpp"

extern "C" JNIEXPORT jstring JNICALL
Java_com_haseebullahjan_roshd_platform_OmrNative_prepareCameraJson(JNIEnv* env, jobject, jstring input) {
    if (!input) return nullptr;
    const char* request=env->GetStringUTFChars(input,nullptr);
    if (!request) return nullptr;
    const auto output=roshd::prepareCameraJson(request);
    env->ReleaseStringUTFChars(input,request);
    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_haseebullahjan_roshd_platform_OmrNative_processJson(JNIEnv* env, jobject, jstring input) {
    if (!input) return nullptr;
    const char* request = env->GetStringUTFChars(input, nullptr);
    if (!request) return nullptr;
    const char* output = roshd_process_json(request);
    env->ReleaseStringUTFChars(input, request);
    const jstring result = output ? env->NewStringUTF(output) : nullptr;
    if (output) roshd_free_string(output);
    return result;
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_haseebullahjan_roshd_platform_OmrNative_engineRevision(JNIEnv* env, jobject) {
    return env->NewStringUTF((std::string("roshd-source-port-v1+")+omr_mobile_engine_revision()).c_str());
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_haseebullahjan_roshd_platform_OmrNative_progress(JNIEnv* env, jobject, jstring input) {
    const char* id = env->GetStringUTFChars(input, nullptr);
    if (!id) return nullptr;
    const auto output = roshd::progressJson(id);
    env->ReleaseStringUTFChars(input, id);
    return env->NewStringUTF(output.c_str());
}
extern "C" JNIEXPORT void JNICALL
Java_com_haseebullahjan_roshd_platform_OmrNative_releaseProgress(JNIEnv* env, jobject, jstring input) {
    const char* id = env->GetStringUTFChars(input, nullptr);
    if (id) { roshd::releaseProgress(id); env->ReleaseStringUTFChars(input, id); }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_haseebullahjan_roshd_platform_OmrNative_annotateJson(JNIEnv* env, jobject, jstring input) {
    if (!input) return nullptr;
    const char* request = env->GetStringUTFChars(input, nullptr);
    if (!request) return nullptr;
    const auto output = omr::renderGradedSheetJson(request);
    env->ReleaseStringUTFChars(input, request);
    return env->NewStringUTF(output.c_str());
}
