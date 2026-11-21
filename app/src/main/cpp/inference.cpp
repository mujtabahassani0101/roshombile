#include <jni.h>
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include <memory>
#include <stdexcept>
#include <vector>
#include <algorithm>
#include <cmath>

namespace {
struct Network {
    std::unique_ptr<MNN::Interpreter> interpreter;
    MNN::Session* session = nullptr;
    MNN::Tensor* input = nullptr;
    std::unique_ptr<MNN::Tensor> host;
    Network(JNIEnv* env, jbyteArray model) {
        std::vector<jbyte> bytes(env->GetArrayLength(model));
        env->GetByteArrayRegion(model, 0, bytes.size(), bytes.data());
        interpreter.reset(MNN::Interpreter::createFromBuffer(bytes.data(), bytes.size()));
        if (!interpreter) throw std::runtime_error("Invalid local scanner model");
        MNN::ScheduleConfig config;
        config.numThread = 4;
        config.type = MNN_FORWARD_CPU;
        session = interpreter->createSession(config);
        if (!session) throw std::runtime_error("Scanner session initialization failed");
        input = interpreter->getSessionInput(session, nullptr);
        host = std::make_unique<MNN::Tensor>(input, MNN::Tensor::CAFFE);
        if (host->width() != 240 || host->height() != 240 || host->channel() != 3)
            throw std::runtime_error("Unexpected scanner input dimensions");
    }
    jfloatArray run(JNIEnv* env, jbyteArray image) {
        constexpr int pixels = 240 * 240;
        if (env->GetArrayLength(image) != pixels * 3) throw std::runtime_error("Invalid scanner tensor");
        std::vector<unsigned char> bytes(pixels * 3);
        env->GetByteArrayRegion(image, 0, bytes.size(), reinterpret_cast<jbyte*>(bytes.data()));
        float* data = host->host<float>();
        for (int i = 0; i < pixels; ++i)
            for (int c = 0; c < 3; ++c) data[c * pixels + i] = bytes[i * 3 + c] / 255.f;
        input->copyFromHostTensor(host.get());
        if (interpreter->runSession(session) != MNN::NO_ERROR) throw std::runtime_error("Local inference failed");
        auto tensor = interpreter->getSessionOutput(session, nullptr);
        MNN::Tensor output(tensor, MNN::Tensor::CAFFE);
        tensor->copyToHostTensor(&output);
        if (output.width() != 240 || output.height() != 240 || output.channel() != 2)
            throw std::runtime_error("Unexpected scanner output dimensions");
        auto result = env->NewFloatArray(output.elementSize());
        if (result) env->SetFloatArrayRegion(result, 0, output.elementSize(), output.host<float>());
        return result;
    }
};
struct Engine { std::unique_ptr<Network> border, dewarp; };
void error(JNIEnv* env, const std::exception& e) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_paperframe_paperframe_LocalModels_create(JNIEnv* env, jobject, jbyteArray border, jbyteArray dewarp) {
    try {
        auto engine = std::make_unique<Engine>();
        engine->border = std::make_unique<Network>(env, border);
        engine->dewarp = std::make_unique<Network>(env, dewarp);
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception& e) { error(env, e); return 0; }
}
extern "C" JNIEXPORT jfloatArray JNICALL
Java_dev_paperframe_paperframe_LocalModels_infer(JNIEnv* env, jobject, jlong handle, jbyteArray input, jboolean dewarp) {
    try {
        auto engine = reinterpret_cast<Engine*>(handle);
        if (!engine) throw std::runtime_error("Scanner engine is closed");
        return (dewarp ? engine->dewarp : engine->border)->run(env, input);
    } catch (const std::exception& e) { error(env, e); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_dev_paperframe_paperframe_LocalModels_destroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Engine*>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_paperframe_paperframe_LocalModels_mapTile(JNIEnv* env, jobject, jfloatArray grid, jobject buffer,
                                                  jint width, jint height, jint start, jint rows) {
    auto destination = static_cast<float*>(env->GetDirectBufferAddress(buffer));
    const auto capacity = env->GetDirectBufferCapacity(buffer);
    if (!destination || width < 1 || height < 1 || start < 0 || rows < 1 || start + rows > height ||
        capacity < static_cast<jlong>(width) * rows * 2 * sizeof(float) || env->GetArrayLength(grid) != 240 * 240 * 2) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid dewarp tile"); return;
    }
    auto source = env->GetFloatArrayElements(grid, nullptr);
    if (!source) return;
    // Match cv::resize half-pixel coordinates, without a full-resolution XY allocation.
    // Horizontal samples repeat for every output row sharing one 240px grid row.
    // Keep only two expanded rows; the final blend is a contiguous vector loop.
    struct Column { int x, next; float fraction; };
    std::vector<Column> columns(width);
    for (int column = 0; column < width; ++column) {
        const float x = std::clamp((column + .5f) * 240.f / width - .5f, 0.f, 239.f);
        const int ix = static_cast<int>(x);
        columns[column] = {ix, std::min(ix + 1, 239), x - ix};
    }
    std::vector<float> upper(width * 2), lower(width * 2);
    auto horizontal = [&](int row, std::vector<float>& output) {
        const float* values = source + row * 240 * 2;
        for (int column = 0; column < width; ++column) {
            const auto& x = columns[column];
            for (int c = 0; c < 2; ++c)
                output[column * 2 + c] = values[x.x * 2 + c] * (1 - x.fraction) + values[x.next * 2 + c] * x.fraction;
        }
    };
    int previous = -1, previousNext = -1;
    for (int row = 0; row < rows; ++row) {
        const float y = std::clamp((start + row + .5f) * 240.f / height - .5f, 0.f, 239.f);
        const int iy = static_cast<int>(y), ny = std::min(iy + 1, 239);
        const float fy = y - iy;
        if (iy != previous) {
            if (iy == previousNext) upper.swap(lower); else horizontal(iy, upper);
            horizontal(ny, lower);
            previous = iy; previousNext = ny;
        }
        float* target = destination + row * width * 2;
        for (int i = 0; i < width * 2; ++i) target[i] = upper[i] * (1 - fy) + lower[i] * fy;
    }
    env->ReleaseFloatArrayElements(grid, source, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_paperframe_paperframe_LocalModels_maskTile(JNIEnv* env, jobject, jbyteArray mask, jobject buffer,
                                                   jint width, jint height, jint start, jint rows) {
    auto destination = static_cast<jbyte*>(env->GetDirectBufferAddress(buffer));
    if (!destination || width < 1 || height < 1 || start < 0 || rows < 1 || start + rows > height ||
        env->GetDirectBufferCapacity(buffer) < static_cast<jlong>(width) * rows || env->GetArrayLength(mask) != 240 * 240) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid dewarp mask tile"); return;
    }
    auto source = env->GetByteArrayElements(mask, nullptr);
    if (!source) return;
    std::vector<int> columns(width);
    for (int column = 0; column < width; ++column) columns[column] = column * 240 / width;
    // Match INTER_NEAREST's integer sampling without allocating a full-resolution mask.
    for (int row = 0; row < rows; ++row) {
        const int y = (start + row) * 240 / height;
        for (int column = 0; column < width; ++column)
            destination[row * width + column] = source[y * 240 + columns[column]];
    }
    env->ReleaseByteArrayElements(mask, source, JNI_ABORT);
}
