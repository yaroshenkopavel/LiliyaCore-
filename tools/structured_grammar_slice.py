from pathlib import Path


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    p = Path(path)
    text = p.read_text()
    actual = text.count(old)
    if actual != count:
        raise SystemExit(f"{path}: expected {count} occurrences, found {actual}")
    p.write_text(text.replace(old, new))


replace(
    "core/src/main/kotlin/pro/liliya/core/modelengine/ModelEngineContracts.kt",
    '''    override fun toString(): String =
        "ModelEngineInferenceRequest(" +
            "prompt=<redacted:${prompt.length}>, " +
            "maxOutputChars=$maxOutputChars, " +
            "grammar=${grammar?.let { "<redacted:${it.length}>" } ?: "<none>"})"
''',
    '''    override fun toString(): String =
        "ModelEngineInferenceRequest(prompt=<redacted:${prompt.length}>, maxOutputChars=$maxOutputChars)"
''',
)

replace(
    "core/src/main/kotlin/pro/liliya/core/cognitive/CognitiveModelRequestCompiler.kt",
    '''    override fun toString(): String =
        "CognitiveCompiledModelRequest(" +
            "prompt=<redacted:${prompt.length}>, " +
            "grammar=${grammar?.let { "<redacted:${it.length}>" } ?: "<none>"})"
''',
    '''    override fun toString(): String =
        "CognitiveCompiledModelRequest(prompt=<redacted:${prompt.length}>)"
''',
)

replace(
    "core/src/main/kotlin/pro/liliya/core/cognitive/CognitiveModelRuntimeComposition.kt",
    '''                ModelEngineInferenceRequest(
                    prompt = compiledRequest.prompt,
                    maxOutputChars = request.maxOutputChars
                )
''',
    '''                ModelEngineInferenceRequest(
                    prompt = compiledRequest.prompt,
                    maxOutputChars = request.maxOutputChars,
                    grammar = compiledRequest.grammar
                )
''',
)
replace(
    "core/src/main/kotlin/pro/liliya/core/cognitive/CognitiveModelRuntimeComposition.kt",
    '''                request = ModelEngineInferenceRequest(
                    prompt = compiledRequest.prompt,
                    maxOutputChars = request.maxOutputChars
                ),
''',
    '''                request = ModelEngineInferenceRequest(
                    prompt = compiledRequest.prompt,
                    maxOutputChars = request.maxOutputChars,
                    grammar = compiledRequest.grammar
                ),
''',
)

replace(
    "android-llama-cpp-engine/src/main/kotlin/pro/liliya/android/llamacppengine/LlamaCppNativeBridge.kt",
    '''        nativeSessionId: Long,
        promptUtf8: ByteArray,
        maxOutputChars: Int
''',
    '''        nativeSessionId: Long,
        promptUtf8: ByteArray,
        grammarUtf8: ByteArray?,
        maxOutputChars: Int
''',
)
replace(
    "android-llama-cpp-engine/src/main/kotlin/pro/liliya/android/llamacppengine/LlamaCppNativeBridge.kt",
    '''        nativeSessionId: Long,
        promptUtf8: ByteArray,
        maxOutputChars: Int,
        sink: LlamaCppNativeStreamingSink
''',
    '''        nativeSessionId: Long,
        promptUtf8: ByteArray,
        grammarUtf8: ByteArray?,
        maxOutputChars: Int,
        sink: LlamaCppNativeStreamingSink
''',
)

