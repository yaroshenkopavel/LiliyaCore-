package pro.liliya.android.semanticprovider

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.llamacppengine.AndroidLlamaCppCognitiveModelAssembly
import pro.liliya.android.llamacppengine.LlamaCppEnginePolicy
import pro.liliya.android.llamacppengine.LlamaCppPromptFormatPolicy
import pro.liliya.android.protectedmodel.staging.AndroidProtectedModelStagingPolicy
import pro.liliya.core.cognitive.CognitiveCompiledModelRequest
import pro.liliya.core.cognitive.CognitiveContextItem
import pro.liliya.core.cognitive.CognitiveContextSnapshot
import pro.liliya.core.cognitive.CognitiveContextSourceReference
import pro.liliya.core.cognitive.CognitiveConversationRole
import pro.liliya.core.cognitive.CognitiveConversationSequence
import pro.liliya.core.cognitive.CognitiveConversationSessionId
import pro.liliya.core.cognitive.CognitiveInferenceRequest
import pro.liliya.core.cognitive.CognitiveInferenceResult
import pro.liliya.core.cognitive.CognitiveInput
import pro.liliya.core.cognitive.CognitiveModelActivationResult
import pro.liliya.core.cognitive.CognitiveModelQuiesceResult
import pro.liliya.core.cognitive.CognitiveModelRequestCompilerPort
import pro.liliya.core.cognitive.CognitiveModelRequestCompilerResult
import pro.liliya.core.cognitive.CognitiveModelRetirementResult
import pro.liliya.core.cognitive.CognitiveModelRuntimeSessionIdSource
import pro.liliya.core.cognitive.CognitiveRuntimeLimits
import pro.liliya.core.cognitive.CognitiveTurnGeneration
import pro.liliya.core.cognitive.CognitiveTurnId
import pro.liliya.core.cognitive.CognitiveTurnReference
import pro.liliya.core.cognitive.DeterministicCognitiveModelRequestCompiler
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.modelengine.ModelEngineLoadFailure
import pro.liliya.core.modelengine.ModelEngineLoadResult
import pro.liliya.core.modelengine.ModelEngineLoaderPort
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.protectedmodel.LargeProtectedModelPayloadProfile
import pro.liliya.core.protectedmodel.LargeProtectedModelStagedSourceOwnership
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAbortResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingAppendResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingCoordinator
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingPublishResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingRequest
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingRetireResult
import pro.liliya.core.protectedmodel.LargeProtectedModelStagingStartResult
import pro.liliya.core.protectedmodel.ProtectedModelAccessCoordinator
import pro.liliya.core.protectedmodel.ProtectedModelAccessPolicy
import pro.liliya.core.protectedmodel.ProtectedModelDekResolver
import pro.liliya.core.protectedmodel.ProtectedModelGeneration
import pro.liliya.core.protectedmodel.ProtectedModelPackageId
import pro.liliya.core.protectedmodel.ProtectedModelPackageVerifier
import pro.liliya.core.protectedmodel.ProtectedModelPayloadLoader
import pro.liliya.core.protectedmodel.ProtectedModelPolicyDecision
import pro.liliya.core.protectedmodel.ProtectedModelReference
import pro.liliya.core.protectedmodel.ProtectedModelRuntimeOwnership
import pro.liliya.core.protectedmodel.ProtectedModelSignerResolver
import pro.liliya.core.runtime.hardening.RuntimeModelSessionId

@RunWith(AndroidJUnit4::class)
class Qwen3RussianQualityContextCandidateInstrumentedTest {

