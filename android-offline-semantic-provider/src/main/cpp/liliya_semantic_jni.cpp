#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
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

constexpr jbyte EMBED_OK = 0;
constexpr jbyte EMBED_RESOURCE_REJECTED = 1;
constexpr jbyte EMBED_REQUEST_REJECTED = 2;
constexpr jbyte EMBED_STALE_SESSION = 3;
constexpr jbyte EMBED_OPERATION_FAILED = 4;
constexpr jbyte EMBED_PROVIDER_FAILED = 5;

constexpr jint CLOSE_OK = 0;
constexpr jint CLOSE_FAILED = 1;
constexpr jint CLOSE_PROVIDER_FAILED = 2;

constexpr int32_t EMBEDDING_DIMENSION = 384;
constexpr int32_t MAX_CONTEXT_TOKENS = 512;
constexpr int32_t MAX_BATCH_TOKENS = 512;
constexpr int32_t MAX_THREAD_COUNT = 4;
constexpr int32_t MAX_INPUT_UTF8_BYTES = 4105;
constexpr size_t EMBEDDING_PACKET_BYTES = 1 + EMBEDDING_DIMENSION * sizeof(float);

struct NativeSemanticSession {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    const llama_vocab * vocab = nullptr;
    int32_t context_tokens = 0;
    int32_t batch_tokens = 0;
    int32_t max_input_utf8_bytes = 0;
    bool use_encoder = false;
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

    ~NativeSemanticSession() {
        release();
    }
};

std::mutex g_registry_mutex;
std::unordered_map<int64_t, std::unique_ptr<NativeSemanticSession>> g_sessions;
bool g_load_in_progress = false;
std::atomic<int64_t> g_next_session_id{1};
std::once_flag g_backend_init_once;

class NativeLoadReservation {
public:
    bool acquire() {
        std::lock_guard<std::mutex> guard(g_registry_mutex);
        if (g_load_in_progress || !g_sessions.empty()) {
            return false;
        }
        g_load_in_progress = true;
        active_ = true;
        return true;
    }

    void commit() noexcept {
        active_ = false;
    }

    ~NativeLoadReservation() {
        if (!active_) return;
        try {
            std::lock_guard<std::mutex> guard(g_registry_mutex);
            g_load_in_progress = false;
        } catch (...) {
            // Destructors must not allow an exception to cross the JNI boundary.
        }
    }

private:
    bool active_ = false;
};

void discard_llama_log(ggml_log_level, const char *, void *) {
    // Model paths, source text and native diagnostics are private at this boundary.
}

void ensure_backend_initialized() {
    std::call_once(g_backend_init_once, [] {
        llama_log_set(discard_llama_log, nullptr);
        llama_backend_init();
    });
}

bool copy_bytes(JNIEnv * env, jbyteArray input, std::vector<uint8_t> & output) {
    if (input == nullptr) return false;
    const jsize size = env->GetArrayLength(input);
    if (size < 0) return false;
    output.resize(static_cast<size_t>(size));
    if (size > 0) {
        env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte *>(output.data()));
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
    ~ByteScrubber() { std::fill(bytes.begin(), bytes.end(), static_cast<uint8_t>(0)); }
    std::vector<uint8_t> & bytes;
};

jbyteArray make_status_packet(JNIEnv * env, jbyte status) {
    jbyteArray packet = env->NewByteArray(1);
    if (packet != nullptr) env->SetByteArrayRegion(packet, 0, 1, &status);
    return packet;
}

jbyteArray make_embedding_packet(JNIEnv * env, const float * values) {
    if (values == nullptr) return make_status_packet(env, EMBED_PROVIDER_FAILED);
    jbyteArray packet = env->NewByteArray(static_cast<jsize>(EMBEDDING_PACKET_BYTES));
    if (packet == nullptr) return nullptr;
    env->SetByteArrayRegion(packet, 0, 1, &EMBED_OK);
    env->SetByteArrayRegion(
        packet,
        1,
        static_cast<jsize>(EMBEDDING_DIMENSION * sizeof(float)),
        reinterpret_cast<const jbyte *>(values)
    );
    return packet;
}