loader = "android-llama-cpp-engine/src/main/kotlin/pro/liliya/android/llamacppengine/AndroidLlamaCppPhysicalEngineLoader.kt"
replace(
    loader,
    '''    fun infer(
        nativeSessionId: Long,
        promptUtf8: ByteArray,
        maxOutputChars: Int
    ): LlamaCppNativeInferenceResult

    fun stream(
''',
    '''    fun infer(
        nativeSessionId: Long,
        promptUtf8: ByteArray,
        maxOutputChars: Int
    ): LlamaCppNativeInferenceResult

    fun inferConstrained(
        nativeSessionId: Long,
        promptUtf8: ByteArray,
        grammarUtf8: ByteArray,
        maxOutputChars: Int
    ): LlamaCppNativeInferenceResult = infer(nativeSessionId, promptUtf8, maxOutputChars)

    fun stream(
''',
)
replace(
    loader,
    '''    fun close(nativeSessionId: Long): LlamaCppNativeCloseResult
}
''',
    '''    fun streamConstrained(
        nativeSessionId: Long,
        promptUtf8: ByteArray,
        grammarUtf8: ByteArray,
        maxOutputChars: Int,
        sink: LlamaCppNativeStreamingSink
    ): LlamaCppNativeInferenceResult = stream(nativeSessionId, promptUtf8, maxOutputChars, sink)

    fun close(nativeSessionId: Long): LlamaCppNativeCloseResult
}
''',
)
replace(
    loader,
    '''    override fun infer(
        nativeSessionId: Long,
        promptUtf8: ByteArray,
        maxOutputChars: Int
    ): LlamaCppNativeInferenceResult =
        decodeInferencePacket(
            LlamaCppNativeBridge.nativeInfer(
                nativeSessionId = nativeSessionId,
                promptUtf8 = promptUtf8,
                maxOutputChars = maxOutputChars
            )
        )

    override fun stream(
''',
    '''    override fun infer(
        nativeSessionId: Long,
        promptUtf8: ByteArray,
        maxOutputChars: Int
    ): LlamaCppNativeInferenceResult = inferPacket(
        nativeSessionId = nativeSessionId,
        promptUtf8 = promptUtf8,
        grammarUtf8 = null,
        maxOutputChars = maxOutputChars
    )

    override fun inferConstrained(
        nativeSessionId: Long,
        promptUtf8: ByteArray,
        grammarUtf8: ByteArray,
        maxOutputChars: Int
    ): LlamaCppNativeInferenceResult = inferPacket(
        nativeSessionId = nativeSessionId,
        promptUtf8 = promptUtf8,
        grammarUtf8 = grammarUtf8,
        maxOutputChars = maxOutputChars
    )

    private fun inferPacket(
        nativeSessionId: Long,
        promptUtf8: ByteArray,
        grammarUtf8: ByteArray?,
        maxOutputChars: Int
    ): LlamaCppNativeInferenceResult =
        decodeInferencePacket(
            LlamaCppNativeBridge.nativeInfer(
                nativeSessionId = nativeSessionId,
                promptUtf8 = promptUtf8,
                grammarUtf8 = grammarUtf8,
                maxOutputChars = maxOutputChars
            )
        )

    override fun stream(
''',
)
replace(
    loader,
    '''    override fun stream(
        nativeSessionId: Long,
        promptUtf8: ByteArray,
        maxOutputChars: Int,
        sink: LlamaCppNativeStreamingSink
    ): LlamaCppNativeInferenceResult =
        decodeInferencePacket(
            LlamaCppNativeBridge.nativeInferStreaming(
                nativeSessionId = nativeSessionId,
                promptUtf8 = promptUtf8,
                maxOutputChars = maxOutputChars,
                sink = sink
            )
        )

    private fun decodeInferencePacket''',
    '''    override fun stream(
        nativeSessionId: Long,
        promptUtf8: ByteArray,
        maxOutputChars: Int,
        sink: LlamaCppNativeStreamingSink
    ): LlamaCppNativeInferenceResult = streamPacket(
        nativeSessionId = nativeSessionId,
        promptUtf8 = promptUtf8,
        grammarUtf8 = null,
        maxOutputChars = maxOutputChars,
        sink = sink
    )

    override fun streamConstrained(
        nativeSessionId: Long,
        promptUtf8: ByteArray,
        grammarUtf8: ByteArray,
        maxOutputChars: Int,
        sink: LlamaCppNativeStreamingSink
    ): LlamaCppNativeInferenceResult = streamPacket(
        nativeSessionId = nativeSessionId,
        promptUtf8 = promptUtf8,
        grammarUtf8 = grammarUtf8,
        maxOutputChars = maxOutputChars,
        sink = sink
    )

    private fun streamPacket(
        nativeSessionId: Long,
        promptUtf8: ByteArray,
        grammarUtf8: ByteArray?,
        maxOutputChars: Int,
        sink: LlamaCppNativeStreamingSink
    ): LlamaCppNativeInferenceResult =
        decodeInferencePacket(
            LlamaCppNativeBridge.nativeInferStreaming(
                nativeSessionId = nativeSessionId,
                promptUtf8 = promptUtf8,
                grammarUtf8 = grammarUtf8,
                maxOutputChars = maxOutputChars,
                sink = sink
            )
        )

    private fun decodeInferencePacket''',
)