    @Test
    fun records_russian_quality_and_production_compiler_context_behavior() =
        withCleanStaging { targetContext ->
            assertEquals("arm64-v8a", Build.SUPPORTED_ABIS.firstOrNull())

            val evidence = linkedMapOf(
                "evidenceClass" to "qwen3-russian-quality-context-candidate-v1",
                "primaryAbi" to Build.SUPPORTED_ABIS.first(),
                "generationModel" to QWEN_FILE_NAME,
                "generationModelBytes" to QWEN_BYTES.toString(),
                "generationModelSha256" to QWEN_SHA256,
                "generationModelRevision" to QWEN_REVISION,
                "generationQuantization" to "Q4_K_M",
                "promptFormatPolicy" to "MODEL_DEFAULT_CHAT_TEMPLATE",
                "measurementProfile" to QUALITY_PROFILE,
                "thinkingMode" to "no_think",
                "contextCompiler" to "DeterministicCognitiveModelRequestCompiler"
            )

            val model = ProtectedModelReference(
                packageId = ProtectedModelPackageId("qwen3-1.7b-q4km-quality-context-candidate"),
                generation = ProtectedModelGeneration(1)
            )
            val ownership = ProtectedModelRuntimeOwnership().also { it.replaceTarget(model) }
            val llama = llamaAssembly(targetContext, foundation(), ownership)

            val stagingStarted = SystemClock.elapsedRealtimeNanos()
            val staged = provisionExactCandidate(llama.stagingCoordinator, model)
            evidence["candidateProvisionAndStageMs"] = elapsedMillis(stagingStarted).toString()

            val activationStarted = SystemClock.elapsedRealtimeNanos()
            val activated = assertIs<CognitiveModelActivationResult.Activated>(
                llama.stagedActivation.activate(staged)
            )
            evidence["llamaActivationMs"] = elapsedMillis(activationStarted).toString()

            val arithmetic = runCase(
                llama,
                request(
                    "russian-arithmetic",
                    "Ответь по-русски одним коротким предложением. Сколько будет 17 + 25? /no_think"
                )
            )
            recordCase(evidence, "russianArithmetic", arithmetic)
            assertTrue(arithmetic.output.contains("42"), "Russian arithmetic answer must contain 42")
            assertTrue(containsCyrillic(arithmetic.output), "Russian arithmetic answer must contain Cyrillic")

            val capital = runCase(
                llama,
                request(
                    "russian-capital",
                    "Ответь по-русски одним коротким предложением. Назови столицу Украины. /no_think"
                )
            )
            recordCase(evidence, "russianCapital", capital)
            assertTrue(containsCyrillic(capital.output), "Russian quality answer must contain Cyrillic")
            assertTrue(
                capital.output.lowercase().contains("киев") || capital.output.lowercase().contains("київ"),
                "Russian quality answer must identify Kyiv"
            )

            val recallContext = listOf(
                conversationItem(
                    sequence = 1,
                    role = CognitiveConversationRole.USER,
                    content = "Контекстная проверка: секретный код пользователя — $RECALL_MARKER."
                )
            )
            val recall = runCase(
                llama,
                request(
                    id = "context-recall",
                    input = "Какой секретный код пользователя указан в контексте? Ответ должен содержать код. /no_think",
                    contextItems = recallContext
                )
            )
            recordCase(evidence, "contextRecall", recall)
            assertTrue(
                recall.output.contains(RECALL_MARKER),
                "Production-compiler context recall must contain the exact marker"
            )

            val orderedContext = listOf(
                conversationItem(
                    sequence = 1,
                    role = CognitiveConversationRole.USER,
                    content = "Старое значение контрольного кода: $OLD_MARKER."
                ),
                conversationItem(
                    sequence = 2,
                    role = CognitiveConversationRole.USER,
                    content = "Обновление: новое и актуальное значение контрольного кода: $LATEST_MARKER."
                )
            )
            val ordered = runCase(
                llama,
                request(
                    id = "context-order",
                    input = "Назови актуальное значение контрольного кода из последнего сообщения контекста. /no_think",
                    contextItems = orderedContext
                )
            )
            recordCase(evidence, "contextOrder", ordered)
            assertTrue(
                ordered.output.contains(LATEST_MARKER),
                "Production-compiler ordered context must surface the latest marker"
            )

            evidence["russianArithmeticPassed"] = "true"
            evidence["russianCapitalPassed"] = "true"
            evidence["contextRecallPassed"] = "true"
            evidence["contextOrderPassed"] = "true"

            assertIs<CognitiveModelQuiesceResult.Quiescing>(
                llama.cognitiveRuntime.beginQuiescing(activated.session)
            )
            assertIs<CognitiveModelRetirementResult.Retired>(
                llama.cognitiveRuntime.retireIfDrained(activated.session)
            )
            assertIs<LargeProtectedModelStagingRetireResult.Retired>(staged.retire())

            val json = JSONObject()
            evidence.forEach { (key, value) -> json.put(key, value) }
            println("QWEN3_RUSSIAN_CONTEXT_EVIDENCE=" + json.toString())
            recordEvidence(evidence)
        }

    private fun runCase(
        llama: AndroidLlamaCppCognitiveModelAssembly,
        request: CognitiveInferenceRequest
    ): CaseResult {
        val started = SystemClock.elapsedRealtimeNanos()
        val inference = assertIs<CognitiveInferenceResult.Succeeded>(
            llama.inferencePort.infer(request)
        )
        val elapsed = elapsedMillis(started)
        assertTrue(inference.output.isNotBlank(), "candidate inference output must not be blank")
        return CaseResult(inference.output, elapsed)
    }

