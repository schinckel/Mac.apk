// Compile this harness together with the production WebView bridge. No host window required.
#import <AppKit/AppKit.h>
#include <jni.h>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <thread>
#include "../runtime/src/webview_bridge.mm"

extern "C" void* CocoaWebViewHostView(void) { return nullptr; }
namespace macapk {
void* CocoaGetPlaceholderWindow() { return nullptr; }
namespace webview { void RegisterWebViewBridgeMethods(JNIEnv*); }
}

static void JNICALL Log(JNIEnv*, jclass, jint, jstring, jstring) {}

@interface GuardConfiguration : WKWebViewConfiguration
@property bool throwOnRegistration;
@end
@implementation GuardConfiguration
- (void)setURLSchemeHandler:(id<WKURLSchemeHandler>)handler forURLScheme:(NSString*)scheme {
    // A concurrent classification must still use WebKit's original answer,
    // even while the registration thread temporarily answers NO.
    std::thread other([] {
        if (![WKWebView handlesURLScheme:@"https"]) std::exit(3);
    });
    other.join();
    if (self.throwOnRegistration) [NSException raise:@"SmokeRegistrationFailure" format:@"expected"];
    [super setURLSchemeHandler:handler forURLScheme:scheme];
}
@end

static void CheckRegistrationGuard() {
    WvInstallInterception([GuardConfiguration new]);
    GuardConfiguration* throwing = [GuardConfiguration new];
    throwing.throwOnRegistration = true;
    @try { WvInstallInterception(throwing); std::exit(3); }
    @catch (NSException* exception) {
        if (![exception.name isEqualToString:@"SmokeRegistrationFailure"]) std::exit(3);
    }
    if (![WKWebView handlesURLScheme:@"https"] || ![WKWebView handlesURLScheme:@"http"]
        || [[WKWebViewConfiguration new] urlSchemeHandlerForURLScheme:@"https"]) std::exit(3);
    std::puts("PASS WebView registration guard: exception cleanup, concurrent thread, ordinary configuration");
}

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    @autoreleasepool {
        [NSApplication sharedApplication];
        std::string classpath = std::string("-Djava.class.path=") + argv[1];
        std::thread([classpath] {
            JavaVMOption options[] = {
                {const_cast<char*>(classpath.c_str()), nullptr},
                {(char*)"--add-exports=java.base/sun.net.www.protocol.http=ALL-UNNAMED", nullptr},
                {(char*)"--add-exports=java.base/sun.net.www.protocol.https=ALL-UNNAMED", nullptr},
            };
            JavaVMInitArgs args = {};
            args.version = JNI_VERSION_1_8;
            args.nOptions = sizeof(options) / sizeof(options[0]);
            args.options = options;
            JavaVM* vm = nullptr;
            JNIEnv* env = nullptr;
            if (JNI_CreateJavaVM(&vm, reinterpret_cast<void**>(&env), &args) != JNI_OK) std::exit(2);
            jclass log = env->FindClass("com/macapk/loader/NativeLog");
            JNINativeMethod logging = {(char*)"print", (char*)"(ILjava/lang/String;Ljava/lang/String;)V", (void*)Log};
            env->RegisterNatives(log, &logging, 1);
            macapk::webview::RegisterWebViewBridgeMethods(env);
            if (WvInterceptEnabled()) dispatch_sync(dispatch_get_main_queue(), ^{ CheckRegistrationGuard(); });
            jclass cls = env->FindClass("android/webkit/WebViewInterceptSmoke");
            if (cls) {
                jmethodID main = env->GetStaticMethodID(cls, "main", "([Ljava/lang/String;)V");
                if (main) env->CallStaticVoidMethod(cls, main, nullptr);
            }
            if (env->ExceptionCheck()) env->ExceptionDescribe();
            std::exit(2);
        }).detach();
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 30 * NSEC_PER_SEC), dispatch_get_main_queue(), ^{
            std::fprintf(stderr, "FAIL WebViewInterceptSmoke: timed out\n");
            std::exit(1);
        });
        [NSApp run];
    }
}