session_path = Path("android-llama-cpp-engine/src/main/kotlin/pro/liliya/android/llamacppengine/LlamaCppSessionOwnership.kt")
text = session_path.read_text()
anchor = 'private val LLAMA_CPP_BACKEND_ID = ModelEngineBackendId("llama.cpp-v0.1")\n'
if text.count(anchor) != 1:
    raise SystemExit("session constant anchor failed")
text = text.replace(anchor, anchor + 'private const val MAX_LLAMA_CPP_GRAMMAR_UTF8_BYTES = 64 * 1024\n', 1)

old = '''            operationInProgress = true
            val result = try {
                nativePort.infer(
                    nativeSessionId = nativeSessionId,
                    promptUtf8 = promptUtf8,
                    maxOutputChars = request.maxOutputChars
                )
            } catch (_: Throwable) {
                LlamaCppNativeInferenceResult.Rejected(ModelEngineInferenceFailure.PROVIDER_FAILED)
            } finally {
                operationInProgress = false
                promptUtf8.fill(0)
            }
'''
new = '''            val grammarUtf8 = encodeGrammar(request.grammar)
                ?: if (request.grammar == null) null else {
                    promptUtf8.fill(0)
                    return@withLock ModelEngineInferenceResult.Rejected(
                        ModelEngineInferenceFailure.RESOURCE_LIMIT_REJECTED
                    )
                }

            operationInProgress = true
            val result = try {
                if (grammarUtf8 == null) {
                    nativePort.infer(
                        nativeSessionId = nativeSessionId,
                        promptUtf8 = promptUtf8,
                        maxOutputChars = request.maxOutputChars
                    )
                } else {
                    nativePort.inferConstrained(
                        nativeSessionId = nativeSessionId,
                        promptUtf8 = promptUtf8,
                        grammarUtf8 = grammarUtf8,
                        maxOutputChars = request.maxOutputChars
                    )
                }
            } catch (_: Throwable) {
                LlamaCppNativeInferenceResult.Rejected(ModelEngineInferenceFailure.PROVIDER_FAILED)
            } finally {
                operationInProgress = false
                promptUtf8.fill(0)
                grammarUtf8?.fill(0)
            }
'''
if text.count(old) != 1:
    raise SystemExit("session infer anchor failed")
text = text.replace(old, new, 1)

old = '''        var sequence = 0L
        var stopped = false
        var contractViolation = false
        val collected = StringBuilder()
        operationInProgress = true
        val result = try {
            nativePort.stream(
                nativeSessionId = nativeSessionId,
                promptUtf8 = promptUtf8,
                maxOutputChars = request.maxOutputChars,
                sink = LlamaCppNativeStreamingSink { chunkUtf8 ->
'''
new = '''        val grammarUtf8 = encodeGrammar(request.grammar)
            ?: if (request.grammar == null) null else {
                promptUtf8.fill(0)
                return@withLock ModelEngineInferenceResult.Rejected(
                    ModelEngineInferenceFailure.RESOURCE_LIMIT_REJECTED
                )
            }

        var sequence = 0L
        var stopped = false
        var contractViolation = false
        val collected = StringBuilder()
        operationInProgress = true
        val nativeSink = LlamaCppNativeStreamingSink { chunkUtf8 ->
'''
if text.count(old) != 1:
    raise SystemExit("session stream start anchor failed")
