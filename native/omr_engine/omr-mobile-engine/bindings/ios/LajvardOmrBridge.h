#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface LajvardOmrBridge : NSObject

+ (NSInteger)apiVersion;
+ (NSString *)engineRevision;
+ (NSString *)processJson:(NSString *)requestJson;

@end

NS_ASSUME_NONNULL_END
