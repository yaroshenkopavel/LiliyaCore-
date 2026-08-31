package pro.liliya.core.encryption

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.persistence.PersistentEntityId
import pro.liliya.core.persistence.PersistentGeneration
import pro.liliya.core.persistence.PersistentStoreId

class CognitiveEncryptionReadinessContractTest {
    @Test
    fun old_dek_cannot_retire_until_all_committed_dependencies_migrate() {
        val registry = CognitiveCiphertextDependencyRegistry()
        val oldDek = dek("dek-old", 1)
        val newDek = dek("dek-new", 2)
        val first = dependency("store-a", "entity-a", 1, oldDek)
        val second = dependency("store-a", "entity-b", 1, oldDek)

        assertIs<CognitiveDependencyUpdateResult.Updated>(registry.registerCommitted(first))
        assertIs<CognitiveDependencyUpdateResult.Updated>(registry.registerCommitted(second))
        assertFalse(registry.canRetire(oldDek))

        assertIs<CognitiveDependencyUpdateResult.Updated>(
            registry.migrateCommitted(first, first.copy(entityGeneration = PersistentGeneration(2), dek = newDek))
        )
        assertFalse(registry.canRetire(oldDek))

        assertIs<CognitiveDependencyUpdateResult.Updated>(
            registry.migrateCommitted(second, second.copy(entityGeneration = PersistentGeneration(2), dek = newDek))
        )
        assertTrue(registry.canRetire(oldDek))
        assertFalse(registry.canRetire(newDek))
    }

    @Test
    fun stale_worker_cannot_overwrite_newer_committed_generation() {
        val registry = CognitiveCiphertextDependencyRegistry()
        val old = dependency("store-a", "entity-a", 1, dek("dek-old", 1))
        val migrated = old.copy(entityGeneration = PersistentGeneration(2), dek = dek("dek-new", 2))
        val staleReplacement = old.copy(entityGeneration = PersistentGeneration(3), dek = dek("dek-stale", 3))

        assertIs<CognitiveDependencyUpdateResult.Updated>(registry.registerCommitted(old))
        assertIs<CognitiveDependencyUpdateResult.Updated>(registry.migrateCommitted(old, migrated))

        val rejected = assertIs<CognitiveDependencyUpdateResult.Rejected>(
            registry.migrateCommitted(old, staleReplacement)
        )
        assertEquals(CognitiveDependencyUpdateFailure.EXPECTED_DEPENDENCY_MISMATCH, rejected.reason)
        assertEquals(listOf(migrated), registry.snapshot())
    }

    @Test
    fun concurrent_registration_preserves_single_exact_owner_per_entity() {
        val registry = CognitiveCiphertextDependencyRegistry()
        val threads = 16
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        val results = java.util.Collections.synchronizedList(mutableListOf<CognitiveDependencyUpdateResult>())

        repeat(threads) { index ->
            executor.submit {
                ready.countDown()
                start.await()
                results += registry.registerCommitted(
                    dependency("store-a", "entity-a", index + 1L, dek("dek-$index", index + 1L))
                )
            }
        }

        ready.await()
        start.countDown()
        executor.shutdown()
        while (!executor.isTerminated) Thread.yield()

        assertEquals(1, results.count { it is CognitiveDependencyUpdateResult.Updated })
        assertEquals(threads - 1, results.count { it is CognitiveDependencyUpdateResult.Rejected })
        assertEquals(1, registry.snapshot().size)
    }

    @Test
    fun recovery_classification_is_explicit_and_never_collapses_key_loss_to_empty_state() {
        val missing = CognitiveRecoveryClassifier.classify(
            CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.PROTECTOR_MISSING)
        )
        val invalidated = CognitiveRecoveryClassifier.classify(
            CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.PROTECTOR_INVALIDATED)
        )
        val auth = CognitiveRecoveryClassifier.classify(
            CognitiveEncryptionResult.Rejected(
                CognitiveEncryptionFailureCategory.CIPHERTEXT_AUTHENTICATION_FAILED
            )
        )

        assertEquals(CognitiveRecoveryState.PROTECTOR_MISSING, missing.state)
        assertEquals(CognitiveRecoveryState.PROTECTOR_INVALIDATED, invalidated.state)
        assertEquals(CognitiveRecoveryState.AUTHENTICATION_FAILED, auth.state)
    }

    @Test
    fun readiness_rendering_redacts_store_entity_and_key_identifiers() {
        val dependency = dependency("secret-store", "secret-entity", 1, dek("secret-dek", 1))
        val rendered = dependency.toString()

        assertFalse(rendered.contains("secret-store"))
        assertFalse(rendered.contains("secret-entity"))
        assertFalse(rendered.contains("secret-dek"))
        assertTrue(rendered.contains("[redacted]"))
    }

    private fun dependency(
        store: String,
        entity: String,
        generation: Long,
        dek: CognitiveDekReference
    ) = CognitiveCiphertextDependency(
        storeId = PersistentStoreId(store),
        entityId = PersistentEntityId(entity),
        entityGeneration = PersistentGeneration(generation),
        dek = dek
    )

    private fun dek(id: String, generation: Long) = CognitiveDekReference(
        CognitiveDekId(id),
        CognitiveDekGeneration(generation)
    )
}