int64_t allocate_session_id() {
    while (true) {
        int64_t current = g_next_session_id.load(std::memory_order_acquire);
        if (current <= 0 || current == std::numeric_limits<int64_t>::max()) return 0;
        if (g_next_session_id.compare_exchange_weak(
                current,
                current + 1,
                std::memory_order_acq_rel,
                std::memory_order_acquire)) {
            return current;
        }
    }
}

bool normalize_embedding(const float * source, float * output) {
    if (source == nullptr || output == nullptr) return false;
    double norm_squared = 0.0;
    for (int32_t i = 0; i < EMBEDDING_DIMENSION; ++i) {
        if (!std::isfinite(source[i])) return false;
        norm_squared += static_cast<double>(source[i]) * static_cast<double>(source[i]);
    }
    if (!std::isfinite(norm_squared) || norm_squared <= 0.0) return false;
    const double inverse_norm = 1.0 / std::sqrt(norm_squared);
    for (int32_t i = 0; i < EMBEDDING_DIMENSION; ++i) {
        output[i] = static_cast<float>(static_cast<double>(source[i]) * inverse_norm);
        if (!std::isfinite(output[i])) return false;
    }
    return true;
}

}  // namespace

extern "C"
JNIEXPORT jint JNICALL
Java_pro_liliya_android_semanticprovider_SemanticNativeBridge_nativeLinkProbe(
    JNIEnv *, jobject
) {
    try {
        return llama_max_devices() > 0 ? LINK_PROBE_OK : LINK_PROBE_FAILED;
    } catch (...) {
        return LINK_PROBE_FAILED;
    }
}

