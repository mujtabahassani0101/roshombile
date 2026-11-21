#pragma once

#if defined(_WIN32)
#  if defined(OMR_MOBILE_BUILD_SHARED)
#    define OMR_MOBILE_API __declspec(dllexport)
#  else
#    define OMR_MOBILE_API __declspec(dllimport)
#  endif
#else
#  define OMR_MOBILE_API __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

OMR_MOBILE_API const char* omr_mobile_process_json(const char* request_json);
OMR_MOBILE_API void omr_mobile_free_string(const char* value);
OMR_MOBILE_API int omr_mobile_api_version(void);
OMR_MOBILE_API const char* omr_mobile_engine_revision(void);
// Polling is concurrent-safe. Free snapshots with omr_mobile_free_string.
// Release the opaque token after process_json returns, including on failure.
OMR_MOBILE_API const char* omr_mobile_progress_json(const char* progress_id);
OMR_MOBILE_API void omr_mobile_release_progress(const char* progress_id);

#ifdef __cplusplus
}
#endif
