#import "LajvardOmrBridge.h"

#include <string>

#include "omr/MobileOmrC.h"

@implementation LajvardOmrBridge

+ (NSInteger)apiVersion {
  return (NSInteger)omr_mobile_api_version();
}

+ (NSString *)engineRevision {
  return [NSString stringWithUTF8String:omr_mobile_engine_revision()] ?: @"";
}

+ (NSString *)processJson:(NSString *)requestJson {
  const char *nativeResult = omr_mobile_process_json(requestJson.UTF8String);
  const std::string result = nativeResult == nullptr ? "" : nativeResult;
  omr_mobile_free_string(nativeResult);
  return [[NSString alloc] initWithBytes:result.data()
                                  length:result.size()
                                encoding:NSUTF8StringEncoding] ?: @"";
}

@end
