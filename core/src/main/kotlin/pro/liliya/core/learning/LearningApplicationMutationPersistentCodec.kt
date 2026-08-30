package pro.liliya.core.learning

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.time.Instant
import pro.liliya.core.authority.AuthorityPrincipal
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.knowledge.KnowledgeSourceReference
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemorySourceId
import pro.liliya.core.memory.MemorySourceReference
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentPayload
import pro.liliya.core.persistence.PersistentRecord
import pro.liliya.core.persistence.PersistentSchemaId
import pro.liliya.core.persistence.PersistentSchemaVersion

internal sealed interface LearningApplicationMutationPersistentDecodeResult {
    class Prepared(val plan: LearningApplicationMutationPlan) : LearningApplicationMutationPersistentDecodeResult {
        override fun toString(): String =
            "Prepared(mutationId=${plan.id}, payload=<redacted>)"
    }

    class Completed(
        val plan: LearningApplicationMutationPlan,
        val receipt: LearningApplicationMutationApplicationReceipt
    ) : LearningApplicationMutationPersistentDecodeResult {
        override fun toString(): String =
            "Completed(mutationId=${plan.id}, generation=${receipt.mutation.generation}, payload=<redacted>)"
    }

    data object Corrupt : LearningApplicationMutationPersistentDecodeResult
    data class Incompatible(val reason: String) : LearningApplicationMutationPersistentDecodeResult
}

internal object LearningApplicationMutationPersistentCodec {
    val preparedSchemaId = PersistentSchemaId("learning-application-mutation-prepared")
    val completedSchemaId = PersistentSchemaId("learning-application-mutation-completed")
    val schemaVersion = PersistentSchemaVersion(1)

    private const val PREPARED_MAGIC = 0x4C4D5031
    private const val COMPLETED_MAGIC = 0x4C4D4331
    private const val TARGET_MEMORY = 1
    private const val TARGET_KNOWLEDGE = 2
    private const val PAYLOAD_MEMORY = 1
    private const val PAYLOAD_KNOWLEDGE = 2
    private const val KNOWLEDGE_ORIGIN_MEMORY = 1
    private const val KNOWLEDGE_ORIGIN_DECLARED = 2
    private const val DOWNSTREAM_MEMORY = 1
    private const val DOWNSTREAM_KNOWLEDGE = 2

    fun encodePrepared(plan: LearningApplicationMutationPlan): PersistentRecord =
        PersistentRecord(
            id = PersistentEntityId(preparedEntityId(plan.id)),
            schemaId = preparedSchemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(
                ByteArrayOutputStream().use { output ->
                    DataOutputStream(output).use { data ->
                        data.writeInt(PREPARED_MAGIC)
                        data.writePlan(plan)
                    }
                    output.toByteArray()
                }
            ),
            createdAt = plan.createdAt
        )

    fun encodeCompleted(
        plan: LearningApplicationMutationPlan,
        receipt: LearningApplicationMutationApplicationReceipt
    ): PersistentRecord {
        require(receipt.mutation.mutationId == plan.id) {
            "completed learning mutation receipt id must match plan"
        }
        require(receipt.target == plan.target) {
            "completed learning mutation receipt target must match plan"
        }
        require(downstreamMatchesTarget(receipt.downstream, plan.target)) {
            "completed learning mutation downstream must match target"
        }

        return PersistentRecord(
            id = PersistentEntityId(completedEntityId(plan.id)),
            schemaId = completedSchemaId,
            schemaVersion = schemaVersion,
            payload = PersistentPayload(
                ByteArrayOutputStream().use { output ->
                    DataOutputStream(output).use { data ->
                        data.writeInt(COMPLETED_MAGIC)
                        data.writePlan(plan)
                        data.writeLong(receipt.mutation.generation.value)
                        data.writeTarget(receipt.target)
                        when (val downstream = receipt.downstream) {
                            is LearningApplicationDownstreamReference.Memory -> {
                                data.writeByte(DOWNSTREAM_MEMORY)
                                data.writeString(downstream.recordId.value)
                                data.writeLong(downstream.generation.value)
                            }

                            is LearningApplicationDownstreamReference.Knowledge -> {
                                data.writeByte(DOWNSTREAM_KNOWLEDGE)
                                data.writeString(downstream.itemId.value)
                                data.writeLong(downstream.generation.value)
                            }
                        }
                    }
                    output.toByteArray()
                }
            ),
            createdAt = plan.createdAt
        )
    }