text = text.replace(old, new, 1)

old = '''                }
            )
        } catch (_: Throwable) {
            LlamaCppNativeInferenceResult.Rejected(ModelEngineInferenceFailure.PROVIDER_FAILED)
        } finally {
            operationInProgress = false
            promptUtf8.fill(0)
        }

        if (contractViolation) {
'''
new = '''                }
        val result = try {
            if (grammarUtf8 == null) {
                nativePort.stream(
                    nativeSessionId = nativeSessionId,
                    promptUtf8 = promptUtf8,
                    maxOutputChars = request.maxOutputChars,
                    sink = nativeSink
                )
            } else {
                nativePort.streamConstrained(
                    nativeSessionId = nativeSessionId,
                    promptUtf8 = promptUtf8,
                    grammarUtf8 = grammarUtf8,
                    maxOutputChars = request.maxOutputChars,
                    sink = nativeSink
                )
            }
        } catch (_: Throwable) {
            LlamaCppNativeInferenceResult.Rejected(ModelEngineInferenceFailure.PROVIDER_FAILED)
        } finally {
            operationInProgress = false
            promptUtf8.fill(0)
            grammarUtf8?.fill(0)
        }

        if (contractViolation) {
'''
if text.count(old) != 1:
    raise SystemExit(f"session stream end anchor failed: {text.count(old)}")
text = text.replace(old, new, 1)

anchor = '''    override fun close(): ModelEngineCloseResult = executionLock.withLock {
'''
helper = '''    private fun encodeGrammar(grammar: String?): ByteArray? {
        if (grammar == null) return null
        val encoded = try {
            grammar.toByteArray(Charsets.UTF_8)
        } catch (_: Throwable) {
            return null
        }
        if (encoded.isEmpty() || encoded.size > MAX_LLAMA_CPP_GRAMMAR_UTF8_BYTES) {
            encoded.fill(0)
            return null
        }
        return encoded
    }

'''
if text.count(anchor) != 1:
    raise SystemExit("session helper anchor failed")
text = text.replace(anchor, helper + anchor, 1)
session_path.write_text(text)

cpp_path = Path("android-llama-cpp-engine/src/main/cpp/liliya_llama_jni.cpp")
text = cpp_path.read_text()
anchor = "constexpr jint CLOSE_PROVIDER_FAILED = 2;\n"
if text.count(anchor) != 1:
    raise SystemExit("native constant anchor failed")
text = text.replace(anchor, anchor + "\nconstexpr size_t MAX_GRAMMAR_UTF8_BYTES = 64U * 1024U;\n", 1)

helper_anchor = "int64_t allocate_native_session_id() {\n"
helper = r'''std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> create_sampler(
    const llama_vocab * vocab,
    const std::vector<uint8_t> & grammar_bytes
) {
    if (grammar_bytes.empty()) {
        return {llama_sampler_init_greedy(), llama_sampler_free};
    }
    if (grammar_bytes.size() > MAX_GRAMMAR_UTF8_BYTES) {
        return {nullptr, llama_sampler_free};
    }
    if (std::find(grammar_bytes.begin(), grammar_bytes.end(), static_cast<uint8_t>(0)) != grammar_bytes.end()) {
        return {nullptr, llama_sampler_free};
    }

    const std::string grammar(
        reinterpret_cast<const char *>(grammar_bytes.data()),
        grammar_bytes.size()
    );
    llama_sampler_chain_params params = llama_sampler_chain_default_params();
    params.no_perf = true;
    llama_sampler * chain = llama_sampler_chain_init(params);
    if (chain == nullptr) {
        return {nullptr, llama_sampler_free};
    }

    llama_sampler * grammar_sampler = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
    if (grammar_sampler == nullptr) {
        llama_sampler_free(chain);
        return {nullptr, llama_sampler_free};
    }
    llama_sampler_chain_add(chain, grammar_sampler);

    llama_sampler * greedy = llama_sampler_init_greedy();
    if (greedy == nullptr) {
        llama_sampler_free(chain);
        return {nullptr, llama_sampler_free};
    }
    llama_sampler_chain_add(chain, greedy);
    return {chain, llama_sampler_free};
}

'''
if text.count(helper_anchor) != 1:
    raise SystemExit("native helper anchor failed")
