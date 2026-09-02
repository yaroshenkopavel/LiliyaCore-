#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "llama.h"

namespace {

constexpr jint LINK_PROBE_OK = 1;
constexpr jint LINK_PROBE_FAILED = 0;

constexpr jlong LOAD_RESOURCE_REJECTED = -1;
constexpr jlong LOAD_UNSUPPORTED = -2;
constexpr jlong LOAD_REJECTED = -3;
constexpr jlong LOAD_PROVIDER_FAILED = -4;

constexpr jbyte INFER_OK = 0;
constexpr jbyte INFER_RESOURCE_REJECTED = 1;
constexpr jbyte INFER_REQUEST_REJECTED = 2;
constexpr jbyte INFER_STALE_SESSION = 3;
constexpr jbyte INFER_OPERATION_FAILED = 4;
constexpr jbyte INFER_PROVIDER_FAILED = 5;

constexpr jint CLOSE_OK = 0;
constexpr jint CLOSE_FAILED = 1;
constexpr jint CLOSE_PROVIDER_FAILED = 2;

struct NativeSession {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    const llama_vocab * vocab = nullptr;
    int32_t max_prompt_tokens = 0;
    int32_t max_generated_tokens = 0;
    int32_t batch_tokens = 0;
    int32_t max_prompt_utf8_bytes = 0;
    int32_t max_output_utf8_bytes = 0;
    std::mutex execution_mutex;

    void release() noexcept {
        if (context != nullptr) {
            llama_free(context);
            context = nullptr;
        }
        if (model != nullptr) {
            llama_model_free(model);
            model = nullptr;
        }
        vocab = nullptr;
    }

    ~NativeSession() {
        release();
    }
};

std::mutex g_registry_mutex;
std::unordered_map<int64_t, std::unique_ptr<NativeSession>> g_sessions;
std::atomic<int64_t> g_next_session_id{1};
std::once_flag g_backend_init_once;
std::atomic<bool> g_backend_initialized{false};

void discard_llama_log(ggml_log_level, const char *, void *) {
    // Prompt/model path/native messages are private at this boundary.
}

void ensure_backend_initialized() {
    std::call_once(g_backend_init_once, [] {
        llama_log_set(discard_llama_log, nullptr);
        llama_backend_init();
        g_backend_initialized.store(true, std::memory_order_release);
    });
}

bool copy_bytes(JNIEnv * env, jbyteArray input, std::vector<uint8_t> & output) {
    if (input == nullptr) {
        return false;
    }
    const jsize size = env->GetArrayLength(input);
    if (size < 0) {
        return false;
    }
    output.resize(static_cast<size_t>(size));
    if (size > 0) {
        env->GetByteArrayRegion(
            input,
            0,
            size,
            reinterpret_cast<jbyte *>(output.data())
        );
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            output.clear();
            return false;
        }
    }
    return true;
}

struct ByteScrubber {
    explicit ByteScrubber(std::vector<uint8_t> & bytes) : bytes(bytes) {}
    ~ByteScrubber() {
        std::fill(bytes.begin(), bytes.end(), static_cast<uint8_t>(0));
    }
    std::vector<uint8_t> & bytes;
};