    fun decode(record: PersistentRecord): LearningApplicationMutationPersistentDecodeResult {
        if (record.schemaVersion != schemaVersion) {
            return LearningApplicationMutationPersistentDecodeResult.Incompatible(
                "persistent learning mutation schema version mismatch"
            )
        }

        return when (record.schemaId) {
            preparedSchemaId -> decodePrepared(record)
            completedSchemaId -> decodeCompleted(record)
            else -> LearningApplicationMutationPersistentDecodeResult.Incompatible(
                "persistent learning mutation schema id mismatch"
            )
        }
    }

    private fun decodePrepared(record: PersistentRecord): LearningApplicationMutationPersistentDecodeResult =
        decodeRecord(record, PREPARED_MAGIC) { data, input ->
            val plan = data.readPlan(input)
            if (record.id.value != preparedEntityId(plan.id)) return@decodeRecord null
            if (record.createdAt != plan.createdAt) return@decodeRecord null
            LearningApplicationMutationPersistentDecodeResult.Prepared(plan)
        }

    private fun decodeCompleted(record: PersistentRecord): LearningApplicationMutationPersistentDecodeResult =
        decodeRecord(record, COMPLETED_MAGIC) { data, input ->
            val plan = data.readPlan(input)
            val generation = LearningApplicationMutationGeneration(data.readLong())
            val target = data.readTarget()
            val downstream = when (data.readUnsignedByte()) {
                DOWNSTREAM_MEMORY -> LearningApplicationDownstreamReference.Memory(
                    recordId = MemoryRecordId(data.readString(input)),
                    generation = MemoryGeneration(data.readLong())
                )

                DOWNSTREAM_KNOWLEDGE -> LearningApplicationDownstreamReference.Knowledge(
                    itemId = KnowledgeItemId(data.readString(input)),
                    generation = KnowledgeGeneration(data.readLong())
                )

                else -> return@decodeRecord null
            }
            val receipt = LearningApplicationMutationApplicationReceipt(
                mutation = LearningApplicationMutationReference(plan.id, generation),
                target = target,
                downstream = downstream
            )
            if (record.id.value != completedEntityId(plan.id)) return@decodeRecord null
            if (record.createdAt != plan.createdAt) return@decodeRecord null
            if (target != plan.target) return@decodeRecord null
            if (!downstreamMatchesTarget(downstream, target)) return@decodeRecord null
            LearningApplicationMutationPersistentDecodeResult.Completed(plan, receipt)
        }

    private inline fun decodeRecord(
        record: PersistentRecord,
        magic: Int,
        block: (DataInputStream, ByteArrayInputStream) -> LearningApplicationMutationPersistentDecodeResult?
    ): LearningApplicationMutationPersistentDecodeResult {
        return try {
            val input = ByteArrayInputStream(record.payload.copyBytes())
            val data = DataInputStream(input)
            if (data.readInt() != magic) return LearningApplicationMutationPersistentDecodeResult.Corrupt
            val decoded = block(data, input) ?: return LearningApplicationMutationPersistentDecodeResult.Corrupt
            if (input.available() != 0) return LearningApplicationMutationPersistentDecodeResult.Corrupt
            decoded
        } catch (_: EOFException) {
            LearningApplicationMutationPersistentDecodeResult.Corrupt
        } catch (_: IllegalArgumentException) {
            LearningApplicationMutationPersistentDecodeResult.Corrupt
        } catch (_: RuntimeException) {
            LearningApplicationMutationPersistentDecodeResult.Corrupt
        }
    }

    private fun DataOutputStream.writePlan(plan: LearningApplicationMutationPlan) {
        writeString(plan.id.value)
        writeString(plan.application.applicationId.value)
        writeLong(plan.application.generation.value)
        writeString(plan.principal.value)
        writeTarget(plan.target)
        writeString(plan.idempotencyKey.value)
        when (val payload = plan.payload) {
            is LearningApplicationMutationPayload.Memory -> {
                writeByte(PAYLOAD_MEMORY)
                writeMemoryRecord(payload.record)
            }

            is LearningApplicationMutationPayload.Knowledge -> {
                writeByte(PAYLOAD_KNOWLEDGE)
                writeKnowledgeItem(payload.item)
            }
        }
        writeInstant(plan.createdAt)
    }

    private fun DataInputStream.readPlan(input: ByteArrayInputStream): LearningApplicationMutationPlan {
        val id = LearningApplicationMutationId(readString(input))
        val application = LearningApplicationIntentReference(
            applicationId = LearningApplicationId(readString(input)),
            generation = LearningApplicationGeneration(readLong())
        )
        val principal = AuthorityPrincipal(readString(input))
        val target = readTarget()
        val idempotencyKey = LearningApplicationIdempotencyKey(readString(input))
        val payload = when (readUnsignedByte()) {
            PAYLOAD_MEMORY -> LearningApplicationMutationPayload.Memory(readMemoryRecord(input))
            PAYLOAD_KNOWLEDGE -> LearningApplicationMutationPayload.Knowledge(readKnowledgeItem(input))
            else -> throw IllegalArgumentException("invalid learning mutation payload type")
        }
        val createdAt = readInstant()
        return LearningApplicationMutationPlan(
            id = id,
            application = application,
            principal = principal,
            target = target,
            idempotencyKey = idempotencyKey,
            payload = payload,
            createdAt = createdAt
        )
    }