    private fun recordCase(
        evidence: MutableMap<String, String>,
        prefix: String,
        result: CaseResult
    ) {
        evidence[prefix + "Ms"] = result.elapsedMs.toString()
        evidence[prefix + "Chars"] = result.output.length.toString()
        evidence[prefix + "OutputBase64"] = Base64.getEncoder().encodeToString(
            result.output.toByteArray(Charsets.UTF_8)
        )
    }

    private fun request(
        id: String,
        input: String,
        contextItems: List<CognitiveContextItem> = emptyList()
    ): CognitiveInferenceRequest {
        val turn = CognitiveTurnReference(
            id = CognitiveTurnId("qwen3-quality-context-$id"),
            generation = CognitiveTurnGeneration(1)
        )
        return CognitiveInferenceRequest(
            turn = turn,
            input = CognitiveInput(input),
            context = CognitiveContextSnapshot(turn, contextItems),
            maxOutputChars = QUALITY_MAX_OUTPUT_CHARS
        )
    }

    private fun conversationItem(
        sequence: Long,
        role: CognitiveConversationRole,
        content: String
    ): CognitiveContextItem = CognitiveContextItem(
        source = CognitiveContextSourceReference.Conversation(
            sessionId = CognitiveConversationSessionId(CONTEXT_SESSION_ID),
            sequence = CognitiveConversationSequence(sequence),
            role = role
        ),
        content = content
    )

    private fun llamaAssembly(
        context: Context,
        foundation: FoundationComposition,
        protectedOwnership: ProtectedModelRuntimeOwnership
    ): AndroidLlamaCppCognitiveModelAssembly {
        val ids = AtomicInteger(0)
        val protectedAccess = ProtectedModelAccessCoordinator(
            policy = ProtectedModelAccessPolicy { ProtectedModelPolicyDecision.Allowed },
            ownership = protectedOwnership,
            loader = ProtectedModelPayloadLoader(
                verifier = ProtectedModelPackageVerifier(ProtectedModelSignerResolver { _, _ -> null }),
                dekResolver = ProtectedModelDekResolver { _, _ -> null },
                maxPlaintextSizeBytes = 1L
            )
        )
        val legacyLoader = ModelEngineLoaderPort { _, _ ->
            ModelEngineLoadResult.Rejected(ModelEngineLoadFailure.LOAD_REJECTED)
        }
        val deterministicCompiler = DeterministicCognitiveModelRequestCompiler()
        val compiler = CognitiveModelRequestCompilerPort { compilerRequest ->
            if (compilerRequest.inference.context.items.isEmpty()) {
                CognitiveModelRequestCompilerResult.Compiled(
                    CognitiveCompiledModelRequest(compilerRequest.inference.input.text)
                )
            } else {
                deterministicCompiler.compile(compilerRequest)
            }
        }
        return AndroidLlamaCppCognitiveModelAssembly.create(
            context = context,
            stagingPolicy = AndroidProtectedModelStagingPolicy(freeSpaceReserveBytes = 0L),
            stagingBudgets = LargeProtectedModelStagingBudgets(
                maxTotalPlaintextBytes = QWEN_BYTES,
                maxSegmentPlaintextBytes = SEGMENT_BYTES.toLong(),
                maxSegmentCount = segmentCount(QWEN_BYTES),
                maxActiveAttempts = 1,
                maxOpaqueIdentifierChars = 64
            ),
            llamaPolicy = LlamaCppEnginePolicy(
                contextTokens = 2048,
                maxPromptTokens = 1536,
                maxGeneratedTokens = QUALITY_MAX_GENERATED_TOKENS,
                batchTokens = 256,
                microBatchTokens = 64,
                threadCount = 4,
                maxPromptChars = 8192,
                maxPromptUtf8Bytes = 32768,
                maxOutputChars = QUALITY_MAX_OUTPUT_CHARS,
                maxOutputUtf8Bytes = QUALITY_MAX_OUTPUT_CHARS * 4,
                useMmap = true,
                promptFormatPolicy = LlamaCppPromptFormatPolicy.MODEL_DEFAULT_CHAT_TEMPLATE
            ),
            foundation = foundation,
            protectedAccess = protectedAccess,
            legacyEngineLoader = legacyLoader,
            compiler = compiler,
            sessionIds = CognitiveModelRuntimeSessionIdSource {
                RuntimeModelSessionId("qwen3-quality-context-" + ids.incrementAndGet())
            },
            limits = CognitiveRuntimeLimits(
                maxInferenceOutputChars = QUALITY_MAX_OUTPUT_CHARS,
                maxModelPromptChars = 8192
            )
        )
    }

