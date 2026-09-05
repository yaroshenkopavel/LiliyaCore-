package pro.liliya.android.semanticprovider

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryRecordId

/**
 * Controlled resource evidence for Offline Semantic Provider v0.1.
 *
 * GitHub-hosted CI currently executes Android x86_64 with native translation. Those measurements
 * are useful preflight evidence only. The canonical ARM64 memory/latency thresholds are enforced
 * automatically when this exact instrumentation test runs with arm64-v8a as the primary Android
 * runtime ABI. Merely advertising arm64-v8a as a translated secondary ABI is not ARM64 evidence.
 */
@RunWith(AndroidJUnit4::class)
class OfflineSemanticProviderResourceInstrumentedTest {

    @Test
    fun records_model_embedding_lifecycle_and_flat_index_resource_evidence() {
        withFixture { fixture, root, selection ->
            val validated = assertIs<SemanticModelArtifactValidationResult.Validated>(
                fixtureValidator(root, selection).validate(fixture, fixtureSpec(selection))
            ).artifact

            forceGc()
            val processPssBeforeLoadBytes = processPssBytes()
            val nativeHeapBeforeLoadBytes = Debug.getNativeHeapAllocatedSize()

            val loadStarted = SystemClock.elapsedRealtimeNanos()
            val session = assertIs<SemanticEmbeddingSessionLoadResult.Loaded>(
                SemanticEmbeddingSessionLoader(testPolicy()).load(validated)
            ).session
            val loadLatencyMs = elapsedMillis(loadStarted)

            forceGc()
            val processPssAfterLoadBytes = processPssBytes()
            val nativeHeapAfterLoadBytes = Debug.getNativeHeapAllocatedSize()
            val processPssLoadDeltaBytes = processPssAfterLoadBytes - processPssBeforeLoadBytes
            val nativeHeapLoadDeltaBytes = nativeHeapAfterLoadBytes - nativeHeapBeforeLoadBytes
            val evidence = linkedMapOf<String, String>()

            try {
                repeat(3) {
                    assertNormalized(embedded(session, SHORT_QUERY))
                }

                val warmLatenciesMs = LongArray(5) {
                    val started = SystemClock.elapsedRealtimeNanos()
                    assertNormalized(embedded(session, SHORT_QUERY))
                    elapsedMillis(started)
                }
                val warmShortQueryMedianMs = median(warmLatenciesMs)

                val paragraphStarted = SystemClock.elapsedRealtimeNanos()
                assertNormalized(embedded(session, PARAGRAPH_QUERY))
                val paragraphLatencyMs = elapsedMillis(paragraphStarted)

                forceGc()
                val repeatedProcessPssBeforeBytes = processPssBytes()
                val repeatedNativeHeapBeforeBytes = Debug.getNativeHeapAllocatedSize()
                repeat(12) {
                    assertNormalized(embedded(session, SHORT_QUERY))
                }
                forceGc()
                val repeatedProcessPssAfterBytes = processPssBytes()
                val repeatedNativeHeapAfterBytes = Debug.getNativeHeapAllocatedSize()
                val repeatedProcessPssDeltaBytes =
                    repeatedProcessPssAfterBytes - repeatedProcessPssBeforeBytes
                val repeatedNativeHeapDeltaBytes =
                    repeatedNativeHeapAfterBytes - repeatedNativeHeapBeforeBytes

                val flatIndexEvidence = measureFlatIndexEvidence()

                evidence.putAll(
                    mapOf(
                    "primaryAbi" to primaryRuntimeAbi(),
                    "abi" to Build.SUPPORTED_ABIS.joinToString(","),
                    "fixtureBytes" to selection.identity.expectedSizeBytes.toString(),
                    "fixtureSha256" to selection.identity.expectedSha256,
                    "fixtureAcceptance" to selection.acceptance.name,
                    "runtimeVersion" to SemanticModelProfileV01.ONNX_RUNTIME_VERSION,
                    "profileDimension" to SemanticEmbeddingVector.DIMENSION.toString(),
                    "loadLatencyMs" to loadLatencyMs.toString(),
                    "processPssBeforeLoadBytes" to processPssBeforeLoadBytes.toString(),
                    "processPssAfterLoadBytes" to processPssAfterLoadBytes.toString(),
                    "processPssLoadDeltaBytes" to processPssLoadDeltaBytes.toString(),
                    "nativeHeapLoadDeltaBytes" to nativeHeapLoadDeltaBytes.toString(),
                    "warmShortQueryMedianMs" to warmShortQueryMedianMs.toString(),
                    "paragraphLatencyMs" to paragraphLatencyMs.toString(),
                    "repeatedEmbeddingCount" to "12",
                    "repeatedProcessPssBeforeBytes" to repeatedProcessPssBeforeBytes.toString(),
                    "repeatedProcessPssAfterBytes" to repeatedProcessPssAfterBytes.toString(),
                    "repeatedProcessPssDeltaBytes" to repeatedProcessPssDeltaBytes.toString(),
                    "repeatedNativeHeapBeforeBytes" to repeatedNativeHeapBeforeBytes.toString(),
                    "repeatedNativeHeapAfterBytes" to repeatedNativeHeapAfterBytes.toString(),
                    "repeatedNativeHeapDeltaBytes" to repeatedNativeHeapDeltaBytes.toString(),
                    "flatScan1kMedianMs" to flatIndexEvidence.scan1kMedianMs.toString(),
                    "flatScan10kMedianMs" to flatIndexEvidence.scan10kMedianMs.toString(),
                    "flatIndex10kProcessPssDeltaBytes" to flatIndexEvidence.processPssDeltaBytes.toString(),
                    "flatIndex10kRawVectorBytes" to flatIndexEvidence.rawVectorBytes.toString(),
                    "flatIndexCandidateBound" to FLAT_INDEX_CANDIDATE_BOUND.toString(),
                    "arm64ThresholdsApplied" to isArm64Target().toString()
                    )
                )
                recordEvidence(evidence)

                if (isArm64Target()) {
                    assertTrue(
                        processPssLoadDeltaBytes <= MAX_MODEL_LOAD_MEMORY_DELTA_BYTES,
                        "ARM64 model-load process PSS delta exceeds canonical 512 MiB review threshold"
                    )
                    assertTrue(
                        warmShortQueryMedianMs <= MAX_WARM_QUERY_LATENCY_MS,
                        "ARM64 warm short-query embedding exceeds canonical 1500 ms review threshold"
                    )
                }
            } finally {
                assertIs<SemanticEmbeddingCloseResult.Closed>(session.close())
            }

            repeat(2) { cycle ->
                val reloadStarted = SystemClock.elapsedRealtimeNanos()
                val reloaded = assertIs<SemanticEmbeddingSessionLoadResult.Loaded>(
                    SemanticEmbeddingSessionLoader(testPolicy()).load(validated)
                ).session
                val reloadLatencyMs = elapsedMillis(reloadStarted)
                try {
                    assertNormalized(embedded(reloaded, SHORT_QUERY))
                    val key = "reloadCycle${cycle + 1}LatencyMs"
                    evidence[key] = reloadLatencyMs.toString()
                    recordEvidence(mapOf(key to reloadLatencyMs.toString()))
                } finally {
                    assertIs<SemanticEmbeddingCloseResult.Closed>(reloaded.close())
                    assertIs<SemanticEmbeddingCloseResult.StaleOrAlreadyClosed>(reloaded.close())
                }
            }

            writeEvidenceFile(evidence)
        }
    }

