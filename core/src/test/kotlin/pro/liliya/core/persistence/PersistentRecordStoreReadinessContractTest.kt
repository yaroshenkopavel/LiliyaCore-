package pro.liliya.core.persistence

import java.lang.reflect.Modifier
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider

class PersistentRecordStoreReadinessContractTest {
    private fun foundation(): FoundationComposition {
        val sequence = AtomicInteger(0)
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context ->
                StructuredLogger(context, InMemoryLogWriter())
            },
            correlationIds = CorrelationIdGenerator {
                "persistence-readiness-${sequence.incrementAndGet()}"
            }
        )
    }

    private fun record(id: String) = PersistentRecord(
        id = PersistentEntityId(id),
        schemaId = PersistentSchemaId("readiness"),
        schemaVersion = PersistentSchemaVersion(1),
        payload = PersistentPayload("private-$id".encodeToByteArray()),
        createdAt = Instant.parse("2026-08-30T13:40:00Z")
    )

    @Test
    fun backend_spi_is_public_and_adapter_implementable_without_internal_state_types() {
        assertTrue(Modifier.isPublic(PersistentRecordBackend::class.java.modifiers))
        assertTrue(Modifier.isPublic(PersistentBackendState::class.java.modifiers))
        assertTrue(Modifier.isPublic(PersistentBackendEntry::class.java.modifiers))
        assertTrue(Modifier.isPublic(PersistentBackendLoadResult::class.java.modifiers))
        assertTrue(Modifier.isPublic(PersistentBackendCommitResult::class.java.modifiers))

        // PersistentStoreId is a Kotlin value class, so JVM method names may be
        // mangled while the source-level SPI remains public and implementable.
        val methods = PersistentRecordBackend::class.java.methods.map { it.name }.toSet()
        assertTrue(methods.any { it == "load" || it.startsWith("load-") })
        assertTrue(methods.any { it == "commit" || it.startsWith("commit-") })
    }

    @Test
    fun mismatched_entry_key_and_record_identity_is_corrupt() {
        val storeId = PersistentStoreId("mismatch")
        val backend = object : PersistentRecordBackend {
            override fun load(storeId: PersistentStoreId): PersistentBackendLoadResult =
                PersistentBackendLoadResult.Loaded(
                    revision = 1,
                    state = PersistentBackendState(
                        storeId = storeId,
                        highWatermark = 1,
                        entries = mapOf(
                            PersistentEntityId("map-key") to PersistentBackendEntry(
                                generation = PersistentGeneration(1),
                                record = record("record-id")
                            )
                        )
                    )
                )

            override fun commit(
                storeId: PersistentStoreId,
                expectedRevision: Long,
                state: PersistentBackendState
            ): PersistentBackendCommitResult = error("commit must not be called")
        }

        assertIs<PersistentStoreOpenResult.Corrupt>(
            PersistentRecordStore.open(foundation(), storeId, backend)
        )
    }

    @Test
    fun duplicate_live_generations_are_corrupt() {
        val storeId = PersistentStoreId("duplicate-generation")
        val backend = object : PersistentRecordBackend {
            override fun load(storeId: PersistentStoreId): PersistentBackendLoadResult =
                PersistentBackendLoadResult.Loaded(
                    revision = 2,
                    state = PersistentBackendState(
                        storeId = storeId,
                        highWatermark = 2,
                        entries = mapOf(
                            PersistentEntityId("one") to PersistentBackendEntry(
                                PersistentGeneration(2),
                                record("one")
                            ),
                            PersistentEntityId("two") to PersistentBackendEntry(
                                PersistentGeneration(2),
                                record("two")
                            )
                        )
                    )
                )

            override fun commit(
                storeId: PersistentStoreId,
                expectedRevision: Long,
                state: PersistentBackendState
            ): PersistentBackendCommitResult = error("commit must not be called")
        }

        assertIs<PersistentStoreOpenResult.Corrupt>(
            PersistentRecordStore.open(foundation(), storeId, backend)
        )
    }

    @Test
    fun non_monotonic_commit_acknowledgement_fails_closed_and_is_not_published_locally() {
        val storeId = PersistentStoreId("bad-revision")
        val backend = object : PersistentRecordBackend {
            override fun load(storeId: PersistentStoreId): PersistentBackendLoadResult =
                PersistentBackendLoadResult.Missing

            override fun commit(
                storeId: PersistentStoreId,
                expectedRevision: Long,
                state: PersistentBackendState
            ): PersistentBackendCommitResult = PersistentBackendCommitResult.Committed(1)
        }

        val store = assertIs<PersistentStoreOpenResult.Opened>(
            PersistentRecordStore.open(foundation(), storeId, backend)
        ).store

        assertIs<PersistentInstallResult.Installed>(store.install(record("one")))
        val failed = assertIs<PersistentInstallResult.Failed>(store.install(record("two")))
        assertTrue(failed.reason.contains("non-monotonic"))
        assertFalse(store.contains(PersistentEntityId("two")))
    }

    @Test
    fun readiness_api_has_no_authority_license_android_scheduler_or_cognitive_power_methods() {
        val forbidden = setOf(
            "authority", "permission", "license", "entitlement", "android", "keystore",
            "scheduler", "schedule", "retry", "execute", "executor", "capability",
            "memory", "knowledge", "reflection", "learning"
        )
        val types = listOf(
            PersistentBackendEntry::class.java,
            PersistentBackendState::class.java,
            PersistentBackendLoadResult::class.java,
            PersistentBackendCommitResult::class.java,
            PersistentRecordBackend::class.java,
            PersistentRecordStore::class.java
        )

        types.forEach { type ->
            val names = type.methods.map { it.name.lowercase() }
            assertFalse(
                names.any { name -> forbidden.any { token -> name.contains(token) } },
                type.name
            )
        }
    }
}