    private fun DataOutputStream.writeMemoryRecord(record: MemoryRecord) {
        writeString(record.id.value)
        writeString(record.provenance.sourceId.value)
        val reference = record.provenance.sourceReference
        writeBoolean(reference != null)
        if (reference != null) writeString(reference.value)
        writeString(record.content)
        writeInstant(record.createdAt)
    }

    private fun DataInputStream.readMemoryRecord(input: ByteArrayInputStream): MemoryRecord {
        val id = MemoryRecordId(readString(input))
        val sourceId = MemorySourceId(readString(input))
        val sourceReference = if (readBoolean()) {
            MemorySourceReference(readString(input))
        } else {
            null
        }
        val content = readString(input)
        val createdAt = readInstant()
        return MemoryRecord(
            id = id,
            provenance = MemoryProvenance(sourceId, sourceReference),
            content = content,
            createdAt = createdAt
        )
    }

    private fun DataOutputStream.writeKnowledgeItem(item: KnowledgeItem) {
        writeString(item.id.value)
        when (val origin = item.origin) {
            is KnowledgeOrigin.Memory -> {
                writeByte(KNOWLEDGE_ORIGIN_MEMORY)
                writeString(origin.recordId.value)
                writeLong(origin.generation.value)
            }

            is KnowledgeOrigin.Declared -> {
                writeByte(KNOWLEDGE_ORIGIN_DECLARED)
                writeString(origin.sourceId.value)
                val reference = origin.sourceReference
                writeBoolean(reference != null)
                if (reference != null) writeString(reference.value)
            }
        }
        writeString(item.content)
        writeInstant(item.createdAt)
    }

    private fun DataInputStream.readKnowledgeItem(input: ByteArrayInputStream): KnowledgeItem {
        val id = KnowledgeItemId(readString(input))
        val origin = when (readUnsignedByte()) {
            KNOWLEDGE_ORIGIN_MEMORY -> KnowledgeOrigin.Memory(
                recordId = MemoryRecordId(readString(input)),
                generation = MemoryGeneration(readLong())
            )

            KNOWLEDGE_ORIGIN_DECLARED -> {
                val sourceId = KnowledgeSourceId(readString(input))
                val sourceReference = if (readBoolean()) {
                    KnowledgeSourceReference(readString(input))
                } else {
                    null
                }
                KnowledgeOrigin.Declared(sourceId, sourceReference)
            }

            else -> throw IllegalArgumentException("invalid knowledge origin type")
        }
        val content = readString(input)
        val createdAt = readInstant()
        return KnowledgeItem(id, origin, content, createdAt)
    }

    private fun DataOutputStream.writeTarget(target: LearningApplicationTarget) {
        writeByte(
            when (target) {
                LearningApplicationTarget.MEMORY -> TARGET_MEMORY
                LearningApplicationTarget.KNOWLEDGE -> TARGET_KNOWLEDGE
            }
        )
    }

    private fun DataInputStream.readTarget(): LearningApplicationTarget = when (readUnsignedByte()) {
        TARGET_MEMORY -> LearningApplicationTarget.MEMORY
        TARGET_KNOWLEDGE -> LearningApplicationTarget.KNOWLEDGE
        else -> throw IllegalArgumentException("invalid learning application target")
    }

    private fun DataOutputStream.writeInstant(value: Instant) {
        writeLong(value.epochSecond)
        writeInt(value.nano)
    }

