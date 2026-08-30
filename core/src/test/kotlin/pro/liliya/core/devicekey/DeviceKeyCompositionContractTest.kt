package pro.liliya.core.devicekey

import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class DeviceKeyCompositionContractTest {
    private val createdAt = Instant.parse("2026-08-30T19:20:00Z")

    private data class TestFoundation(
        val composition: FoundationComposition,
        val diagnostics: InMemoryDiagnosticSink
    )

    private fun testFoundation(): TestFoundation {
        val sequence = AtomicInteger(0)
        val diagnostics = InMemoryDiagnosticSink()
        return TestFoundation(
            composition = FoundationComposition(
                diagnostics = DiagnosticRecorder(diagnostics),
                loggerProvider = LoggerProvider { context ->
                    StructuredLogger(context, InMemoryLogWriter())
                },
                correlationIds = CorrelationIdGenerator {
                    "device-key-${sequence.incrementAndGet()}"
                }
            ),
            diagnostics = diagnostics
        )
    }

    private fun foundation(): FoundationComposition = testFoundation().composition

    private fun state(
        id: String,
        createdAt: Instant = this.createdAt,
        securityLevel: DeviceKeySecurityLevel = DeviceKeySecurityLevel.TRUSTED_ENVIRONMENT
    ) = DeviceKeyState(
        id = DeviceKeyId(id),
        algorithm = DeviceKeyAlgorithm("EC-P256-SHA256"),
        securityLevel = securityLevel,
        capabilities = setOf(DeviceKeyCapability.SIGN_CHALLENGE),
        createdAt = createdAt
    )

    @Test
    fun invalid_identifiers_generations_and_ready_security_level_fail_closed() {
        assertFailsWith<IllegalArgumentException> { DeviceKeyId(" ") }
        assertFailsWith<IllegalArgumentException> { DeviceKeyAlgorithm("") }
        assertFailsWith<IllegalArgumentException> { DeviceKeyGeneration(0) }
        assertFailsWith<IllegalArgumentException> {
            state("unknown-level", securityLevel = DeviceKeySecurityLevel.UNKNOWN)
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceKeyProfile(
                algorithm = DeviceKeyAlgorithm("EC-P256-SHA256"),
                requestedSecurityLevel = DeviceKeySecurityLevel.UNKNOWN,
                capabilities = setOf(DeviceKeyCapability.SIGN_CHALLENGE)
            )
        }
    }

    @Test
    fun exact_ownership_is_duplicate_safe_one_shot_and_aba_safe() {
        val composition = DeviceKeyComposition(foundation())
        val first = assertIs<DeviceKeyRegisterResult.Registered>(
            composition.register(state("device-key-main"))
        ).ownership

        assertEquals(1L, first.generation.value)
        assertIs<DeviceKeyRegisterResult.Rejected>(
            composition.register(state("device-key-main"))
        )
        assertTrue(first.remove())
        assertFalse(first.remove())

        val replacement = assertIs<DeviceKeyRegisterResult.Registered>(
            composition.register(state("device-key-main", createdAt.plusSeconds(1)))
        ).ownership
        assertEquals(2L, replacement.generation.value)
        assertFalse(first.remove())
        assertEquals(replacement.generation, composition.inspect(DeviceKeyId("device-key-main"))?.generation)
    }

    @Test
    fun snapshots_are_deterministic_and_compositions_are_isolated() {
        val first = DeviceKeyComposition(foundation())
        val second = DeviceKeyComposition(foundation())

        assertIs<DeviceKeyRegisterResult.Registered>(
            first.register(state("key-b", createdAt.plusSeconds(1)))
        )
        assertIs<DeviceKeyRegisterResult.Registered>(
            first.register(state("key-c", createdAt))
        )
        assertIs<DeviceKeyRegisterResult.Registered>(
            first.register(state("key-a", createdAt))
        )

        assertEquals(
            listOf("key-a", "key-c", "key-b"),
            first.snapshot().map { it.id.value }
        )
        assertEquals(setOf(1L, 2L, 3L), first.snapshotEntries().map { it.generation.value }.toSet())
        assertTrue(second.snapshot().isEmpty())
    }

    @Test
    fun registered_state_detaches_mutable_capability_input() {
        val composition = DeviceKeyComposition(foundation())
        val capabilities = mutableSetOf(DeviceKeyCapability.SIGN_CHALLENGE)
        val input = DeviceKeyState(
            id = DeviceKeyId("mutable-input"),
            algorithm = DeviceKeyAlgorithm("EC-P256-SHA256"),
            securityLevel = DeviceKeySecurityLevel.TRUSTED_ENVIRONMENT,
            capabilities = capabilities,
            createdAt = createdAt
        )

        val ownership = assertIs<DeviceKeyRegisterResult.Registered>(
            composition.register(input)
        ).ownership
        capabilities.clear()
        capabilities += DeviceKeyCapability.UNWRAP_WRAPPED_KEY

        val stored = composition.inspect(input.id)!!.state
        assertEquals(setOf(DeviceKeyCapability.SIGN_CHALLENGE), stored.capabilities)
        assertEquals(setOf(DeviceKeyCapability.SIGN_CHALLENGE), ownership.state.capabilities)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (stored.capabilities as MutableSet<DeviceKeyCapability>).clear()
        }
    }

    @Test
    fun profile_detaches_mutable_capability_input() {
        val capabilities = mutableSetOf(DeviceKeyCapability.SIGN_CHALLENGE)
        val profile = DeviceKeyProfile(
            algorithm = DeviceKeyAlgorithm("EC-P256-SHA256"),
            requestedSecurityLevel = DeviceKeySecurityLevel.TRUSTED_ENVIRONMENT,
            capabilities = capabilities
        )

        capabilities.clear()
        capabilities += DeviceKeyCapability.UNWRAP_WRAPPED_KEY

        assertEquals(setOf(DeviceKeyCapability.SIGN_CHALLENGE), profile.capabilities)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (profile.capabilities as MutableSet<DeviceKeyCapability>).clear()
        }
    }

    @Test
    fun concurrent_duplicate_registration_publishes_exactly_one_live_owner() {
        val composition = DeviceKeyComposition(foundation())
        val threads = 8
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<DeviceKeyRegisterResult>())
        val executor = Executors.newFixedThreadPool(threads)

        repeat(threads) {
            executor.submit {
                ready.countDown()
                start.await()
                results += composition.register(state("concurrent-main"))
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))

        assertEquals(1, results.count { it is DeviceKeyRegisterResult.Registered })
        assertEquals(threads - 1, results.count { it is DeviceKeyRegisterResult.Rejected })
        assertEquals(1, composition.snapshotEntries().size)
        assertEquals(1L, composition.snapshotEntries().single().generation.value)
    }

    @Test
    fun observability_and_rendering_redact_raw_device_key_id() {
        val fixture = testFoundation()
        val composition = DeviceKeyComposition(fixture.composition)
        val secretId = "RAW-DEVICE-KEY-ID-PRIVATE"

        assertIs<DeviceKeyRegisterResult.Registered>(composition.register(state(secretId)))

        val rendered = composition.inspect(DeviceKeyId(secretId))!!.state.toString()
        assertFalse(secretId in rendered)
        assertTrue("[redacted]" in rendered)

        val diagnosticText = fixture.diagnostics.snapshot().joinToString("\n") { event ->
            event.toString() + event.metadata.toString()
        }
        assertFalse(secretId in diagnosticText)
        assertTrue("[redacted]" in diagnosticText)
    }

    @Test
    fun unknown_security_level_never_reports_hardware_backed() {
        assertFalse(DeviceKeySecurityLevel.UNKNOWN.hardwareBacked)
        assertFalse(DeviceKeySecurityLevel.SOFTWARE.hardwareBacked)
        assertTrue(DeviceKeySecurityLevel.TRUSTED_ENVIRONMENT.hardwareBacked)
        assertTrue(DeviceKeySecurityLevel.STRONGBOX.hardwareBacked)
    }

    @Test
    fun failure_rendering_exposes_category_and_exception_class_not_exception_message() {
        val secret = "PRIVATE-PLATFORM-SECRET"
        val failure = DeviceKeyOperationResult.Failed(
            category = DeviceKeyFailureCategory.PLATFORM_REJECTED,
            throwable = IllegalStateException("platform leaked $secret")
        )
        val rendered = failure.toString()

        assertFalse(secret in rendered)
        assertFalse("platform leaked" in rendered)
        assertTrue(IllegalStateException::class.java.name in rendered)
        assertTrue(DeviceKeyFailureCategory.PLATFORM_REJECTED.name in rendered)
    }
}