    private fun provisionExactCandidate(
        coordinator: LargeProtectedModelStagingCoordinator,
        model: ProtectedModelReference
    ): LargeProtectedModelStagedSourceOwnership {
        val expectedSegments = segmentCount(QWEN_BYTES)
        val started = assertIs<LargeProtectedModelStagingStartResult.Started>(
            coordinator.start(
                LargeProtectedModelStagingRequest(
                    model = model,
                    profile = LargeProtectedModelPayloadProfile.SEGMENTED_AES_256_GCM_SHA256_V1,
                    expectedPlaintextBytes = QWEN_BYTES,
                    expectedSegmentCount = expectedSegments
                )
            )
        )

        val session = started.session
        var published = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val connection = (URL(QWEN_URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                connection.connect()
                assertEquals(HttpURLConnection.HTTP_OK, connection.responseCode)
                if (connection.contentLengthLong > 0L) {
                    assertEquals(QWEN_BYTES, connection.contentLengthLong)
                }
                connection.inputStream.use { input ->
                    var segmentIndex = 0
                    while (total < QWEN_BYTES) {
                        val wanted = minOf(SEGMENT_BYTES.toLong(), QWEN_BYTES - total).toInt()
                        val segment = readExactSegment(input, wanted)
                        assertEquals(wanted, segment.size)
                        digest.update(segment)
                        assertIs<LargeProtectedModelStagingAppendResult.Appended>(
                            session.append(segmentIndex, segment)
                        )
                        total += segment.size.toLong()
                        segment.fill(0)
                        segmentIndex += 1
                    }
                    assertEquals(-1, input.read())
                }
            } finally {
                connection.disconnect()
            }

            assertEquals(QWEN_BYTES, total)
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualSha != QWEN_SHA256) {
                assertIs<LargeProtectedModelStagingAbortResult.Aborted>(session.abort())
                error("candidate GGUF SHA-256 mismatch")
            }

            val ownership = assertIs<LargeProtectedModelStagingPublishResult.Published>(
                session.sealAndPublish()
            ).ownership
            published = true
            return ownership
        } finally {
            if (!published && coordinator.currentAttempt() == session.attempt) {
                session.abort()
            }
        }
    }

    private fun foundation(): FoundationComposition {
        val logs = InMemoryLogWriter()
        val correlations = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { loggerContext -> StructuredLogger(loggerContext, logs) },
            correlationIds = CorrelationIdGenerator { "qwen3-quality-context-" + correlations.incrementAndGet() }
        )
    }

    private fun recordEvidence(values: Map<String, String>) {
        val bundle = Bundle()
        values.forEach { (key, value) -> bundle.putString("qwen3Quality." + key, value) }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, bundle)
    }

    private inline fun withCleanStaging(block: (Context) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val stagingRoot = File(context.filesDir, "large-protected-model-staging-v1")
        stagingRoot.deleteRecursively()
        try {
            block(context)
        } finally {
            stagingRoot.deleteRecursively()
        }
    }

    private fun readExactSegment(input: InputStream, wanted: Int): ByteArray {
        val buffer = ByteArray(wanted)
        var offset = 0
        while (offset < wanted) {
            val read = input.read(buffer, offset, wanted - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == buffer.size) buffer else buffer.copyOf(offset)
    }

    private fun segmentCount(totalBytes: Long): Int =
        ((totalBytes + SEGMENT_BYTES - 1L) / SEGMENT_BYTES).toInt()

    private fun elapsedMillis(startedNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000L

    private fun containsCyrillic(value: String): Boolean =
        value.any { it in '\u0400'..'\u04FF' }

    private data class CaseResult(
        val output: String,
        val elapsedMs: Long
    )

    private companion object {
        const val QWEN_FILE_NAME = "Qwen3-1.7B-Q4_K_M.gguf"
        const val QWEN_BYTES = 1_282_439_264L
        const val QWEN_SHA256 = "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5"
        const val QWEN_REVISION = "daeb8e2d528a760970442092f6bf1e55c3b659eb"
        const val QWEN_URL =
            "https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF/resolve/" +
                QWEN_REVISION + "/" + QWEN_FILE_NAME + "?download=true"

        const val SEGMENT_BYTES = 4 * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 120_000
        const val QUALITY_MAX_GENERATED_TOKENS = 256
        const val QUALITY_MAX_OUTPUT_CHARS = 2048
        const val QUALITY_PROFILE = "russian-context-v1-256t-2048c"
        const val CONTEXT_SESSION_ID = "qwen3-quality-context-session"
        const val RECALL_MARKER = "LILIYA-7391"
        const val OLD_MARKER = "STARYI-1111"
        const val LATEST_MARKER = "NOVYI-8427"
    }
}
