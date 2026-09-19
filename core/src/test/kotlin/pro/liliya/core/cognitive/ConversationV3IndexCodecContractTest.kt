package pro.liliya.core.cognitive

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import pro.liliya.core.persistence.PersistentEntityId

class ConversationV3IndexCodecContractTest {
    private val session = CognitiveConversationSessionId("private-session-v3")
    private val at = Instant.parse("2026-09-19T00:00:00Z")

    @Test
    fun native_format_marker_round_trips_and_is_fixed_non_sensitive_id() {
        val record = ConversationV3IndexCodec.encodeMarker(at)
        assertEquals(ConversationV3IndexCodec.MARKER_ID, record.id)
        assertFalse(record.id.value.contains(session.value))
        assertIs<ConversationV3DecodeResult.Decoded<ConversationV3FormatMarker>>(
            ConversationV3IndexCodec.decodeMarker(record)
        )
        assertEquals(
            ConversationV3DecodeResult.Incompatible("conversation v3 marker schema mismatch"),
            ConversationV3IndexCodec.decodeMarker(
                record.copy(id = PersistentEntityId("conversation-v3-format-marker-other"))
            )
        )
    }

    @Test
    fun truncated_native_marker_fails_closed_as_corrupt() {
        val record = ConversationV3IndexCodec.encodeMarker(at)
        val bytes = record.payload.copyBytes()
        val truncated = record.copy(
            payload = pro.liliya.core.persistence.PersistentPayload(
                bytes.copyOf(bytes.size - 1)
            )
        )
        assertEquals(
            ConversationV3DecodeResult.Corrupt,
            ConversationV3IndexCodec.decodeMarker(truncated)
        )
    }

    @Test
    fun head_and_linked_chunk_round_trip_with_deterministic_hashed_ids() {
        val first = CognitiveConversationContextMessage(
            CognitiveConversationSequence(1),
            CognitiveConversationRole.USER,
            "secret user message"
        )
        val second = CognitiveConversationContextMessage(
            CognitiveConversationSequence(2),
            CognitiveConversationRole.ASSISTANT,
            "secret assistant reply"
        )
        val chunkRecord = ConversationV3IndexCodec.encodeChunk(
            ConversationV3LinkedChunk(
                CognitiveConversationContextSnapshot(session, listOf(first, second)),
                previousChunkId = null
            ),
            at
        )
        val chunk = assertIs<ConversationV3DecodeResult.Decoded<ConversationV3LinkedChunk>>(
            ConversationV3IndexCodec.decodeChunk(chunkRecord)
        ).value
        assertEquals(session, chunk.snapshot.sessionId)
        assertEquals(listOf(1L, 2L), chunk.snapshot.messages.map { it.sequence.value })
        assertEquals(null, chunk.previousChunkId)

        val headRecord = ConversationV3IndexCodec.encodeHead(
            ConversationV3SessionHead(
                sessionId = session,
                lastSequence = 2L,
                latestChunkId = chunkRecord.id
            ),
            at
        )
        val head = assertIs<ConversationV3DecodeResult.Decoded<ConversationV3SessionHead>>(
            ConversationV3IndexCodec.decodeHead(headRecord)
        ).value
        assertEquals(2L, head.lastSequence)
        assertEquals(chunkRecord.id, head.latestChunkId)

        assertEquals(ConversationV3IndexCodec.headId(session), headRecord.id)
        assertEquals(ConversationV3IndexCodec.chunkId(session, 1L), chunkRecord.id)
        assertFalse(headRecord.id.value.contains(session.value))
        assertFalse(chunkRecord.id.value.contains(session.value))
        assertFalse(chunkRecord.id.value.contains("secret"))
    }

    @Test
    fun linked_chunk_preserves_predecessor_pointer() {
        val previous = ConversationV3IndexCodec.chunkId(session, 1L)
        val current = ConversationV3IndexCodec.encodeChunk(
            ConversationV3LinkedChunk(
                CognitiveConversationContextSnapshot(
                    session,
                    listOf(
                        CognitiveConversationContextMessage(
                            CognitiveConversationSequence(3),
                            CognitiveConversationRole.USER,
                            "next"
                        ),
                        CognitiveConversationContextMessage(
                            CognitiveConversationSequence(4),
                            CognitiveConversationRole.ASSISTANT,
                            "reply"
                        )
                    )
                ),
                previousChunkId = previous
            ),
            at.plusSeconds(1)
        )
        val decoded = assertIs<ConversationV3DecodeResult.Decoded<ConversationV3LinkedChunk>>(
            ConversationV3IndexCodec.decodeChunk(current)
        ).value
        assertEquals(previous, decoded.previousChunkId)
    }

    @Test
    fun linked_chunk_predecessor_boundary_is_strict() {
        val firstMessage = CognitiveConversationContextMessage(
            CognitiveConversationSequence(1),
            CognitiveConversationRole.USER,
            "first"
        )
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ConversationV3IndexCodec.encodeChunk(
                ConversationV3LinkedChunk(
                    CognitiveConversationContextSnapshot(session, listOf(firstMessage)),
                    previousChunkId = PersistentEntityId("unexpected-predecessor")
                ),
                at
            )
        }

        val laterMessage = CognitiveConversationContextMessage(
            CognitiveConversationSequence(3),
            CognitiveConversationRole.USER,
            "later"
        )
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ConversationV3IndexCodec.encodeChunk(
                ConversationV3LinkedChunk(
                    CognitiveConversationContextSnapshot(session, listOf(laterMessage)),
                    previousChunkId = null
                ),
                at
            )
        }
    }

    @Test
    fun decoder_rejects_entity_id_substitution() {
        val record = ConversationV3IndexCodec.encodeChunk(
            ConversationV3LinkedChunk(
                CognitiveConversationContextSnapshot(
                    session,
                    listOf(
                        CognitiveConversationContextMessage(
                            CognitiveConversationSequence(1),
                            CognitiveConversationRole.USER,
                            "one"
                        )
                    )
                ),
                previousChunkId = null
            ),
            at
        )
        assertEquals(
            ConversationV3DecodeResult.Corrupt,
            ConversationV3IndexCodec.decodeChunk(
                record.copy(id = PersistentEntityId("conversation-v3-chunk-substituted"))
            )
        )

        val head = ConversationV3IndexCodec.encodeHead(
            ConversationV3SessionHead(session, 1L, record.id),
            at
        )
        assertEquals(
            ConversationV3DecodeResult.Corrupt,
            ConversationV3IndexCodec.decodeHead(
                head.copy(id = PersistentEntityId("conversation-head-substituted"))
            )
        )
    }
}
