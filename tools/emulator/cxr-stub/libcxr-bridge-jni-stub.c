/* x86_64 no-op stand-in for the vendor's arm64/armv7-only libcxr-bridge-jni.so.
 * Lets the glasses hub start on an x86_64 emulator with no ARM translation. Every call reports
 * "no CXR link" (sendMessage < 0), so the hub behaves as if no phone were connected. Emulation
 * only: never ship an APK repacked with this. */
#include <jni.h>
#include <stddef.h>

#define B(n) Java_com_rokid_cxr_CXRServiceBridge_##n

JNIEXPORT jint JNICALL B(sendMessage__Ljava_lang_String_2Lcom_rokid_cxr_Caps_2)(JNIEnv *e, jobject o, jstring k, jobject c) { return -1; }
JNIEXPORT jint JNICALL B(sendMessage__Ljava_lang_String_2Lcom_rokid_cxr_Caps_2_3BII)(JNIEnv *e, jobject o, jstring k, jobject c, jbyteArray b, jint a, jint l) { return -1; }
JNIEXPORT void JNICALL B(disconnectCXRDevice)(JNIEnv *e, jobject o) {}
JNIEXPORT jint JNICALL B(startAudioStream)(JNIEnv *e, jobject o, jint a, jint b, jstring c, jobject d) { return -1; }
JNIEXPORT void JNICALL B(stopAudioStream)(JNIEnv *e, jobject o, jstring s) {}
JNIEXPORT void JNICALL B(startBTPairing)(JNIEnv *e, jobject o, jint a) {}
JNIEXPORT jint JNICALL B(sendARTCFrame)(JNIEnv *e, jobject o, jbyteArray b, jint a, jint l, jboolean k, jlong t) { return -1; }
JNIEXPORT void JNICALL B(appLaunch)(JNIEnv *e, jobject o) {}
JNIEXPORT void JNICALL B(nativeInitialize)(JNIEnv *e, jobject o) {}
JNIEXPORT void JNICALL B(nativeSubscribe)(JNIEnv *e, jobject o, jstring s) {}
JNIEXPORT void JNICALL B(nativeOpenAudioRecord)(JNIEnv *e, jobject o, jint a, jint b, jint c, jobject d) {}
JNIEXPORT void JNICALL B(nativeCloseAudioRecord)(JNIEnv *e, jobject o, jint a) {}
JNIEXPORT jbyteArray JNICALL Java_com_rokid_cxr_Caps_serialize(JNIEnv *e, jobject o) { return NULL; }
JNIEXPORT jboolean JNICALL Java_com_rokid_cxr_Caps_parse___3BII(JNIEnv *e, jobject o, jbyteArray b, jint a, jint l) { return JNI_FALSE; }
JNIEXPORT jstring JNICALL Java_com_rokid_cxr_Caps_dump(JNIEnv *e, jobject o) { return NULL; }
