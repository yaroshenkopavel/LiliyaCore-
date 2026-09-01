package pro.liliya.core.license

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LicenseServiceDurableStateCodecContractTest {
    private fun state(
        product: String,
        subject: String,
        revocation: Long? = 7L,
        replay: Long? = 11L,
        serverTime: Instant? = Instant.parse("2026-09-01T08:00:00.123456789Z")
    ) = LicenseServiceSecurityState(
        scope = LicenseServiceSecurityScope(
            productId = LicenseProductId(product),
            subject = LicenseSubject(subject)
        ),
        revocationEpoch = revocation?.let(::LicenseRevocationEpoch),
        replaySequence = replay?.let(::LicenseReplaySequence),
        serverTime = serverTime
    )

    private fun snapshot(
        states: List<LicenseServiceSecurityState>,
        generation: Long = 9L,
        backendRevision: Long = 4L
    ) = LicenseServiceDurableStateSnapshot(
        states = states,
        generation = LicenseServiceDurableStateGeneration(generation),
        backendRevision = LicenseServiceDurableBackendRevision(backendRevision)
    )

    @Test
    fun canonical_snapshot_round_trips_and_is_order_independent() {
        val first = state("PRIVATE-PRODUCT-B", "PRIVATE-SUBJECT-B", replay = 13L)
        val second = state("PRIVATE-PRODUCT-A", "PRIVATE-SUBJECT-A", revocation = 8L)
        val reversed = snapshot(listOf(first, second))
        val canonical = snapshot(listOf(second, first))

        assertEquals(canonical, reversed)
        val encodedReversed = assertIs<LicenseServiceDurableStateEncodeResult.Encoded>(
            LicenseServiceDurableStateCanonicalCodec.encode(reversed)
        ).payload
        val encodedCanonical = assertIs<LicenseServiceDurableStateEncodeResult.Encoded>(
            LicenseServiceDurableStateCanonicalCodec.encode(canonical)
        ).payload
        assertEquals(encodedCanonical, encodedReversed)

        val decoded = assertIs<LicenseServiceDurableStateDecodeResult.Decoded>(
            LicenseServiceDurableStateCanonicalCodec.decode(encodedCanonical)
        ).snapshot
        assertEquals(canonical, decoded)
        assertEquals(9L, decoded.generation.value)
        assertEquals(4L, decoded.backendRevision.value)

        val rendered = buildString {
            append(canonical)
            append(encodedCanonical)
            append(decoded)
        }
        assertFalse("PRIVATE-PRODUCT-A" in rendered)
        assertFalse("PRIVATE-PRODUCT-B" in rendered)
        assertFalse("PRIVATE-SUBJECT-A" in rendered)
        assertFalse("PRIVATE-SUBJECT-B" in rendered)
        assertTrue("<redacted>" in rendered)
    }

    @Test
    fun text_and_scope_count_bounds_fail_before_unbounded_codec_growth() {
        val oversized = "x".repeat(LicenseServiceDurableStateCanonicalCodec.MAX_TEXT_BYTES + 1)
        val oversizedTextResult = LicenseServiceDurableStateCanonicalCodec.encode(
            snapshot(listOf(state(oversized, "subject")))
        )
        assertEquals(
            LicenseServiceDurableStateCodecRejection.BOUNDS_EXCEEDED,
            assertIs<LicenseServiceDurableStateEncodeResult.Rejected>(oversizedTextResult).reason
        )

        val manyStates = (0..LicenseServiceDurableStateCanonicalCodec.MAX_SCOPE_COUNT).map { index ->
            state("product-$index", "subject-$index", revocation = null, serverTime = null)
        }
        assertFailsWith<IllegalArgumentException> {
            snapshot(manyStates)
        }
    }

    @Test
    fun decode_rejects_oversized_payload_before_canonical_parsing() {
        val oversized = LicenseServiceDurableStatePayload.of(
            ByteArray(LicenseServiceDurableStateCanonicalCodec.MAX_PAYLOAD_BYTES + 1) { 1 }
        )

        val result = LicenseServiceDurableStateCanonicalCodec.decode(oversized)

        assertEquals(
            LicenseServiceDurableStateCodecRejection.BOUNDS_EXCEEDED,
            assertIs<LicenseServiceDurableStateDecodeResult.Rejected>(result).reason
        )
    }

    @Test
    fun duplicate_scope_wire_state_is_rejected_explicitly() {
        val duplicate = rawPayload(
            rawState("product", "subject", flags = FLAG_REPLAY, replay = 1L),
            rawState("product", "subject", flags = FLAG_REPLAY, replay = 2L)
        )

        val result = LicenseServiceDurableStateCanonicalCodec.decode(duplicate)

        assertEquals(
            LicenseServiceDurableStateCodecRejection.DUPLICATE_SCOPE,
            assertIs<LicenseServiceDurableStateDecodeResult.Rejected>(result).reason
        )
    }

    @Test
    fun valid_but_noncanonical_scope_order_is_rejected() {
        val noncanonical = rawPayload(
            rawState("product-b", "subject-b", flags = FLAG_REPLAY, replay = 2L),
            rawState("product-a", "subject-a", flags = FLAG_REPLAY, replay = 1L)
        )

        val result = LicenseServiceDurableStateCanonicalCodec.decode(noncanonical)

        assertEquals(
            LicenseServiceDurableStateCodecRejection.NON_CANONICAL,
            assertIs<LicenseServiceDurableStateDecodeResult.Rejected>(result).reason
        )
    }

    @Test
    fun trailing_bytes_are_rejected_as_noncanonical() {
        val canonical = assertIs<LicenseServiceDurableStateEncodeResult.Encoded>(
            LicenseServiceDurableStateCanonicalCodec.encode(
                snapshot(listOf(state("product", "subject")))
            )
        ).payload.copyBytes()
        val withTrailing = LicenseServiceDurableStatePayload.of(canonical + byteArrayOf(0x55))

        val result = LicenseServiceDurableStateCanonicalCodec.decode(withTrailing)

        assertEquals(
            LicenseServiceDurableStateCodecRejection.NON_CANONICAL,
            assertIs<LicenseServiceDurableStateDecodeResult.Rejected>(result).reason
        )
    }

    @Test
    fun unsupported_version_is_distinct_from_malformed_payload() {
        val result = LicenseServiceDurableStateCanonicalCodec.decode(
            rawPayload(
                rawState("product", "subject", flags = FLAG_REPLAY, replay = 1L),
                version = 2
            )
        )

        assertEquals(
            LicenseServiceDurableStateCodecRejection.UNSUPPORTED_VERSION,
            assertIs<LicenseServiceDurableStateDecodeResult.Rejected>(result).reason
        )
    }

    @Test
    fun invalid_flags_and_nonpositive_ownership_values_fail_closed() {
        val invalidFlags = LicenseServiceDurableStateCanonicalCodec.decode(
            rawPayload(rawState("product", "subject", flags = 8))
        )
        assertEquals(
            LicenseServiceDurableStateCodecRejection.MALFORMED,
            assertIs<LicenseServiceDurableStateDecodeResult.Rejected>(invalidFlags).reason
        )

        val invalidGeneration = LicenseServiceDurableStateCanonicalCodec.decode(
            rawPayload(
                rawState("product", "subject", flags = FLAG_REPLAY, replay = 1L),
                generation = 0L
            )
        )
        assertEquals(
            LicenseServiceDurableStateCodecRejection.MALFORMED,
            assertIs<LicenseServiceDurableStateDecodeResult.Rejected>(invalidGeneration).reason
        )

        val invalidRevision = LicenseServiceDurableStateCanonicalCodec.decode(
            rawPayload(
                rawState("product", "subject", flags = FLAG_REPLAY, replay = 1L),
                backendRevision = 0L
            )
        )
        assertEquals(
            LicenseServiceDurableStateCodecRejection.MALFORMED,
            assertIs<LicenseServiceDurableStateDecodeResult.Rejected>(invalidRevision).reason
        )
    }

    private data class RawState(
        val product: String,
        val subject: String,
        val flags: Int,
        val revocation: Long? = null,
        val replay: Long? = null,
        val serverTime: Instant? = null
    )

    private fun rawState(
        product: String,
        subject: String,
        flags: Int,
        revocation: Long? = null,
        replay: Long? = null,
        serverTime: Instant? = null
    ) = RawState(product, subject, flags, revocation, replay, serverTime)

    private fun rawPayload(
        vararg states: RawState,
        version: Int = 1,
        generation: Long = 9L,
        backendRevision: Long = 4L
    ): LicenseServiceDurableStatePayload = LicenseServiceDurableStatePayload.of(
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(version)
                data.writeUtf8(PURPOSE)
                data.writeLong(generation)
                data.writeLong(backendRevision)
                data.writeInt(states.size)
                states.forEach { state ->
                    data.writeUtf8(state.product)
                    data.writeUtf8(state.subject)
                    data.writeInt(state.flags)
                    if (state.flags and FLAG_REVOCATION != 0) data.writeLong(state.revocation ?: 0L)
                    if (state.flags and FLAG_REPLAY != 0) data.writeLong(state.replay ?: 0L)
                    if (state.flags and FLAG_SERVER_TIME != 0) {
                        val time = state.serverTime ?: Instant.EPOCH
                        data.writeLong(time.epochSecond)
                        data.writeInt(time.nano)
                    }
                }
            }
            output.toByteArray()
        }
    )

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.encodeToByteArray()
        writeInt(bytes.size)
        write(bytes)
    }

    companion object {
        private const val MAGIC = 0x4C534434
        private const val PURPOSE = "LICENSE_SERVICE_SECURITY_STATE"
        private const val FLAG_REVOCATION = 1
        private const val FLAG_REPLAY = 1 shl 1
        private const val FLAG_SERVER_TIME = 1 shl 2
    }
}
