#include <jni.h>
#include <aaudio/AAudio.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>

extern "C" {
void* fm1_android_create(const uint8_t* package, size_t package_len,
                         const uint8_t* saved_flash, size_t saved_flash_len,
                         uint32_t clock_mhz, char* error, size_t error_capacity);
void fm1_android_destroy(void* engine);
size_t fm1_android_render_audio(void* engine, float* output, size_t frames);
int32_t fm1_android_render_panel(void* engine, uint32_t* output, size_t pixels);
void fm1_android_panel_size(void* engine, uint32_t* width, uint32_t* height);
int32_t fm1_android_panel_hit(void* engine, float x, float y);
float fm1_android_panel_hit_y(void* engine);
uint32_t fm1_android_panel_slide(void* engine, uint32_t index, float y);
void fm1_android_key(void* engine, uint32_t id, uint32_t down, uint32_t velocity);
void fm1_android_encoder(void* engine, uint32_t panel_index, int32_t clicks);
void fm1_android_analog(void* engine, uint32_t index, uint32_t value);
void fm1_android_pot(void* engine, uint32_t value);
void fm1_android_set_theme(void* engine, uint32_t theme);
uint32_t fm1_android_theme_count();
size_t fm1_android_theme_name(uint32_t index, char* output, size_t capacity);
size_t fm1_android_status(void* engine, char* output, size_t capacity);
size_t fm1_android_export_flash(void* engine, uint8_t* output, size_t capacity);
}

