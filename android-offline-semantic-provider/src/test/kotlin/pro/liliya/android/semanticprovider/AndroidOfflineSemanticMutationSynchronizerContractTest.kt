package pro.liliya.android.semanticprovider

import java.time.Instant
import kotlin.test.assertEquals
import org.junit.Test
import pro.liliya.core.knowledge.KnowledgeGeneration
import pro.liliya.core.knowledge.KnowledgeItem
import pro.liliya.core.knowledge.KnowledgeItemId
import pro.liliya.core.knowledge.KnowledgeItemSnapshot
import pro.liliya.core.knowledge.KnowledgeOrigin
import pro.liliya.core.knowledge.KnowledgeSourceId
import pro.liliya.core.memory.MemoryGeneration
import pro.liliya.core.memory.MemoryProvenance
import pro.liliya.core.memory.MemoryRecord
import pro.liliya.core.memory.MemoryRecordId
import pro.liliya.core.memory.MemoryRecordSnapshot
import pro.liliya.core.memory.MemorySourceId

class AndroidOfflineSemanticMutationSynchronizerContractTest {

    @Test
    fun post_commit_sync_is_rejected_until_semantic_assembly_is_ready() {
        val assembly = AndroidOfflineSemanticProviderAssembly.create()
        val synchronizer = AndroidOfflineSemanticMutationSynchronizer.create(assembly)

        assertEquals(
            AndroidOfflineSemanticMutationSyncResult.NotReady,
            synchronizer.addMemory(memory(1))
        )
        assertEquals(
            AndroidOfflineSemanticMutationSyncResult.NotReady,
            synchronizer.addKnowledge(knowledge(1))
        )
        assertEquals(
            AndroidOfflineSemanticMutationSyncResult.NotReady,
            synchronizer.replaceMemory(memory(1), memory(2))
        )
        assertEquals(
            AndroidOfflineSemanticMutationSyncResult.NotReady,
            synchronizer.replaceKnowledge(knowledge(1), knowledge(2))
        )
        assertEquals(
            AndroidOfflineSemanticMutationSyncResult.NotReady,
            synchronizer.removeMemory(memory(1))
        )
        assertEquals(
            AndroidOfflineSemanticMutationSyncResult.NotReady,
            synchronizer.removeKnowledge(knowledge(1))
        )
        assertEquals(
            AndroidOfflineSemanticProviderState.UNAVAILABLE,
            assembly.state()
        )
    }

    @Test
    fun exact_committed_generation_is_part_of_every_public_sync_command() {
        val previousMemory = memory(7)
        val replacementMemory = memory(8)
        val previousKnowledge = knowledge(11)
        val replacementKnowledge = knowledge(12)

        assertEquals(
            MemoryGeneration(7),
            previousMemory.generation
        )
        assertEquals(
            MemoryGeneration(8),
            replacementMemory.generation
        )
        assertEquals(
            KnowledgeGeneration(11),
            previousKnowledge.generation
        )
        assertEquals(
            KnowledgeGeneration(12),
            replacementKnowledge.generation
        )
    }

    private fun memory(generation: Long): MemoryRecordSnapshot =
        MemoryRecordSnapshot(
            record = MemoryRecord(
                id = MemoryRecordId("memory-sync"),
                provenance = MemoryProvenance(MemorySourceId("mutation-sync-test")),
                content = "memory generation $generation",
                createdAt = BASE.plusSeconds(generation)
            ),
            generation = MemoryGeneration(generation)
        )

    private fun knowledge(generation: Long): KnowledgeItemSnapshot =
        KnowledgeItemSnapshot(
            item = KnowledgeItem(
                id = KnowledgeItemId("knowledge-sync"),
                origin = KnowledgeOrigin.Declared(KnowledgeSourceId("mutation-sync-test")),
                content = "knowledge generation $generation",
                createdAt = BASE.plusSeconds(generation)
            ),
            generation = KnowledgeGeneration(generation)
        )

    private companion object {
        val BASE: Instant = Instant.parse("2026-09-05T13:00:00Z")
    }
}
