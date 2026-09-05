package pro.liliya.android.semanticprovider

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemoryRecordSnapshot
import pro.liliya.core.memory.MemorySourceId

/**
 * Post-ONNX production resource evidence using the real pinned tokenizer/encoder and the public
 * production assembly. Unlike the legacy flat-index resource probe, every rebuild entry is embedded
 * by the real ONNX session before transactional publication.
 */
@RunWith(AndroidJUnit4::class)
class OfflineSemanticProviderProductionResourceInstrumentedTest {

    @Test
    fun records_full_startup_and_real_rebuild_tiers_with_peak_pss() {
        withFixture { fixture, root, selection ->
            val semantic = AndroidOfflineSemanticProviderAssembly.create()
            val evidence = linkedMapOf<String, String>(
                "primaryAbi" to primaryRuntimeAbi(),
                "abi" to Build.SUPPORTED_ABIS.joinToString(","),
                "fixtureBytes" to selection.identity.expectedSizeBytes.toString(),
                "fixtureSha256" to selection.identity.expectedSha256,
                "runtimeVersion" to SemanticModelProfileV01.ONNX_RUNTIME_VERSION,
                "embeddingDimension" to SemanticEmbeddingVector.DIMENSION.toString(),
                "realEmbeddingRebuild" to "true",
                "arm64ThresholdsApplied" to isArm64Target().toString()
            )

            forceGc()
            val processPssBeforeStartupBytes = processPssBytes()
            val startupStarted = SystemClock.elapsedRealtimeNanos()
            assertEquals(
                AndroidOfflineSemanticProviderLoadResult.Loaded,
                semantic.load(
                    appPrivateRoot = root,
                    encoderFile = fixture
                )
            )
            val artifactValidationAndOrtLoadMs = elapsedMillis(startupStarted)

            evidence["processPssBeforeStartupBytes"] = processPssBeforeStartupBytes.toString()
            evidence["artifactValidationAndOrtLoadMs"] = artifactValidationAndOrtLoadMs.toString()

            var firstFullStartupMs: Long? = null
            try {
                for (count in REBUILD_TIERS) {
                    val snapshots = memorySnapshots(count)
                    forceGc()
                    val beforePss = processPssBytes()
                    val beforeNative = Debug.getNativeHeapAllocatedSize()
                    val sample = samplePeakPss {
                        val rebuildStarted = SystemClock.elapsedRealtimeNanos()
                        val rebuilt = assertIs<AndroidOfflineSemanticProviderRebuildResult.Ready>(
                            semantic.rebuild(memory = snapshots, knowledge = emptyList())
                        )
                        assertEquals(count, rebuilt.entryCount)
                        elapsedMillis(rebuildStarted)
                    }
                    forceGc()
                    val afterPss = processPssBytes()
                    val afterNative = Debug.getNativeHeapAllocatedSize()

                    evidence["realRebuild${count}LatencyMs"] = sample.value.toString()
                    evidence["realRebuild${count}PssBeforeBytes"] = beforePss.toString()
                    evidence["realRebuild${count}PssAfterBytes"] = afterPss.toString()
                    evidence["realRebuild${count}PssDeltaBytes"] = (afterPss - beforePss).toString()
                    evidence["realRebuild${count}PeakPssBytes"] = sample.peakPssBytes.toString()
                    evidence["realRebuild${count}PeakDeltaBytes"] =
                        (sample.peakPssBytes - beforePss).toString()
                    evidence["realRebuild${count}NativeHeapDeltaBytes"] =
                        (afterNative - beforeNative).toString()
                    evidence["realRebuild${count}RawVectorBytes"] =
                        (count.toLong() * SemanticEmbeddingVector.DIMENSION * Float.SIZE_BYTES)
                            .toString()

                    if (count == REBUILD_TIERS.first()) {
                        firstFullStartupMs = artifactValidationAndOrtLoadMs + sample.value
                        evidence["fullStartup1kMs"] = firstFullStartupMs.toString()
                    }

                    recordEvidence(
                        mapOf(
                            "realRebuild${count}LatencyMs" to sample.value.toString(),
                            "realRebuild${count}PeakPssBytes" to sample.peakPssBytes.toString(),
                            "realRebuild${count}PeakDeltaBytes" to
                                (sample.peakPssBytes - beforePss).toString()
                        )
                    )
                }

                forceGc()
                evidence["processPssReady20kBytes"] = processPssBytes().toString()

                if (isArm64Target()) {
                    assertTrue(
                        firstFullStartupMs != null && firstFullStartupMs > 0L,
                        "physical ARM64 full startup evidence must be recorded"
                    )
                    assertTrue(
                        evidence["realRebuild20000LatencyMs"]!!.toLong() > 0L,
                        "physical ARM64 real 20k rebuild evidence must be recorded"
                    )
                }
            } finally {
                assertEquals(AndroidOfflineSemanticProviderCloseResult.Closed, semantic.close())
                forceGc()
                evidence["processPssAfterCloseBytes"] = processPssBytes().toString()
            }

            writeEvidenceFile(evidence)
            recordEvidence(evidence)
        }
    }

