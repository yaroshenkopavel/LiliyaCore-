package pro.liliya.core.cognitive

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import pro.liliya.core.persistence.PersistentEntityId

class ConversationV3MigrationCodecContractTest {
    private val at = Instant.parse("2026-09-19T00:30:00Z")
    private val session = CognitiveConversationSessionId("legacy-private-session")

    @Test
    fun mixed_mode_marker_round_trips_without_changing_native_marker_contract() {
        val record = ConversationV3MigrationCodec.encodeMixedMarker(at)

        assertEquals(ConversationV3MigrationCodec.MIXED_MARKER_ID, record.id)
        assertIs<
            ConversationV3MigrationDecodeResult.Decoded<ConversationV3MixedModeMarker>
        >(ConversationV3MigrationCodec.decodeMixedMarker(record))

        assertEquals(
            ConversationV3MigrationDecodeResult.Incompatible(
                "conversation v3 mixed-mode marker schema mismatch"
            ),
            ConversationV3MigrationCodec.decodeMixedMarker(
                record.copy(id = PersistentEntityId("wrong-mixed-marker"))
            )
        )
    }

    @Test
    fun migration_receipt_round_trips_with_hashed_id_and_bound_target_head() {
        val source = PersistentEntityId("conversation-source-row")
        val receipt = ConversationV3MigrationReceipt(
            sessionId = session,
            sourceKind = ConversationV3MigrationSourceKind.V2_COMPLETE,
            sourceLegacyEntityId = source,
            targetHeadId = ConversationV3IndexCodec.headId(session)
        )
        val record = ConversationV3MigrationCodec.encodeMigrationReceipt(
            receipt,
            at
        )

        assertEquals(
            ConversationV3MigrationCodec.migrationReceiptId(session),
            record.id
        )
        assertFalse(record.id.value.contains(session.value))
        assertFalse(record.id.value.contains(source.value))

        val decoded = assertIs<
            ConversationV3MigrationDecodeResult.Decoded<
                ConversationV3MigrationReceipt
            >
        >(
            ConversationV3MigrationCodec.decodeMigrationReceipt(record)
        ).value
        assertEquals(receipt, decoded)

        assertEquals(
            ConversationV3MigrationDecodeResult.Corrupt,
            ConversationV3MigrationCodec.decodeMigrationReceipt(
                record.copy(
                    id = PersistentEntityId(
                        "conversation-v3-migration-receipt-substituted"
                    )
                )
            )
        )
    }

    @Test
    fun truncated_root_round_trips_with_hashed_non_sensitive_id_and_source_provenance() {
        val source = PersistentEntityId("conversation-legacy-source-record")
        val record = ConversationV3MigrationCodec.encodeTruncatedRoot(
            ConversationV3TruncatedRootBoundary(
                sessionId = session,
                firstRetainedSequence = 5L,
                sourceLegacyEntityId = source
            ),
            at
        )

        assertEquals(
            ConversationV3MigrationCodec.truncatedRootId(session),
            record.id
        )
        assertFalse(record.id.value.contains(session.value))
        assertFalse(record.id.value.contains(source.value))

        val decoded = assertIs<
            ConversationV3MigrationDecodeResult.Decoded<
                ConversationV3TruncatedRootBoundary
            >
        >(ConversationV3MigrationCodec.decodeTruncatedRoot(record)).value

        assertEquals(session, decoded.sessionId)
        assertEquals(5L, decoded.firstRetainedSequence)
        assertEquals(source, decoded.sourceLegacyEntityId)
    }

    @Test
    fun truncated_root_rejects_sequence_one_or_earlier() {
        val source = PersistentEntityId("conversation-legacy-source-record")

        assertFailsWith<IllegalArgumentException> {
            ConversationV3MigrationCodec.encodeTruncatedRoot(
                ConversationV3TruncatedRootBoundary(
                    sessionId = session,
                    firstRetainedSequence = 1L,
                    sourceLegacyEntityId = source
                ),
                at
            )
        }

        assertFailsWith<IllegalArgumentException> {
            ConversationV3MigrationCodec.encodeTruncatedRoot(
                ConversationV3TruncatedRootBoundary(
                    sessionId = session,
                    firstRetainedSequence = 0L,
                    sourceLegacyEntityId = source
                ),
                at
            )
        }
    }

    @Test
    fun truncated_root_decoder_rejects_entity_id_substitution() {
        val record = ConversationV3MigrationCodec.encodeTruncatedRoot(
            ConversationV3TruncatedRootBoundary(
                sessionId = session,
                firstRetainedSequence = 9L,
                sourceLegacyEntityId = PersistentEntityId("legacy-source")
            ),
            at
        )

        assertEquals(
            ConversationV3MigrationDecodeResult.Corrupt,
            ConversationV3MigrationCodec.decodeTruncatedRoot(
                record.copy(id = PersistentEntityId("substituted-truncated-root"))
            )
        )
    }
}