jbyteArray make_infer_packet(
    JNIEnv * env,
    jbyte status,
    const std::string & output = std::string()
) {
    if (output.size() > static_cast<size_t>(std::numeric_limits<jsize>::max() - 1)) {
        jbyteArray failed = env->NewByteArray(1);
        if (failed != nullptr) {
            env->SetByteArrayRegion(failed, 0, 1, &INFER_PROVIDER_FAILED);
        }
        return failed;
    }

    const jsize size = static_cast<jsize>(output.size() + 1);
    jbyteArray packet = env->NewByteArray(size);
    if (packet == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(packet, 0, 1, &status);
    if (!output.empty()) {
        env->SetByteArrayRegion(
            packet,
            1,
            static_cast<jsize>(output.size()),
            reinterpret_cast<const jbyte *>(output.data())
        );
    }
    return packet;
}

int64_t allocate_native_session_id() {
    while (true) {
        int64_t current = g_next_session_id.load(std::memory_order_acquire);
        if (current <= 0 || current == std::numeric_limits<int64_t>::max()) {
            return 0;
        }
        if (g_next_session_id.compare_exchange_weak(
                current,
                current + 1,
                std::memory_order_acq_rel,
                std::memory_order_acquire)) {
            return current;
        }
    }
}

enum class PieceResult {
    OK,
    TOO_LARGE,
    FAILED
};

PieceResult token_to_piece(
    const llama_vocab * vocab,
    llama_token token,
    size_t byte_budget,
    std::string & piece
) {
    char stack_buffer[256];
    int32_t count = llama_token_to_piece(
        vocab,
        token,
        stack_buffer,
        static_cast<int32_t>(sizeof(stack_buffer)),
        0,
        true
    );
    if (count >= 0) {
        if (static_cast<size_t>(count) > byte_budget) {
            return PieceResult::TOO_LARGE;
        }
        piece.assign(stack_buffer, static_cast<size_t>(count));
        return PieceResult::OK;
    }

    if (count == std::numeric_limits<int32_t>::min()) {
        return PieceResult::FAILED;
    }
    const int32_t required = -count;
    if (required <= 0) {
        return PieceResult::FAILED;
    }
    if (static_cast<size_t>(required) > byte_budget) {
        return PieceResult::TOO_LARGE;
    }
    std::vector<char> buffer(static_cast<size_t>(required));
    count = llama_token_to_piece(vocab, token, buffer.data(), required, 0, true);
    if (count < 0) {
        return PieceResult::FAILED;
    }
    if (static_cast<size_t>(count) > byte_budget) {
        return PieceResult::TOO_LARGE;
    }
    piece.assign(buffer.data(), static_cast<size_t>(count));
    return PieceResult::OK;
}

}  // namespace

extern "C"
JNIEXPORT jint JNICALL
Java_pro_liliya_android_llamacppengine_LlamaCppNativeBridge_nativeLinkProbe(
    JNIEnv *,
    jobject
) {
    try {
        return llama_max_devices() > 0 ? LINK_PROBE_OK : LINK_PROBE_FAILED;
    } catch (...) {
        return LINK_PROBE_FAILED;
    }
}

