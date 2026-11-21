#pragma once
#include <string>
namespace roshd {
std::string processJson(const std::string& request);
std::string prepareCameraJson(const std::string& request);
std::string progressJson(const std::string& id);
void releaseProgress(const std::string& id);
}
extern "C" const char* roshd_process_json(const char* request);
extern "C" const char* roshd_prepare_camera_json(const char* request);
extern "C" void roshd_free_string(const char* value);
extern "C" const char* roshd_annotate_json(const char* request);
