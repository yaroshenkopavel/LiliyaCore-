package pro.liliya.core.personality

import java.lang.reflect.Modifier
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.identity.SelfGeneration
import pro.liliya.core.identity.SelfIdentityId
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonalityCompositionContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val diagnostics: InMemoryDiagnosticSink,
        val composition: PersonalityComposition
    )

    private fun fixture(): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "personality-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, diagnostics, PersonalityComposition(foundation))
    }

    private fun profile(
        id: String = "profile-self",
        value: String = "warm"
    ) = PersonalityProfile(
        id = PersonalityProfileId(id),
        target = PersonalityTarget.Self(
            identityId = SelfIdentityId("self-liliya"),
            generation = SelfGeneration(7L)
        ),
        attributes = listOf(
            PersonalityAttribute(PersonalityAttributeKey("tone"), PersonalityAttributeValue(value))
        ),
        provenance = PersonalityProvenance(
            sourceId = PersonalitySourceId("bootstrap"),
            sourceReference = PersonalitySourceReference("personality-config")
        ),
        createdAt = Instant.parse("2026-08-29T00:10:00Z")
    )

    @Test
    fun install_read_and_remove_are_owned_by_composition() {
        val f = fixture()
        val p = profile()
        val ownership = assertIs<PersonalityInstallResult.Installed>(f.composition.install(p)).ownership

        assertEquals(p, f.composition.find(p.id))
        assertEquals(ownership.generation, f.composition.inspect(p.id)?.generation)
        assertTrue(f.composition.contains(p.id))
        assertTrue(ownership.remove())
        assertNull(f.composition.find(p.id))
        assertFalse(f.composition.contains(p.id))
    }

    @Test
    fun duplicate_profile_is_rejected_without_replacement() {
        val f = fixture()
        val first = profile(value = "warm")
        val second = profile(value = "formal")

        assertIs<PersonalityInstallResult.Installed>(f.composition.install(first))
        assertIs<PersonalityInstallResult.Rejected>(f.composition.install(second))
        assertEquals(first, f.composition.find(first.id))
    }

    @Test
    fun stale_ownership_cannot_remove_replacement_profile() {
        val f = fixture()
        val stale = assertIs<PersonalityInstallResult.Installed>(f.composition.install(profile())).ownership
        assertTrue(stale.remove())

        val replacement = profile(value = "formal")
        val current = assertIs<PersonalityInstallResult.Installed>(f.composition.install(replacement)).ownership
        assertNotEquals(stale.generation, current.generation)
        assertFalse(stale.remove())
        assertEquals(replacement, f.composition.find(replacement.id))
    }

    @Test
    fun install_and_remove_use_fresh_foundation_contexts() {
        val f = fixture()
        val ownership = assertIs<PersonalityInstallResult.Installed>(f.composition.install(profile())).ownership
        assertTrue(ownership.remove())

        val correlations = f.logs.snapshot().map { it.context.correlationId }
        assertEquals(2, correlations.size)
        assertNotEquals(correlations[0], correlations[1])
    }

    @Test
    fun self_target_remains_structural_and_values_stay_out_of_lifecycle_metadata() {
        val f = fixture()
        val p = PersonalityProfile(
            id = PersonalityProfileId("profile-structural"),
            target = PersonalityTarget.Self(SelfIdentityId("self-not-installed"), SelfGeneration(999L)),
            attributes = listOf(
                PersonalityAttribute(PersonalityAttributeKey("tone"), PersonalityAttributeValue("secret-value"))
            ),
            provenance = PersonalityProvenance(PersonalitySourceId("caller")),
            createdAt = Instant.parse("2026-08-29T00:11:00Z")
        )

        assertIs<PersonalityInstallResult.Installed>(f.composition.install(p))
        assertEquals(p, f.composition.find(p.id))

        val metadata = f.logs.snapshot().first().metadata
        assertEquals("self-not-installed", metadata["selfIdentityId"])
        assertEquals("999", metadata["selfGeneration"])
        assertEquals("1", metadata["personalityAttributeCount"])
        assertFalse(metadata.values.any { it == "secret-value" })
        assertFalse(metadata.keys.any {
            it.contains("trust", true) || it.contains("authority", true) ||
                it.contains("behavior", true) || it.contains("prompt", true)
        })
    }

    @Test
    fun public_api_does_not_expose_raw_store_or_registration() {
        val forbidden = setOf(
            PersonalityProfileStore::class.java,
            PersonalityProfileRegistration::class.java
        )
        val exposed = PersonalityComposition::class.java.methods.filter { method ->
            Modifier.isPublic(method.modifiers) && method.returnType in forbidden
        }
        assertTrue(exposed.isEmpty(), "personality API must not expose raw store internals: $exposed")
    }
}