    private fun memorySnapshots(count: Int): List<MemoryRecordSnapshot> =
        List(count) { index ->
            val ordinal = index + 1
            MemoryRecordSnapshot(
                record = MemoryRecord(
                    id = MemoryRecordId("resource-real-memory-$ordinal"),
                    provenance = MemoryProvenance(MemorySourceId("resource-real-rebuild")),
                    content = "Personal memory entry $ordinal about household notes, travel plans, keys, schedules and reminders.",
                    createdAt = BASE.plusSeconds(ordinal.toLong())
                ),
                generation = MemoryGeneration(ordinal.toLong())
            )
        }

    private fun <T> samplePeakPss(block: () -> T): PeakSample<T> {
        val running = AtomicBoolean(true)
        val peak = AtomicLong(processPssBytes())
        val sampler = Thread {
            while (running.get()) {
                val current = processPssBytes()
                peak.accumulateAndGet(current, ::maxOf)
                try {
                    Thread.sleep(PEAK_SAMPLE_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            name = "semantic-resource-pss-sampler"
            isDaemon = true
            start()
        }

        return try {
            val value = block()
            peak.accumulateAndGet(processPssBytes(), ::maxOf)
            PeakSample(value, peak.get())
        } finally {
            running.set(false)
            sampler.interrupt()
            sampler.join(2_000L)
        }
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

    private fun recordEvidence(values: Map<String, String>) {
        val bundle = Bundle()
        values.forEach { (key, value) ->
            bundle.putString("postOnnxResource.$key", value)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, bundle)
    }

    private fun writeEvidenceFile(values: Map<String, String>) {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val target = File(targetContext.filesDir, RESOURCE_EVIDENCE_FILE_NAME)
        val json = JSONObject()
        values.forEach { (key, value) -> json.put(key, value) }
        target.writeText(json.toString(2) + "\n", Charsets.UTF_8)
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
        val root = File(targetContext.filesDir, "post-onnx-production-resource-test")
        root.deleteRecursively()
        check(root.mkdirs())
        val fixture = File(root, selection.identity.modelFileName)
        val tokenizerFixture = File(root, selection.identity.tokenizerFileName)
        try {
            testContext.assets.open(selection.identity.modelFileName).use { input ->
                fixture.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
            testContext.assets.open(selection.identity.tokenizerFileName).use { input ->
                tokenizerFixture.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
            assertEquals(selection.identity.expectedSizeBytes, fixture.length())
            assertEquals(selection.identity.tokenizerExpectedSizeBytes, tokenizerFixture.length())
            block(fixture, root, selection)
        } finally {
            root.deleteRecursively()
        }
    }

    private data class PeakSample<T>(
        val value: T,
        val peakPssBytes: Long
    )

    private companion object {
        val REBUILD_TIERS = intArrayOf(1_000, 5_000, 10_000, 20_000)
        const val PEAK_SAMPLE_INTERVAL_MS = 25L
        const val RESOURCE_EVIDENCE_FILE_NAME = "post-onnx-production-resource-evidence.json"
        val BASE: Instant = Instant.parse("2026-09-05T15:00:00Z")
    }
}