text = text.replace(helper_anchor, helper + helper_anchor, 1)

replace_sig = [
    (
        '''    jlong native_session_id,
    jbyteArray prompt_utf8,
    jint max_output_chars
) {
''',
        '''    jlong native_session_id,
    jbyteArray prompt_utf8,
    jbyteArray grammar_utf8,
    jint max_output_chars
) {
''',
    ),
    (
        '''    jlong native_session_id,
    jbyteArray prompt_utf8,
    jint max_output_chars,
    jobject sink
) {
''',
        '''    jlong native_session_id,
    jbyteArray prompt_utf8,
    jbyteArray grammar_utf8,
    jint max_output_chars,
    jobject sink
) {
''',
    ),
]
for old, new in replace_sig:
    if text.count(old) != 1:
        raise SystemExit("native JNI signature anchor failed")
    text = text.replace(old, new, 1)

anchor = '''        ByteScrubber prompt_scrubber(prompt);

        try {
'''
insert = '''        ByteScrubber prompt_scrubber(prompt);

        std::vector<uint8_t> grammar;
        if (grammar_utf8 != nullptr) {
            if (!copy_bytes(env, grammar_utf8, grammar)) {
                return make_infer_packet(env, INFER_PROVIDER_FAILED);
            }
            if (grammar.empty() || grammar.size() > MAX_GRAMMAR_UTF8_BYTES) {
                return make_infer_packet(env, INFER_RESOURCE_REJECTED);
            }
        }
        ByteScrubber grammar_scrubber(grammar);

        try {
'''
if text.count(anchor) != 2:
    raise SystemExit(f"native grammar copy anchor count={text.count(anchor)}")
text = text.replace(anchor, insert)

old = '''            std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
                llama_sampler_init_greedy(),
                llama_sampler_free
            );
'''
new = '''            std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler =
                create_sampler(session->vocab, grammar);
'''
if text.count(old) != 2:
    raise SystemExit(f"native sampler anchor count={text.count(old)}")
text = text.replace(old, new)
cpp_path.write_text(text)

Path("core/src/test/kotlin/pro/liliya/core/cognitive/CognitiveStructuredResponseGrammarContractTest.kt").write_text(r'''package pro.liliya.core.cognitive

import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class CognitiveStructuredResponseGrammarContractTest {
    @Test
    fun grammar_projects_exact_count_and_selected_index_bounds() {
        val budgets = CognitiveStructuredResponseBudgets(
            maxOutputChars = 768,
            maxPlanningGoalChars = 64,
            maxPlanningSteps = 2,
            maxPlanningStepChars = 64,
            maxReasoningPremises = 2,
            maxReasoningPremiseChars = 64,
            maxReasoningAnalysisChars = 64,
            maxReasoningConclusionChars = 64,
            maxDecisionOptions = 3,
            maxDecisionOptionChars = 64,
            maxDecisionRationaleChars = 64,
            maxResultChars = 64,
            maxReflectionChars = 64,
            maxLearningProposalChars = 64
        )
        val grammar = assertNotNull(CognitiveStructuredResponseGrammar.compile(budgets))
        assertContains(grammar, "planning-count-2 ::= \"PLANNING_STEP_COUNT=2\\n\" planning-step-line{2}")
        assertContains(grammar, "reasoning-count-2 ::= \"REASONING_PREMISE_COUNT=2\\n\" reasoning-premise-line{2}")
        assertContains(grammar, "decision-option-line{3} \"DECISION_SELECTED_INDEX=\" (\"0\" | \"1\" | \"2\")")
        assertContains(grammar, "planning-goal ::= value-start value-char{0,63}")
        assertTrue(grammar.length <= 64 * 1024)
    }

    @Test
    fun grammar_rejects_unbounded_projection_before_engine_boundary() {
        val budgets = CognitiveStructuredResponseBudgets(
            maxOutputChars = 1_000_000,
            maxPlanningGoalChars = 1,
            maxPlanningSteps = 20_000,
            maxPlanningStepChars = 1,
            maxReasoningPremises = 1,
            maxReasoningPremiseChars = 1,
            maxReasoningAnalysisChars = 1,
            maxReasoningConclusionChars = 1,
            maxDecisionOptions = 1,
            maxDecisionOptionChars = 1,
            maxDecisionRationaleChars = 1,
            maxResultChars = 1,
            maxReflectionChars = 1,
            maxLearningProposalChars = 1
        )
        assertTrue(CognitiveStructuredResponseGrammar.compile(budgets) == null)
    }
}
''')

