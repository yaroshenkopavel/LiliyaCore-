package pro.liliya.android.semanticprovider

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.max
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measurement-only physical ARM64 I9 benchmark.
 *
 * This test intentionally does not change the production SemanticEmbeddingPolicy default. It runs
 * the exact pinned tokenizer/encoder bundle with one caller-selected thread count in 1..4 and emits
 * directly comparable load, embedding and PSS evidence.
 */
@RunWith(AndroidJUnit4::class)
class OfflineSemanticProviderThreadCountBenchmarkInstrumentedTest {

    @Test
    fun records_exact_thread_count_physical_benchmark() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val threadCount = arguments.getString(THREAD_COUNT_ARGUMENT)
            ?.trim()
            ?.toIntOrNull()
            ?: error("$THREAD_COUNT_ARGUMENT must be supplied as an integer in 1..4")
        require(threadCount in 1..SemanticEmbeddingPolicy.MAX_THREAD_COUNT) {
            "$THREAD_COUNT_ARGUMENT must be in 1..4"
        }

        val targetContext = instrumentation.targetContext
        val testContext = instrumentation.context
        val selection = SelfReproducedSemanticModelFixture.select(arguments)
        val root = File(targetContext.filesDir, "post-onnx-i9-thread-$threadCount")
        root.deleteRecursively()
        check(root.mkdirs())

        val encoder = File(root, selection.identity.modelFileName)
        val tokenizer = File(root, selection.identity.tokenizerFileName)