    private fun measureFlatIndexEvidence(): FlatIndexEvidence {
        forceGc()
        val pssBefore = processPssBytes()
        val index = SemanticFlatIndex(SemanticProfileGeneration(1))
        val query = SemanticEmbeddingVector(basis(0))

        for (entry in 1..1_000) {
            assertIs<SemanticIndexAddResult.Indexed>(
                index.addExact(memorySource(entry), SemanticEmbeddingVector(basis(entry % 16)))
            )
        }
        assertEquals(1_000, index.size(SemanticIndexDomain.MEMORY))
        val scan1kMedianMs = measureScanMedian(index, query)

        for (entry in 1_001..10_000) {
            assertIs<SemanticIndexAddResult.Indexed>(
                index.addExact(memorySource(entry), SemanticEmbeddingVector(basis(entry % 16)))
            )
        }
        assertEquals(10_000, index.size(SemanticIndexDomain.MEMORY))
        val scan10kMedianMs = measureScanMedian(index, query)

        forceGc()
        val pssAfter = processPssBytes()
        val expectedRawVectorBytes =
            10_000L * SemanticEmbeddingVector.DIMENSION.toLong() * Float.SIZE_BYTES.toLong()

        return FlatIndexEvidence(
            scan1kMedianMs = scan1kMedianMs,
            scan10kMedianMs = scan10kMedianMs,
            processPssDeltaBytes = pssAfter - pssBefore,
            rawVectorBytes = expectedRawVectorBytes
        )
    }

    private fun measureScanMedian(
        index: SemanticFlatIndex,
        query: SemanticEmbeddingVector
    ): Long {
        index.rank(SemanticIndexDomain.MEMORY, query, FLAT_INDEX_CANDIDATE_BOUND)
        val samples = LongArray(7) {
            val started = SystemClock.elapsedRealtimeNanos()
            val ranked = index.rank(
                SemanticIndexDomain.MEMORY,
                query,
                FLAT_INDEX_CANDIDATE_BOUND
            )
            assertEquals(FLAT_INDEX_CANDIDATE_BOUND, ranked.size)
            elapsedMillis(started)
        }
        return median(samples)
    }