extern "C"
JNIEXPORT jlong JNICALL
Java_pro_liliya_android_llamacppengine_LlamaCppNativeBridge_nativeLoad(
    JNIEnv * env,
    jobject,
    jbyteArray source_path_utf8,
    jint context_tokens,
    jint max_prompt_tokens,
    jint max_generated_tokens,
    jint batch_tokens,
    jint micro_batch_tokens,
    jint thread_count,
    jint max_prompt_utf8_bytes,
    jint max_output_utf8_bytes,
    jboolean use_mmap
) {
    if (
        context_tokens <= 0 ||
        max_prompt_tokens <= 0 ||
        max_generated_tokens <= 0 ||
        batch_tokens <= 0 ||
        micro_batch_tokens <= 0 ||
        thread_count <= 0 ||
        max_prompt_utf8_bytes <= 0 ||
        max_output_utf8_bytes <= 0 ||
        max_prompt_tokens > context_tokens ||
        max_generated_tokens > context_tokens ||
        static_cast<int64_t>(max_prompt_tokens) + max_generated_tokens > context_tokens ||
        batch_tokens > context_tokens ||
        micro_batch_tokens > batch_tokens
    ) {
        return LOAD_RESOURCE_REJECTED;
    }

    try {
        std::vector<uint8_t> path_bytes;
        if (!copy_bytes(env, source_path_utf8, path_bytes) || path_bytes.empty()) {
            return LOAD_REJECTED;
        }
        ByteScrubber path_scrubber(path_bytes);
        if (
            std::find(path_bytes.begin(), path_bytes.end(), static_cast<uint8_t>(0)) !=
            path_bytes.end()
        ) {
            return LOAD_REJECTED;
        }
        path_bytes.push_back(0);

        ensure_backend_initialized();

        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        model_params.load_mode = use_mmap == JNI_TRUE
            ? LLAMA_LOAD_MODE_MMAP
            : LLAMA_LOAD_MODE_NONE;
        model_params.check_tensors = true;

        llama_model * raw_model = llama_model_load_from_file(
            reinterpret_cast<const char *>(path_bytes.data()),
            model_params
        );
        if (raw_model == nullptr) {
            return LOAD_REJECTED;
        }
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
            raw_model,
            llama_model_free
        );

        if (llama_model_has_encoder(model.get())) {
            return LOAD_UNSUPPORTED;
        }

        const llama_vocab * vocab = llama_model_get_vocab(model.get());
        if (vocab == nullptr) {
            return LOAD_UNSUPPORTED;
        }

        llama_context_params context_params = llama_context_default_params();
        context_params.n_ctx = static_cast<uint32_t>(context_tokens);
        context_params.n_batch = static_cast<uint32_t>(batch_tokens);
        context_params.n_ubatch = static_cast<uint32_t>(micro_batch_tokens);
        context_params.no_perf = true;

        llama_context * raw_context = llama_init_from_model(model.get(), context_params);
        if (raw_context == nullptr) {
            return LOAD_REJECTED;
        }
        std::unique_ptr<llama_context, decltype(&llama_free)> context(
            raw_context,
            llama_free
        );
        llama_set_n_threads(context.get(), thread_count, thread_count);

        auto session = std::make_unique<NativeSession>();
        session->model = model.release();
        session->context = context.release();
        session->vocab = vocab;
        session->max_prompt_tokens = max_prompt_tokens;
        session->max_generated_tokens = max_generated_tokens;
        session->batch_tokens = batch_tokens;
        session->max_prompt_utf8_bytes = max_prompt_utf8_bytes;
        session->max_output_utf8_bytes = max_output_utf8_bytes;

        const int64_t session_id = allocate_native_session_id();
        if (session_id <= 0) {
            return LOAD_PROVIDER_FAILED;
        }

        {
            std::lock_guard<std::mutex> registry_guard(g_registry_mutex);
            const auto inserted = g_sessions.emplace(session_id, std::move(session));
            if (!inserted.second) {
                return LOAD_PROVIDER_FAILED;
            }
        }
        return static_cast<jlong>(session_id);
    } catch (...) {
        return LOAD_PROVIDER_FAILED;
    }
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_pro_liliya_android_llamacppengine_LlamaCppNativeBridge_nativeInfer(
    JNIEnv * env,
    jobject,
    jlong native_session_id,
    jbyteArray prompt_utf8,
    jint max_output_chars
) {
    if (native_session_id <= 0) {
        return make_infer_packet(env, INFER_STALE_SESSION);
    }
    if (max_output_chars <= 0 || prompt_utf8 == nullptr) {
        return make_infer_packet(env, INFER_REQUEST_REJECTED);
    }

    try {
        NativeSession * session = nullptr;
        std::unique_lock<std::mutex> session_guard;
        {
            std::unique_lock<std::mutex> registry_guard(g_registry_mutex);
            auto it = g_sessions.find(static_cast<int64_t>(native_session_id));
            if (it == g_sessions.end() || it->second == nullptr) {
                return make_infer_packet(env, INFER_STALE_SESSION);
            }
            session = it->second.get();
            session_guard = std::unique_lock<std::mutex>(session->execution_mutex);
        }

        const jsize prompt_size = env->GetArrayLength(prompt_utf8);
        if (prompt_size < 0) {
            return make_infer_packet(env, INFER_PROVIDER_FAILED);
        }
        if (prompt_size > session->max_prompt_utf8_bytes) {
            return make_infer_packet(env, INFER_RESOURCE_REJECTED);
        }

        std::vector<uint8_t> prompt;
        if (!copy_bytes(env, prompt_utf8, prompt)) {
            return make_infer_packet(env, INFER_PROVIDER_FAILED);
        }
        ByteScrubber prompt_scrubber(prompt);

        try {
            int32_t token_count = llama_tokenize(
                session->vocab,
                reinterpret_cast<const char *>(prompt.data()),
                static_cast<int32_t>(prompt.size()),
                nullptr,
                0,
                true,
                true
            );
            if (token_count == std::numeric_limits<int32_t>::min()) {
                return make_infer_packet(env, INFER_RESOURCE_REJECTED);
            }
            if (token_count < 0) {
                token_count = -token_count;
            }
            if (token_count <= 0) {
                return make_infer_packet(env, INFER_REQUEST_REJECTED);
            }
            if (token_count > session->max_prompt_tokens) {
                return make_infer_packet(env, INFER_RESOURCE_REJECTED);
            }

            std::vector<llama_token> tokens(static_cast<size_t>(token_count));
            const int32_t actual = llama_tokenize(
                session->vocab,
                reinterpret_cast<const char *>(prompt.data()),
                static_cast<int32_t>(prompt.size()),
                tokens.data(),
                token_count,
                true,
                true
            );
            if (actual < 0 || actual != token_count) {
                return make_infer_packet(env, INFER_OPERATION_FAILED);
            }

            llama_memory_clear(llama_get_memory(session->context), true);

            int32_t offset = 0;
            while (offset < token_count) {
                const int32_t chunk = std::min(session->batch_tokens, token_count - offset);
                llama_batch batch = llama_batch_get_one(tokens.data() + offset, chunk);
                if (llama_decode(session->context, batch) != 0) {
                    llama_memory_clear(llama_get_memory(session->context), true);
                    return make_infer_packet(env, INFER_OPERATION_FAILED);
                }
                offset += chunk;
            }

            std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
                llama_sampler_init_greedy(),
                llama_sampler_free
            );
            if (sampler == nullptr) {
                llama_memory_clear(llama_get_memory(session->context), true);
                return make_infer_packet(env, INFER_PROVIDER_FAILED);
            }

            std::string output;
            const size_t char_byte_ceiling = static_cast<size_t>(max_output_chars) * 4U;
            const size_t max_output_bytes = std::min(
                char_byte_ceiling,
                static_cast<size_t>(session->max_output_utf8_bytes)
            );
            for (int32_t generated = 0; generated < session->max_generated_tokens; ++generated) {
                const llama_token token = llama_sampler_sample(sampler.get(), session->context, -1);
                if (llama_vocab_is_eog(session->vocab, token)) {
                    break;
                }

                const size_t remaining = max_output_bytes - std::min(max_output_bytes, output.size());
                std::string piece;
                const PieceResult piece_result = token_to_piece(
                    session->vocab,
                    token,
                    remaining,
                    piece
                );
                if (piece_result == PieceResult::TOO_LARGE) {
                    break;
                }
                if (piece_result == PieceResult::FAILED) {
                    llama_memory_clear(llama_get_memory(session->context), true);
                    return make_infer_packet(env, INFER_OPERATION_FAILED);
                }
                output.append(piece);
                if (output.size() >= max_output_bytes) {
                    break;
                }

                llama_token next_token = token;
                llama_batch next = llama_batch_get_one(&next_token, 1);
                if (llama_decode(session->context, next) != 0) {
                    llama_memory_clear(llama_get_memory(session->context), true);
                    return make_infer_packet(env, INFER_OPERATION_FAILED);
                }
            }

            llama_memory_clear(llama_get_memory(session->context), true);
            return make_infer_packet(env, INFER_OK, output);
        } catch (...) {
            llama_memory_clear(llama_get_memory(session->context), true);
            return make_infer_packet(env, INFER_PROVIDER_FAILED);
        }
    } catch (...) {
        return make_infer_packet(env, INFER_PROVIDER_FAILED);
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_pro_liliya_android_llamacppengine_LlamaCppNativeBridge_nativeClose(
    JNIEnv *,
    jobject,
    jlong native_session_id
) {
    if (native_session_id <= 0) {
        return CLOSE_FAILED;
    }

    try {
        std::unique_ptr<NativeSession> retired;
        {
            std::unique_lock<std::mutex> registry_guard(g_registry_mutex);
            auto it = g_sessions.find(static_cast<int64_t>(native_session_id));
            if (it == g_sessions.end() || it->second == nullptr) {
                return CLOSE_FAILED;
            }

            NativeSession * session = it->second.get();
            std::unique_lock<std::mutex> session_guard(session->execution_mutex);
            session->release();
            retired = std::move(it->second);
            g_sessions.erase(it);
        }
        retired.reset();
        return CLOSE_OK;
    } catch (...) {
        return CLOSE_PROVIDER_FAILED;
    }
}

extern "C"
JNIEXPORT void JNICALL
JNI_OnUnload(JavaVM *, void *) {
    try {
        std::unordered_map<int64_t, std::unique_ptr<NativeSession>> retired;
        {
            std::lock_guard<std::mutex> registry_guard(g_registry_mutex);
            retired.swap(g_sessions);
        }
        retired.clear();
        if (g_backend_initialized.exchange(false, std::memory_order_acq_rel)) {
            llama_backend_free();
        }
    } catch (...) {
        // JNI unload cannot report a structural result; never allow a C++ exception to escape.
    }
}