extern "C"
JNIEXPORT jlong JNICALL
Java_pro_liliya_android_semanticprovider_SemanticNativeBridge_nativeLoad(
    JNIEnv * env,
    jobject,
    jbyteArray source_path_utf8,
    jint context_tokens,
    jint batch_tokens,
    jint thread_count,
    jint max_input_utf8_bytes,
    jboolean use_mmap
) {
    if (
        context_tokens <= 0 || context_tokens > MAX_CONTEXT_TOKENS ||
        batch_tokens <= 0 || batch_tokens > MAX_BATCH_TOKENS ||
        thread_count <= 0 || thread_count > MAX_THREAD_COUNT ||
        max_input_utf8_bytes <= 0 || max_input_utf8_bytes > MAX_INPUT_UTF8_BYTES ||
        batch_tokens > context_tokens
    ) {
        return LOAD_RESOURCE_REJECTED;
    }

    NativeLoadReservation load_reservation;
    try {
        if (!load_reservation.acquire()) {
            return LOAD_RESOURCE_REJECTED;
        }

        std::vector<uint8_t> path_bytes;
        if (!copy_bytes(env, source_path_utf8, path_bytes) || path_bytes.empty()) {
            return LOAD_REJECTED;
        }
        ByteScrubber path_scrubber(path_bytes);
        if (std::find(path_bytes.begin(), path_bytes.end(), static_cast<uint8_t>(0)) != path_bytes.end()) {
            return LOAD_REJECTED;
        }
        path_bytes.push_back(0);

        ensure_backend_initialized();

        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        model_params.load_mode = use_mmap == JNI_TRUE ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
        model_params.check_tensors = true;

        llama_model * raw_model = llama_model_load_from_file(
            reinterpret_cast<const char *>(path_bytes.data()),
            model_params
        );
        if (raw_model == nullptr) return LOAD_REJECTED;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(raw_model, llama_model_free);

        const bool has_encoder = llama_model_has_encoder(model.get());
        const bool has_decoder = llama_model_has_decoder(model.get());
        if (has_encoder == has_decoder) {
            return LOAD_UNSUPPORTED;
        }
        if (llama_model_n_embd_out(model.get()) != EMBEDDING_DIMENSION) {
            return LOAD_UNSUPPORTED;
        }

        const llama_vocab * vocab = llama_model_get_vocab(model.get());
        if (vocab == nullptr) return LOAD_UNSUPPORTED;

        llama_context_params context_params = llama_context_default_params();
        context_params.n_ctx = static_cast<uint32_t>(context_tokens);
        context_params.n_batch = static_cast<uint32_t>(batch_tokens);
        context_params.n_ubatch = static_cast<uint32_t>(batch_tokens);
        context_params.n_threads = thread_count;
        context_params.n_threads_batch = thread_count;
        context_params.pooling_type = LLAMA_POOLING_TYPE_MEAN;
        context_params.attention_type = LLAMA_ATTENTION_TYPE_NON_CAUSAL;
        context_params.embeddings = true;
        context_params.no_perf = true;

        llama_context * raw_context = llama_init_from_model(model.get(), context_params);
        if (raw_context == nullptr) return LOAD_REJECTED;
        std::unique_ptr<llama_context, decltype(&llama_free)> context(raw_context, llama_free);
        llama_set_n_threads(context.get(), thread_count, thread_count);

        if (llama_pooling_type(context.get()) != LLAMA_POOLING_TYPE_MEAN) {
            return LOAD_UNSUPPORTED;
        }

        auto session = std::make_unique<NativeSemanticSession>();
        session->model = model.release();
        session->context = context.release();
        session->vocab = vocab;
        session->context_tokens = context_tokens;
        session->batch_tokens = batch_tokens;
        session->max_input_utf8_bytes = max_input_utf8_bytes;
        session->use_encoder = has_encoder;

        const int64_t session_id = allocate_session_id();
        if (session_id <= 0) return LOAD_PROVIDER_FAILED;

        std::lock_guard<std::mutex> guard(g_registry_mutex);
        if (!g_load_in_progress || !g_sessions.empty()) {
            return LOAD_PROVIDER_FAILED;
        }
        const auto inserted = g_sessions.emplace(session_id, std::move(session));
        if (!inserted.second) {
            return LOAD_PROVIDER_FAILED;
        }
        g_load_in_progress = false;
        load_reservation.commit();
        return static_cast<jlong>(session_id);
    } catch (...) {
        return LOAD_PROVIDER_FAILED;
    }
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_pro_liliya_android_semanticprovider_SemanticNativeBridge_nativeEmbed(
    JNIEnv * env,
    jobject,
    jlong native_session_id,
    jbyteArray input_utf8
) {
    if (native_session_id <= 0) return make_status_packet(env, EMBED_STALE_SESSION);
    if (input_utf8 == nullptr) return make_status_packet(env, EMBED_REQUEST_REJECTED);

    try {
        NativeSemanticSession * session = nullptr;
        std::unique_lock<std::mutex> session_guard;
        {
            std::unique_lock<std::mutex> registry_guard(g_registry_mutex);
            auto it = g_sessions.find(static_cast<int64_t>(native_session_id));
            if (it == g_sessions.end() || it->second == nullptr) {
                return make_status_packet(env, EMBED_STALE_SESSION);
            }
            session = it->second.get();
            session_guard = std::unique_lock<std::mutex>(session->execution_mutex);
        }

        const jsize input_size = env->GetArrayLength(input_utf8);
        if (input_size <= 0) return make_status_packet(env, EMBED_REQUEST_REJECTED);
        if (input_size > session->max_input_utf8_bytes) {
            return make_status_packet(env, EMBED_RESOURCE_REJECTED);
        }

        std::vector<uint8_t> input;
        if (!copy_bytes(env, input_utf8, input)) {
            return make_status_packet(env, EMBED_PROVIDER_FAILED);
        }
        ByteScrubber input_scrubber(input);

        int32_t token_count = llama_tokenize(
            session->vocab,
            reinterpret_cast<const char *>(input.data()),
            static_cast<int32_t>(input.size()),
            nullptr,
            0,
            true,
            true
        );
        if (token_count == std::numeric_limits<int32_t>::min()) {
            return make_status_packet(env, EMBED_RESOURCE_REJECTED);
        }
        if (token_count < 0) token_count = -token_count;
        if (token_count <= 0) return make_status_packet(env, EMBED_REQUEST_REJECTED);
        if (token_count > session->batch_tokens || token_count > session->context_tokens) {
            return make_status_packet(env, EMBED_RESOURCE_REJECTED);
        }

        std::vector<llama_token> tokens(static_cast<size_t>(token_count));
        const int32_t actual = llama_tokenize(
            session->vocab,
            reinterpret_cast<const char *>(input.data()),
            static_cast<int32_t>(input.size()),
            tokens.data(),
            token_count,
            true,
            true
        );
        if (actual != token_count) return make_status_packet(env, EMBED_OPERATION_FAILED);

        llama_memory_clear(llama_get_memory(session->context), true);

        llama_batch batch = llama_batch_init(token_count, 0, 1);
        if (
            batch.token == nullptr || batch.pos == nullptr || batch.n_seq_id == nullptr ||
            batch.seq_id == nullptr || batch.logits == nullptr
        ) {
            llama_batch_free(batch);
            return make_status_packet(env, EMBED_PROVIDER_FAILED);
        }
        batch.n_tokens = token_count;
        for (int32_t index = 0; index < token_count; ++index) {
            batch.token[index] = tokens[static_cast<size_t>(index)];
            batch.pos[index] = index;
            batch.n_seq_id[index] = 1;
            batch.seq_id[index][0] = 0;
            batch.logits[index] = 1;
        }

        const int32_t execution_result = session->use_encoder
            ? llama_encode(session->context, batch)
            : llama_decode(session->context, batch);
        if (execution_result != 0) {
            llama_batch_free(batch);
            return make_status_packet(env, EMBED_OPERATION_FAILED);
        }

        const float * raw_embedding = llama_get_embeddings_seq(session->context, 0);
        if (raw_embedding == nullptr) {
            llama_batch_free(batch);
            return make_status_packet(env, EMBED_OPERATION_FAILED);
        }

        float normalized[EMBEDDING_DIMENSION];
        if (!normalize_embedding(raw_embedding, normalized)) {
            std::fill(std::begin(normalized), std::end(normalized), 0.0f);
            llama_batch_free(batch);
            return make_status_packet(env, EMBED_OPERATION_FAILED);
        }
        jbyteArray result = make_embedding_packet(env, normalized);
        std::fill(std::begin(normalized), std::end(normalized), 0.0f);
        llama_batch_free(batch);
        return result;
    } catch (...) {
        return make_status_packet(env, EMBED_PROVIDER_FAILED);
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_pro_liliya_android_semanticprovider_SemanticNativeBridge_nativeClose(
    JNIEnv *,
    jobject,
    jlong native_session_id
) {
    if (native_session_id <= 0) return CLOSE_FAILED;
    try {
        std::unique_ptr<NativeSemanticSession> removed;
        {
            std::unique_lock<std::mutex> registry_guard(g_registry_mutex);
            auto it = g_sessions.find(static_cast<int64_t>(native_session_id));
            if (it == g_sessions.end() || it->second == nullptr) return CLOSE_FAILED;
            std::unique_lock<std::mutex> session_guard(it->second->execution_mutex);

            // Keep the registry occupied until the physical context/model has been released.
            // This prevents a new semantic load from overlapping the closing session's resources.
            it->second->release();
            removed = std::move(it->second);
            g_sessions.erase(it);
        }
        removed.reset();
        return CLOSE_OK;
    } catch (...) {
        return CLOSE_PROVIDER_FAILED;
    }
}