Path("android-llama-cpp-engine/src/test/kotlin/pro/liliya/android/llamacppengine/LlamaCppGrammarPropagationContractTest.kt").write_text(r'''package pro.liliya.android.llamacppengine

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.core.modelengine.ModelEngineHandleId
import pro.liliya.core.modelengine.ModelEngineInferenceRequest
import pro.liliya.core.modelengine.ModelEngineInferenceResult
import pro.liliya.core.modelengine.ModelEngineLoadFailure

class LlamaCppGrammarPropagationContractTest {
    @Test
    fun constrained_request_reaches_native_capability() {
        val native = CapturingNativePort()
        val session = LlamaCppSessionOwnership(
            handleId = ModelEngineHandleId("grammar-session"),
            nativeSessionId = 7L,
            nativePort = native,
            policy = policy()
        )
        val grammar = "root ::= \"OK\""
        val result = assertIs<ModelEngineInferenceResult.Succeeded>(
            session.infer(
                ModelEngineInferenceRequest(
                    prompt = "prompt",
                    maxOutputChars = 8,
                    grammar = grammar
                )
            )
        )
        assertEquals("OK", result.output)
        assertContentEquals(grammar.encodeToByteArray(), native.capturedGrammar)
    }

    private fun policy() = LlamaCppEnginePolicy(
        contextTokens = 32,
        maxPromptTokens = 16,
        maxGeneratedTokens = 16,
        batchTokens = 16,
        microBatchTokens = 8,
        threadCount = 2,
        maxPromptChars = 32,
        maxPromptUtf8Bytes = 64,
        maxOutputChars = 16,
        maxOutputUtf8Bytes = 64,
        useMmap = true
    )

    private class CapturingNativePort : LlamaCppNativeSessionPort {
        var capturedGrammar: ByteArray = byteArrayOf()

        override fun load(sourcePath: String, policy: LlamaCppEnginePolicy) =
            LlamaCppNativeLoadResult.Rejected(ModelEngineLoadFailure.LOAD_REJECTED)

        override fun infer(
            nativeSessionId: Long,
            promptUtf8: ByteArray,
            maxOutputChars: Int
        ) = LlamaCppNativeInferenceResult.Succeeded("unconstrained")

        override fun inferConstrained(
            nativeSessionId: Long,
            promptUtf8: ByteArray,
            grammarUtf8: ByteArray,
            maxOutputChars: Int
        ): LlamaCppNativeInferenceResult {
            capturedGrammar = grammarUtf8.copyOf()
            return LlamaCppNativeInferenceResult.Succeeded("OK")
        }

        override fun close(nativeSessionId: Long) = LlamaCppNativeCloseResult.Closed
    }
}
''')