    private fun memorySource(entry: Int): SemanticIndexSourceReference.Memory =
        SemanticIndexSourceReference.Memory(
            id = MemoryRecordId("resource-memory-$entry"),
            generation = MemoryGeneration(entry.toLong())
        )

    private fun embedded(
        session: SemanticEmbeddingSessionOwnership,
        text: String
    ): SemanticEmbeddingVector =
        assertIs<SemanticEmbeddingResult.Embedded>(session.embed(text)).vector

    private fun assertNormalized(vector: SemanticEmbeddingVector) {
        var normSquared = 0.0
        vector.copyValues().forEach { component ->
            assertTrue(component.isFinite())
            normSquared += component.toDouble() * component.toDouble()
        }
        assertTrue(abs(normSquared - 1.0) <= 0.001)
    }

    private fun processPssBytes(): Long {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss.toLong() * 1024L
    }

    private fun forceGc() {
        repeat(2) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            Thread.sleep(50)
        }
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000L

    private fun median(values: LongArray): Long {
        val sorted = values.sortedArray()
        return sorted[sorted.size / 2]
    }

    private fun recordEvidence(values: Map<String, String>) {
        val bundle = Bundle()
        for ((key, value) in values) {
            bundle.putString("semanticResource.$key", value)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, bundle)
    }

    private fun writeEvidenceFile(values: Map<String, String>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val target = File(targetContext.filesDir, RESOURCE_EVIDENCE_FILE_NAME)
        target.parentFile?.mkdirs()
        val json = JSONObject()
        values.forEach { (key, value) -> json.put(key, value) }
        val rendered = json.toString(2) + "\n"
        target.writeText(rendered, Charsets.UTF_8)
    }

    private fun primaryRuntimeAbi(): String =
        Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

    private fun isArm64Target(): Boolean =
        primaryRuntimeAbi() == "arm64-v8a"

    private fun withFixture(
        block: (
            File,
            File,
            SelfReproducedSemanticModelFixture.Selection
        ) -> Unit
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val selection = SelfReproducedSemanticModelFixture.select(
            InstrumentationRegistry.getArguments()
        )
        val targetContext = instrumentation.targetContext
        val testContext = instrumentation.context
        val root = File(targetContext.filesDir, "offline-semantic-resource-test")
        root.deleteRecursively()
        check(root.mkdirs())
        val fixture = File(root, selection.identity.modelFileName)
        val tokenizerFixture = File(root, selection.identity.tokenizerFileName)
        try {
            testContext.assets.open(selection.identity.modelFileName).use { input ->
                fixture.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
            testContext.assets.open(selection.identity.tokenizerFileName).use { input ->
                tokenizerFixture.outputStream().use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                }
            }
            assertEquals(selection.identity.expectedSizeBytes, fixture.length())
            assertEquals(
                selection.identity.tokenizerExpectedSizeBytes,
                tokenizerFixture.length()
            )
            block(fixture, root, selection)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun fixtureSpec(
        selection: SelfReproducedSemanticModelFixture.Selection
    ): SemanticModelArtifactSpec = SemanticModelArtifactSpec(selection.identity)

    private fun fixtureValidator(
        root: File,
        selection: SelfReproducedSemanticModelFixture.Selection
    ) = SemanticModelArtifactValidator(root, selection.identity, selection.acceptance)

    private fun testPolicy() = SemanticEmbeddingPolicy(
        contextTokens = 512,
        batchTokens = 512,
        threadCount = 1,
        maxInputUtf8Bytes = SemanticTextProfile.MAX_PREPARED_UTF8_BYTES
    )

    private fun basis(index: Int): FloatArray =
        FloatArray(SemanticEmbeddingVector.DIMENSION).also { it[index] = 1f }

    private data class FlatIndexEvidence(
        val scan1kMedianMs: Long,
        val scan10kMedianMs: Long,
        val processPssDeltaBytes: Long,
        val rawVectorBytes: Long
    )

    private companion object {
        const val RESOURCE_EVIDENCE_FILE_NAME = "semantic-resource-evidence.json"
        const val RESOURCE_EVIDENCE_SHELL_PATH = "/data/local/tmp/semantic-resource-evidence.json"
        const val FLAT_INDEX_CANDIDATE_BOUND = 8
        const val MAX_MODEL_LOAD_MEMORY_DELTA_BYTES = 512L * 1024L * 1024L
        const val MAX_WARM_QUERY_LATENCY_MS = 1_500L
        const val SHORT_QUERY = "query: where did I leave my keys?"
        const val PARAGRAPH_QUERY =
            "query: Find the note describing where the apartment keys were left after dinner and why they were moved there."
    }
}
