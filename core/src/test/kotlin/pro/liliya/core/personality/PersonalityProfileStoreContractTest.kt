package pro.liliya.core.personality

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.identity.SelfGeneration
import pro.liliya.core.identity.SelfIdentityId
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.LogContextPropagation
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.CoreObservability
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonalityProfileStoreContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val diagnostics: InMemoryDiagnosticSink,
        val store: PersonalityProfileStore,
        val sequence: AtomicInteger
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val sequence = AtomicInteger(0)
        val observability = CoreObservability(
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            diagnostics = DiagnosticRecorder(diagnostics)
        )
        return Fixture(logs, diagnostics, PersonalityProfileStore(observability), sequence)
    }

    private fun context(f: Fixture) = LogContextPropagation.root(
        module = "CORE",
        component = "Personality",
        operation = "testPersonalityProfile",
        generator = CorrelationIdGenerator { "personality-${f.sequence.incrementAndGet()}" }
    )

    private fun profile(
        id: String = "profile-liliya",
        attributes: List<PersonalityAttribute> = listOf(
            PersonalityAttribute(PersonalityAttributeKey("tone"), PersonalityAttributeValue("warm")),
            PersonalityAttribute(PersonalityAttributeKey("detail"), PersonalityAttributeValue("concise"))
        ),
        createdAt: Instant = Instant.parse("2026-08-28T21:00:00Z")
    ) = PersonalityProfile(
        id = PersonalityProfileId(id),
        target = PersonalityTarget.Self(
            identityId = SelfIdentityId("self-liliya"),
            generation = SelfGeneration(7L)
        ),
        attributes = attributes,
        provenance = PersonalityProvenance(
            sourceId = PersonalitySourceId("bootstrap"),
            sourceReference = PersonalitySourceReference("personality-config")
        ),
        createdAt = createdAt
    )

    @Test
    fun register_read_and_remove_use_exact_ownership() {
        val f = fixture()
        val profile = profile()
        val registration = assertIs<PersonalityProfileRegistrationResult.Registered>(
            f.store.register(profile, context(f))
        ).registration

        assertEquals(profile, f.store.find(profile.id))
        assertEquals(registration.generation, f.store.inspect(profile.id)?.generation)
        assertTrue(registration.remove(context(f)))
        assertNull(f.store.find(profile.id))
        assertEquals(listOf("PERSONALITY_PROFILE_REGISTERED", "PERSONALITY_PROFILE_REMOVED"), f.logs.snapshot().map { it.marker })
    }

    @Test
    fun duplicate_profile_id_is_rejected_without_replacement() {
        val f = fixture()
        val first = profile()
        val second = profile(attributes = listOf(PersonalityAttribute(PersonalityAttributeKey("tone"), PersonalityAttributeValue("formal"))))
        assertIs<PersonalityProfileRegistrationResult.Registered>(f.store.register(first, context(f)))
        assertIs<PersonalityProfileRegistrationResult.Rejected>(f.store.register(second, context(f)))
        assertEquals(first, f.store.find(first.id))
    }

    @Test
    fun stale_registration_cannot_remove_same_id_replacement() {
        val f = fixture()
        val stale = assertIs<PersonalityProfileRegistrationResult.Registered>(f.store.register(profile(), context(f))).registration
        assertTrue(stale.remove(context(f)))
        val replacement = profile(attributes = listOf(PersonalityAttribute(PersonalityAttributeKey("tone"), PersonalityAttributeValue("formal"))))
        val current = assertIs<PersonalityProfileRegistrationResult.Registered>(f.store.register(replacement, context(f))).registration
        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove(context(f)))
        assertEquals(replacement, f.store.find(replacement.id))
    }

    @Test
    fun attributes_are_defensively_copied() {
        val mutable = mutableListOf(PersonalityAttribute(PersonalityAttributeKey("tone"), PersonalityAttributeValue("warm")))
        val profile = profile(attributes = mutable)
        mutable += PersonalityAttribute(PersonalityAttributeKey("detail"), PersonalityAttributeValue("verbose"))
        assertEquals(1, profile.attributes.size)
        assertEquals("tone", profile.attributes.single().key.value)
    }

    @Test
    fun self_target_is_structural_without_self_lookup() {
        val f = fixture()
        val profile = PersonalityProfile(
            id = PersonalityProfileId("profile-structural"),
            target = PersonalityTarget.Self(SelfIdentityId("self-not-installed"), SelfGeneration(999L)),
            attributes = listOf(PersonalityAttribute(PersonalityAttributeKey("tone"), PersonalityAttributeValue("warm"))),
            provenance = PersonalityProvenance(PersonalitySourceId("caller")),
            createdAt = Instant.parse("2026-08-28T21:01:00Z")
        )
        assertIs<PersonalityProfileRegistrationResult.Registered>(f.store.register(profile, context(f)))
        assertEquals(profile, f.store.find(profile.id))
    }

    @Test
    fun observability_does_not_expose_attribute_values_or_create_trust_authority_semantics() {
        val f = fixture()
        assertIs<PersonalityProfileRegistrationResult.Registered>(f.store.register(profile(), context(f)))
        val metadata = f.logs.snapshot().first().metadata
        assertEquals("2", metadata["personalityAttributeCount"])
        assertFalse(metadata.values.any { it == "warm" || it == "concise" })
        assertFalse(metadata.keys.any { it.contains("authority", true) || it.contains("trust", true) || it.contains("confidence", true) || it.contains("truth", true) })
    }

    @Test
    fun profile_string_redacts_attribute_values() {
        val rendered = profile().toString()
        assertTrue(rendered.contains("attributeCount=2"))
        assertFalse(rendered.contains("warm"))
        assertFalse(rendered.contains("concise"))
    }

    @Test
    fun duplicate_attribute_keys_are_rejected() {
        val duplicate = listOf(
            PersonalityAttribute(PersonalityAttributeKey("tone"), PersonalityAttributeValue("warm")),
            PersonalityAttribute(PersonalityAttributeKey("tone"), PersonalityAttributeValue("formal"))
        )
        runCatching { profile(attributes = duplicate) }.onSuccess { error("duplicate attribute keys must fail") }
    }

    @Test
    fun concurrent_same_id_registration_has_exactly_one_winner() {
        val f = fixture()
        val workers = 32
        val executor = Executors.newFixedThreadPool(workers)
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val results = ConcurrentLinkedQueue<PersonalityProfileRegistrationResult>()
        repeat(workers) { index ->
            executor.execute {
                ready.countDown()
                start.await()
                try {
                    results += f.store.register(
                        profile(attributes = listOf(PersonalityAttribute(PersonalityAttributeKey("tone"), PersonalityAttributeValue("tone-$index")))),
                        context(f)
                    )
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()
        assertEquals(1, results.count { it is PersonalityProfileRegistrationResult.Registered })
        assertEquals(workers - 1, results.count { it is PersonalityProfileRegistrationResult.Rejected })
    }
}