        try {
            testContext.assets.open(selection.identity.modelFileName).use { input ->
                encoder.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
            testContext.assets.open(selection.identity.tokenizerFileName).use { input ->
                tokenizer.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }

            val validated = assertIs<SemanticModelArtifactValidationResult.Validated>(
                SemanticModelArtifactValidator(
                    appPrivateRoot = root,
                    trustedIdentity = selection.identity,
                    acceptance = selection.acceptance
                ).validate(
                    candidate = encoder,
                    spec = SemanticModelArtifactSpec(selection.identity)
                )
            ).artifact

            forceGc()
            val pssBeforeLoad = processPssBytes()
            val loadStarted = SystemClock.elapsedRealtimeNanos()
            val ownership = assertIs<SemanticEmbeddingSessionLoadResult.Loaded>(
                SemanticEmbeddingSessionLoader(
                    SemanticEmbeddingPolicy(threadCount = threadCount)
                ).load(validated)
            ).session
            val loadMs = elapsedMillis(loadStarted)

            try {
                repeat(WARMUP_COUNT) {
                    assertIs<SemanticEmbeddingResult.Embedded>(ownership.embed(SHORT_QUERY))
                }

                val shortSamples = LongArray(SHORT_SAMPLE_COUNT) {
                    val started = SystemClock.elapsedRealtimeNanos()
                    assertIs<SemanticEmbeddingResult.Embedded>(ownership.embed(SHORT_QUERY))
                    elapsedMicros(started)
                }

                val paragraphSamples = LongArray(PARAGRAPH_SAMPLE_COUNT) {
                    val started = SystemClock.elapsedRealtimeNanos()
                    assertIs<SemanticEmbeddingResult.Embedded>(ownership.embed(PARAGRAPH))
                    elapsedMicros(started)
                }

                forceGc()
                val pssReadyBytes = processPssBytes()
                val pssBeforeThroughput = pssReadyBytes
                var peakPss = pssBeforeThroughput
                val throughputStarted = SystemClock.elapsedRealtimeNanos()
                repeat(THROUGHPUT_EMBED_COUNT) { index ->
                    assertIs<SemanticEmbeddingResult.Embedded>(
                        ownership.embed("$PASSAGE_PREFIX ${index + 1}.")
                    )
                    if ((index + 1) % PSS_SAMPLE_INTERVAL == 0) {
                        peakPss = max(peakPss, processPssBytes())
                    }
                }
                val throughputMs = elapsedMillis(throughputStarted)
                forceGc()
                val pssAfterThroughput = processPssBytes()
                peakPss = max(peakPss, pssAfterThroughput)

                val evidence = linkedMapOf(
                    "evidenceClass" to "post-onnx-i9-thread-count-physical-benchmark",
                    "primaryAbi" to Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                    "allAbis" to Build.SUPPORTED_ABIS.joinToString(","),
                    "threadCount" to threadCount.toString(),
                    "runtimeVersion" to SemanticModelProfileV01.ONNX_RUNTIME_VERSION,
                    "fixtureSha256" to selection.identity.expectedSha256,
                    "fixtureBytes" to selection.identity.expectedSizeBytes.toString(),
                    "loadMs" to loadMs.toString(),
                    "pssBeforeLoadBytes" to pssBeforeLoad.toString(),
                    "pssReadyBytes" to pssReadyBytes.toString(),
                    "shortMedianMicros" to median(shortSamples).toString(),
                    "shortP95Micros" to percentile95(shortSamples).toString(),
                    "paragraphMedianMicros" to median(paragraphSamples).toString(),
                    "paragraphP95Micros" to percentile95(paragraphSamples).toString(),
                    "throughputEmbedCount" to THROUGHPUT_EMBED_COUNT.toString(),
                    "throughputMs" to throughputMs.toString(),
                    "throughputEmbeddingsPerSecondMilli" to
                        ((THROUGHPUT_EMBED_COUNT * 1_000_000L) / max(1L, throughputMs)).toString(),
                    "throughputPssBeforeBytes" to pssBeforeThroughput.toString(),
                    "throughputPssAfterBytes" to pssAfterThroughput.toString(),
                    "throughputPeakPssBytes" to peakPss.toString()
                )

                assertTrue(
                    evidence["primaryAbi"] == "arm64-v8a",
                    "I9 acceptance benchmark requires physical arm64-v8a"
                )
                assertTrue(loadMs > 0L)
                assertTrue(throughputMs > 0L)

                val evidenceJson = writeEvidence(root, evidence)
                println("POST_ONNX_I9_EVIDENCE=$evidenceJson")
                recordEvidence(evidence)
            } finally {
                assertIs<SemanticEmbeddingCloseResult.Closed>(ownership.close())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun median(values: LongArray): Long {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun percentile95(values: LongArray): Long {
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * 95) / 100
        return sorted[index]
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

    private fun elapsedMicros(startedNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000L

    private fun recordEvidence(values: Map<String, String>) {
        val bundle = Bundle()
        values.forEach { (key, value) ->
            bundle.putString("postOnnxI9.$key", value)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, bundle)
    }

    private fun writeEvidence(root: File, values: Map<String, String>): String {
        val json = JSONObject()
        values.forEach { (key, value) -> json.put(key, value) }
        val rendered = json.toString()
        File(root, EVIDENCE_FILE_NAME).writeText(json.toString(2) + "\n", Charsets.UTF_8)
        return rendered
    }

    private companion object {
        const val THREAD_COUNT_ARGUMENT = "semanticThreadCount"
        const val WARMUP_COUNT = 3
        const val SHORT_SAMPLE_COUNT = 21
        const val PARAGRAPH_SAMPLE_COUNT = 11
        const val THROUGHPUT_EMBED_COUNT = 250
        const val PSS_SAMPLE_INTERVAL = 10
        const val EVIDENCE_FILE_NAME = "post-onnx-i9-thread-count-evidence.json"

        const val SHORT_QUERY = "Where are my keys?"
        const val PARAGRAPH =
            "Remember that the spare keys are in the kitchen drawer beside the travel documents, " +
                "and that tomorrow's appointment begins after lunch."
        const val PASSAGE_PREFIX =
            "Local benchmark passage about household notes, travel plans, schedules and reminders"
    }
}