namespace {
std::mutex gErrorMutex;
std::string gLastError;

void setLastError(const std::string& error) {
    std::lock_guard<std::mutex> lock(gErrorMutex);
    gLastError = error;
}

std::string getLastError() {
    std::lock_guard<std::mutex> lock(gErrorMutex);
    return gLastError;
}

struct AudioOutput {
    AAudioStream* stream = nullptr;
    void* engine = nullptr;
    int32_t framesPerBurst = 0;
    int32_t bufferSizeFrames = 0;
    aaudio_performance_mode_t performanceMode = AAUDIO_PERFORMANCE_MODE_NONE;
};

aaudio_data_callback_result_t onAudioData(AAudioStream*, void* userData,
                                          void* audioData, int32_t numFrames) {
    auto* output = static_cast<AudioOutput*>(userData);
    auto* samples = static_cast<float*>(audioData);
    if (output == nullptr || output->engine == nullptr || samples == nullptr || numFrames <= 0) {
        return AAUDIO_CALLBACK_RESULT_STOP;
    }
    // This is the only work done on AAudio's high-priority callback thread. The emulator itself
    // runs elsewhere; this call drains an SPSC ring and zero-fills any underrun.
    fm1_android_render_audio(output->engine, samples, static_cast<size_t>(numFrames));
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void closeAudio(AudioOutput* output) {
    if (output == nullptr) return;
    if (output->stream != nullptr) {
        AAudioStream_requestStop(output->stream);
        AAudioStream_close(output->stream);
        output->stream = nullptr;
    }
    delete output;
}

jlong createEngine(JNIEnv* env, jbyteArray packageBytes, jbyteArray savedBytes, jint clockMhz) {
    if (packageBytes == nullptr) {
        setLastError("Select an .fwsc firmware package first.");
        return 0;
    }
    const jsize packageLength = env->GetArrayLength(packageBytes);
    if (packageLength <= 0 || packageLength > 32 * 1024 * 1024) {
        setLastError("Firmware package is empty or exceeds the 32 MiB safety limit.");
        return 0;
    }
    std::vector<uint8_t> package(static_cast<size_t>(packageLength));
    env->GetByteArrayRegion(packageBytes, 0, packageLength, reinterpret_cast<jbyte*>(package.data()));
    if (env->ExceptionCheck()) return 0;

    std::vector<uint8_t> saved;
    if (savedBytes != nullptr) {
        const jsize savedLength = env->GetArrayLength(savedBytes);
        if (savedLength == 1024 * 1024) {
            saved.resize(static_cast<size_t>(savedLength));
            env->GetByteArrayRegion(savedBytes, 0, savedLength, reinterpret_cast<jbyte*>(saved.data()));
            if (env->ExceptionCheck()) return 0;
        }
    }

    char error[512] = {};
    void* engine = fm1_android_create(package.data(), package.size(),
                                      saved.empty() ? nullptr : saved.data(), saved.size(),
                                      static_cast<uint32_t>(clockMhz), error, sizeof(error));
    if (engine == nullptr) {
        setLastError(error[0] != '\0' ? error : "The emulator could not initialize this firmware.");
        return 0;
    }
    setLastError("");
    return reinterpret_cast<jlong>(engine);
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_fm1emu_android_MainActivity_nativeCreateEngine(JNIEnv* env, jobject,
                                                         jbyteArray packageBytes,
                                                         jbyteArray savedBytes,
                                                         jint clockMhz) {
    return createEngine(env, packageBytes, savedBytes, clockMhz);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fm1emu_android_MainActivity_nativeLastError(JNIEnv* env, jobject) {
    const std::string error = getLastError();
    return env->NewStringUTF(error.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_fm1emu_android_MainActivity_nativeDestroyEngine(JNIEnv*, jobject, jlong engineHandle) {
    fm1_android_destroy(reinterpret_cast<void*>(engineHandle));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_fm1emu_android_MainActivity_nativeStartAudio(JNIEnv*, jobject, jlong engineHandle) {
    auto* engine = reinterpret_cast<void*>(engineHandle);
    if (engine == nullptr) return 0;

    auto* output = new AudioOutput();
    output->engine = engine;
    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK || builder == nullptr) {
        setLastError(std::string("AAudio builder: ") + AAudio_convertResultToText(result));
        delete output;
        return 0;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, 44100);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    // AAudio defaults to MEDIA usage and MUSIC content type. Keep those defaults:
    // the explicit setters require Android API 28, while this app supports API 26.
    AAudioStreamBuilder_setDataCallback(builder, onAudioData, output);

    result = AAudioStreamBuilder_openStream(builder, &output->stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || output->stream == nullptr) {
        setLastError(std::string("AAudio open: ") + AAudio_convertResultToText(result));
        delete output;
        return 0;
    }

    if (AAudioStream_getFormat(output->stream) != AAUDIO_FORMAT_PCM_FLOAT ||
        AAudioStream_getChannelCount(output->stream) != 2 ||
        AAudioStream_getSampleRate(output->stream) != 44100) {
        setLastError("AAudio did not provide the required 44.1 kHz stereo float output format.");
        closeAudio(output);
        return 0;
    }

    output->framesPerBurst = AAudioStream_getFramesPerBurst(output->stream);
    const int32_t capacity = AAudioStream_getBufferCapacityInFrames(output->stream);
    if (output->framesPerBurst > 0 && capacity > 0) {
        // Start with three hardware bursts. The Rust-side ring adapts independently if the
        // emulator has occasional scheduling spikes.
        const int32_t requested = std::min(capacity, output->framesPerBurst * 3);
        AAudioStream_setBufferSizeInFrames(output->stream, requested);
    }
    output->bufferSizeFrames = AAudioStream_getBufferSizeInFrames(output->stream);
    output->performanceMode = AAudioStream_getPerformanceMode(output->stream);

    result = AAudioStream_requestStart(output->stream);
    if (result != AAUDIO_OK) {
        setLastError(std::string("AAudio start: ") + AAudio_convertResultToText(result));
        closeAudio(output);
        return 0;
    }
    setLastError("");
    return reinterpret_cast<jlong>(output);
}

extern "C" JNIEXPORT void JNICALL
Java_com_fm1emu_android_MainActivity_nativeStopAudio(JNIEnv*, jobject, jlong audioHandle) {
    closeAudio(reinterpret_cast<AudioOutput*>(audioHandle));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_fm1emu_android_MainActivity_nativeAudioInfo(JNIEnv* env, jobject, jlong audioHandle) {
    auto* output = reinterpret_cast<AudioOutput*>(audioHandle);
    if (output == nullptr || output->stream == nullptr) return env->NewStringUTF("Audio stopped");
    char info[256];
    std::snprintf(info, sizeof(info), "AAudio · %d Hz · burst %d · buffer %d · mode %d · xruns %d",
                  AAudioStream_getSampleRate(output->stream), output->framesPerBurst,
                  output->bufferSizeFrames, static_cast<int>(output->performanceMode),
                  AAudioStream_getXRunCount(output->stream));
    return env->NewStringUTF(info);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_fm1emu_android_MainActivity_nativePanelSize(JNIEnv* env, jobject, jlong engineHandle) {
    uint32_t width = 0, height = 0;
    fm1_android_panel_size(reinterpret_cast<void*>(engineHandle), &width, &height);
    jint values[2] = {static_cast<jint>(width), static_cast<jint>(height)};
    jintArray array = env->NewIntArray(2);
    if (array != nullptr) env->SetIntArrayRegion(array, 0, 2, values);
    return array;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fm1emu_android_MainActivity_nativeRenderPanel(JNIEnv* env, jobject, jlong engineHandle,
                                                        jobject destination) {
    auto* output = static_cast<uint32_t*>(env->GetDirectBufferAddress(destination));
    const jlong capacity = env->GetDirectBufferCapacity(destination);
    if (output == nullptr || capacity <= 0) return 0;
    return fm1_android_render_panel(reinterpret_cast<void*>(engineHandle), output,
                                    static_cast<size_t>(capacity) / sizeof(uint32_t));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_fm1emu_android_MainActivity_nativePanelHit(JNIEnv*, jobject, jlong engineHandle,
                                                     jfloat x, jfloat y) {
    return fm1_android_panel_hit(reinterpret_cast<void*>(engineHandle), x, y);
}
extern "C" JNIEXPORT jfloat JNICALL
Java_com_fm1emu_android_MainActivity_nativePanelHitY(JNIEnv*, jobject, jlong engineHandle) {
    return fm1_android_panel_hit_y(reinterpret_cast<void*>(engineHandle));
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_fm1emu_android_MainActivity_nativeIsSmk37(JNIEnv*, jobject, jlong engineHandle) {
    uint32_t width = 0, height = 0;
    fm1_android_panel_size(reinterpret_cast<void*>(engineHandle), &width, &height);
    return width == 1920 ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_fm1emu_android_MainActivity_nativePanelSlide(JNIEnv*, jobject, jlong engineHandle,
                                                       jint index, jfloat y) {
    return static_cast<jint>(fm1_android_panel_slide(reinterpret_cast<void*>(engineHandle),
                                                      static_cast<uint32_t>(index), y));
}

extern "C" JNIEXPORT void JNICALL
Java_com_fm1emu_android_MainActivity_nativeKey(JNIEnv*, jobject, jlong engineHandle,
                                                jint id, jboolean down, jint velocity) {
    fm1_android_key(reinterpret_cast<void*>(engineHandle), static_cast<uint32_t>(id),
                    down == JNI_TRUE ? 1u : 0u, static_cast<uint32_t>(std::max(0, velocity)));
}
extern "C" JNIEXPORT void JNICALL
Java_com_fm1emu_android_MainActivity_nativeEncoder(JNIEnv*, jobject, jlong engineHandle,
                                                    jint index, jint clicks) {
    fm1_android_encoder(reinterpret_cast<void*>(engineHandle), static_cast<uint32_t>(index), clicks);
}
extern "C" JNIEXPORT void JNICALL
Java_com_fm1emu_android_MainActivity_nativeAnalog(JNIEnv*, jobject, jlong engineHandle,
                                                   jint index, jint value) {
    fm1_android_analog(reinterpret_cast<void*>(engineHandle), static_cast<uint32_t>(index),
                       static_cast<uint32_t>(std::clamp(value, 0, 1023)));
}
extern "C" JNIEXPORT void JNICALL
Java_com_fm1emu_android_MainActivity_nativePot(JNIEnv*, jobject, jlong engineHandle, jint value) {
    fm1_android_pot(reinterpret_cast<void*>(engineHandle), static_cast<uint32_t>(std::clamp(value, 0, 1023)));
}
extern "C" JNIEXPORT jint JNICALL
Java_com_fm1emu_android_MainActivity_nativeThemeCount(JNIEnv*, jobject) {
    return static_cast<jint>(fm1_android_theme_count());
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_fm1emu_android_MainActivity_nativeThemeName(JNIEnv* env, jobject, jint index) {
    char name[64] = {};
    fm1_android_theme_name(static_cast<uint32_t>(std::max(0, index)), name, sizeof(name));
    return env->NewStringUTF(name);
}
extern "C" JNIEXPORT void JNICALL
Java_com_fm1emu_android_MainActivity_nativeSetTheme(JNIEnv*, jobject, jlong engineHandle, jint index) {
    fm1_android_set_theme(reinterpret_cast<void*>(engineHandle), static_cast<uint32_t>(std::max(0, index)));
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_fm1emu_android_MainActivity_nativeStatus(JNIEnv* env, jobject, jlong engineHandle) {
    char status[512] = {};
    fm1_android_status(reinterpret_cast<void*>(engineHandle), status, sizeof(status));
    return env->NewStringUTF(status);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_fm1emu_android_MainActivity_nativeExportFlash(JNIEnv* env, jobject, jlong engineHandle,
                                                         jobject destination) {
    auto* output = static_cast<uint8_t*>(env->GetDirectBufferAddress(destination));
    const jlong capacity = env->GetDirectBufferCapacity(destination);
    if (output == nullptr || capacity <= 0) return 0;
    return static_cast<jint>(fm1_android_export_flash(reinterpret_cast<void*>(engineHandle),
                                                       output, static_cast<size_t>(capacity)));
}