    private fun DataInputStream.readInstant(): Instant =
        Instant.ofEpochSecond(readLong(), readInt().toLong())

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(input: ByteArrayInputStream): String {
        val length = readInt()
        if (length < 0 || length > input.available()) throw EOFException()
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun preparedEntityId(id: LearningApplicationMutationId): String =
        "learning-mutation:prepared:${id.value}"

    private fun completedEntityId(id: LearningApplicationMutationId): String =
        "learning-mutation:completed:${id.value}"

    private fun downstreamMatchesTarget(
        downstream: LearningApplicationDownstreamReference,
        target: LearningApplicationTarget
    ): Boolean = when (target) {
        LearningApplicationTarget.MEMORY -> downstream is LearningApplicationDownstreamReference.Memory
        LearningApplicationTarget.KNOWLEDGE -> downstream is LearningApplicationDownstreamReference.Knowledge
    }
}

internal data class LearningPreparedPersistentState(
    val plan: LearningApplicationMutationPlan,
    val generation: LearningApplicationMutationGeneration
)

internal data class LearningCompletedPersistentState(
    val plan: LearningApplicationMutationPlan,
    val receipt: LearningApplicationMutationApplicationReceipt
)

internal class LearningApplicationMutationRestoredState(
    liveEntries: List<LearningPreparedPersistentState>,
    completedEntries: List<LearningCompletedPersistentState>,
    val highWatermark: Long
) {
    val liveEntries: List<LearningPreparedPersistentState> = liveEntries.toList()
    val completedEntries: List<LearningCompletedPersistentState> = completedEntries.toList()
    val completedByMutationId: Map<LearningApplicationMutationId, LearningApplicationMutationApplicationReceipt> =
        completedEntries.associate { it.plan.id to it.receipt }
    val completedByIdempotencyKey: Map<LearningApplicationIdempotencyKey, LearningApplicationMutationApplicationReceipt> =
        completedEntries.associate { it.plan.idempotencyKey to it.receipt }
}

internal sealed interface LearningApplicationMutationRestorationResult {
    data class Restored(val state: LearningApplicationMutationRestoredState) :
        LearningApplicationMutationRestorationResult

    data class Rejected(val reason: String) : LearningApplicationMutationRestorationResult
}

internal object LearningApplicationMutationRestorationBoundary {
    fun restore(
        liveEntries: List<LearningPreparedPersistentState>,
        completedEntries: List<LearningCompletedPersistentState>,
        highWatermark: Long
    ): LearningApplicationMutationRestorationResult {
        if (highWatermark < 0L) return rejected("learning mutation high-watermark must not be negative")

        val liveIds = mutableSetOf<LearningApplicationMutationId>()
        val liveGenerations = mutableSetOf<LearningApplicationMutationGeneration>()
        val liveKeys = mutableSetOf<LearningApplicationIdempotencyKey>()
        for (entry in liveEntries) {
            if (entry.generation.value > highWatermark) {
                return rejected("live learning mutation generation exceeds high-watermark")
            }
            if (!liveIds.add(entry.plan.id)) return rejected("duplicate live learning mutation id")
            if (!liveGenerations.add(entry.generation)) return rejected("duplicate live learning mutation generation")
            if (!liveKeys.add(entry.plan.idempotencyKey)) return rejected("duplicate live learning idempotency key")
        }

        val completedIds = mutableSetOf<LearningApplicationMutationId>()
        val completedKeys = mutableSetOf<LearningApplicationIdempotencyKey>()
        for (entry in completedEntries) {
            val receipt = entry.receipt
            if (receipt.mutation.mutationId != entry.plan.id) {
                return rejected("completed learning receipt mutation id mismatch")
            }
            if (receipt.mutation.generation.value > highWatermark) {
                return rejected("completed learning mutation generation exceeds high-watermark")
            }
            if (receipt.target != entry.plan.target) {
                return rejected("completed learning receipt target mismatch")
            }
            if (!downstreamMatchesTarget(receipt.downstream, receipt.target)) {
                return rejected("completed learning downstream target mismatch")
            }
            if (!completedIds.add(entry.plan.id)) return rejected("duplicate completed learning mutation id")
            if (!completedKeys.add(entry.plan.idempotencyKey)) {
                return rejected("duplicate completed learning idempotency key")
            }
            if (entry.plan.id in liveIds) return rejected("learning mutation id is both live and completed")
            if (entry.plan.idempotencyKey in liveKeys) {
                return rejected("learning idempotency key is both live and completed")
            }
        }

        return LearningApplicationMutationRestorationResult.Restored(
            LearningApplicationMutationRestoredState(
                liveEntries = liveEntries.sortedWith(
                    compareBy<LearningPreparedPersistentState> { it.plan.createdAt }
                        .thenBy { it.plan.id.value }
                ),
                completedEntries = completedEntries,
                highWatermark = highWatermark
            )
        )
    }

    private fun downstreamMatchesTarget(
        downstream: LearningApplicationDownstreamReference,
        target: LearningApplicationTarget
    ): Boolean = when (target) {
        LearningApplicationTarget.MEMORY -> downstream is LearningApplicationDownstreamReference.Memory
        LearningApplicationTarget.KNOWLEDGE -> downstream is LearningApplicationDownstreamReference.Knowledge
    }

    private fun rejected(reason: String) = LearningApplicationMutationRestorationResult.Rejected(reason)
}
