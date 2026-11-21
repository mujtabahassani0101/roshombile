#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char** argv) {
  if (argc != 4) return 2;
  void* library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
  if (!library) { fprintf(stderr, "%s\n", dlerror()); return 3; }
  int (*version)(void) = dlsym(library, "omr_mobile_api_version");
  const char* (*revision)(void) = dlsym(library, "omr_mobile_engine_revision");
  const char* (*process)(const char*) = dlsym(library, "omr_mobile_process_json");
  void (*release)(const char*) = dlsym(library, "omr_mobile_free_string");
  if (!version || !revision || !process || !release || version() != 2) return 4;
  /* Retain the baseline for before/after checks against the reported photo. */
  if (strcmp(revision(), "omr-mobile-api-v2-a5-t1-t2-t3-r11") &&
      strcmp(revision(), "omr-mobile-api-v2-a5-t1-t2-t3-r12-dark-rows") &&
      strcmp(revision(), "omr-mobile-api-v2-a5-t1-t2-t3-r13-strict-qr") &&
      strcmp(revision(), "omr-mobile-api-v2-a5-t1-t2-t3-r14-qr-probe-evidence") &&
      strcmp(revision(), "omr-mobile-api-v2-a5-t1-t2-t3-r15-t3-2x20") &&
      strcmp(revision(), "omr-mobile-api-v2-a5-t1-t2-t3-r17-compact-qr") &&
      strcmp(revision(), "omr-mobile-api-v2-a5-t1-t2-t3-r18-ellipse-speed") &&
      strcmp(revision(), "omr-mobile-api-v2-four-engines-r19-header-only") &&
      strcmp(revision(), "omr-mobile-api-v2-four-engines-r20-verified-direction") &&
      strcmp(revision(), "omr-mobile-api-v2-four-engines-r21-parallel-queues") &&
      strcmp(revision(), "omr-mobile-api-v2-four-engines-r22-new-print-rails") &&
      strcmp(revision(), "omr-mobile-api-v2-four-engines-r24-live-progress") &&
      strcmp(revision(), "omr-mobile-api-v2-four-engines-r25-relative-ink") &&
      strcmp(revision(), "omr-mobile-api-v2-four-engines-r26-highest-fill")) return 4;
  printf("Engine revision: %s\n", revision());
  FILE* input = fopen(argv[2], "rb");
  if (!input) return 5;
  fseek(input, 0, SEEK_END);
  long length = ftell(input);
  rewind(input);
  char* request = calloc((size_t)length + 1, 1);
  if (!request || fread(request, 1, (size_t)length, input) != (size_t)length) return 6;
  fclose(input);
  const char* response = process(request);
  free(request);
  if (!response) return 7;
  FILE* output = fopen(argv[3], "wb");
  if (!output) { release(response); return 8; }
  fputs(response, output);
  fclose(output);
  release(response);
  dlclose(library);
  puts("PASS: actual packaged engine loaded through the four exported C ABI functions");
  return 0;
}
