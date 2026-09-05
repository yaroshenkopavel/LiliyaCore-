package pro.liliya.android.persistence

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.persistence.PersistentBackendEntry
import pro.liliya.core.persistence.PersistentBackendState
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion
import pro.liliya.core.persistence.PersistentStoreId

class AndroidPersistentStateCodecContractTest {

    @Test
    fun deterministic_round_trip_preserves_exact_revision_state_and_generation() {
        val state = fixtureState()

        val first = AndroidPersistentStateCodec.encode(7, state)
        val second = AndroidPersistentStateCodec.encode(7, state)
        assertTrue(first.contentEquals(second))

        val decoded = assertIs<AndroidPersistentStateCodec.DecodeResult.Decoded>(
            AndroidPersistentStateCodec.decode(first)
        )
        assertEquals(7, decoded.revision)
        assertEquals(state.storeId, decoded.state.storeId)
        assertEquals(state.highWatermark, decoded.state.highWatermark)
        assertEquals(state.entries.keys, decoded.state.entries.keys)

        state.entries.forEach { (id, expected) ->
            val actual = decoded.state.entries.getValue(id)
            assertEquals(expected.generation, actual.generation)
            assertEquals(expected.record.id, actual.record.id)
            assertEquals(expected.record.schemaId, actual.record.schemaId)
            assertEquals(expected.record.schemaVersion, actual.record.schemaVersion)
            assertEquals(expected.record.createdAt, actual.record.createdAt)
            assertTrue(
                expected.record.payload.copyBytes().contentEquals(
                    actual.record.payload.copyBytes()
                )
            )
        }
    }

    @Test
    fun checksum_corruption_is_rejected_as_corrupt() {
        val encoded = AndroidPersistentStateCodec.encode(1, fixtureState())
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 0x01).toByte()

        assertEquals(
            AndroidPersistentStateCodec.DecodeResult.Corrupt,
            AndroidPersistentStateCodec.decode(encoded)
        )
    }

    @Test
    fun unsupported_format_version_is_incompatible() {
        val encoded = AndroidPersistentStateCodec.encode(1, fixtureState())
        encoded[7] = 2

        assertEquals(
            AndroidPersistentStateCodec.DecodeResult.Incompatible,
            AndroidPersistentStateCodec.decode(encoded)
        )
    }

    @Test
    fun truncated_state_is_corrupt() {
        val encoded = AndroidPersistentStateCodec.encode(1, fixtureState())
        val truncated = encoded.copyOf(encoded.size - 3)

        assertEquals(
            AndroidPersistentStateCodec.DecodeResult.Corrupt,
            AndroidPersistentStateCodec.decode(truncated)
        )
    }

    private fun fixtureState(): PersistentBackendState {
        val a = PersistentEntityId("entity-a")
        val b = PersistentEntityId("entity-b")
        return PersistentBackendState(
            storeId = PersistentStoreId("memory"),
            highWatermark = 9,
            entries = linkedMapOf(
                b to PersistentBackendEntry(
                    generation = PersistentGeneration(9),
                    record = record(b, "beta", 2)
                ),
                a to PersistentBackendEntry(
                    generation = PersistentGeneration(4),
                    record = record(a, "alpha", 1)
                )
            )
        )
    }

    private fun record(
        id: PersistentEntityId,
        content: String,
        second: Long
    ) = PersistentRecord(
        id = id,
        schemaId = PersistentSchemaId("memory-record"),
        schemaVersion = PersistentSchemaVersion(1),
        payload = PersistentPayload(content.toByteArray()),
        createdAt = Instant.parse("2026-09-05T15:00:00Z").plusSeconds(second)
    )
}
