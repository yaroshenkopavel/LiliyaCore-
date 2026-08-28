package pro.liliya.core.personality

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonalityReadinessContractTest {
    private data class Fixture(
        val logs: InMemoryLogWriter,
        val composition: PersonalityComposition
    )

    private fun fixture(prefix: String): Fixture {
        val logs = InMemoryLogWriter()
        val diagnostics = InMemoryDiagnosticSink()
        val sequence = AtomicInteger(0)
        val foundation = FoundationComposition(
            diagnostics = DiagnosticRecorder(diagnostics),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "$prefix-${sequence.incrementAndGet()}" }
        )
        return Fixture(logs, PersonalityComposition(foundation))
    }

    private fun profile(
        id: String = "profile-liliya",
        value: String = "warm",
        createdAt: Instant = Instant.parse("2001-02-03T04:05:06Z"),
        selfId: String = "self-liliya",
        selfGeneration: Long = 7L
    ) = PersonalityProfile(
        id = PersonalityProfileId(id),
        target = PersonalityTarget.Self(
            identityId = SelfIdentityId(selfId),
            generation = SelfGeneration(selfGeneration)
        ),
        attributes = listOf(
            PersonalityAttribute(
                key = PersonalityAttributeKey("tone"),
                value = PersonalityAttributeValue(value)
            )
        ),
        provenance = PersonalityProvenance(
            sourceId = PersonalitySourceId("caller"),
            sourceReference = PersonalitySourceReference("readiness")
        ),
        createdAt = createdAt
    )

    @Test
    fun created_at_is_caller_supplied_and_preserved_without_runtime_replacement() {
        val f = fixture("created-at")
        val callerTime = Instant.parse("1999-12-31T23:59:59Z")
        val p = profile(createdAt = callerTime)

        assertIs<PersonalityInstallResult.Installed>(f.composition.install(p))

        assertEquals(callerTime, f.composition.find(p.id)?.createdAt)
        assertEquals(callerTime, f.composition.inspect(p.id)?.profile?.createdAt)
    }

    @Test
    fun independent_compositions_do_not_share_profiles_even_with_same_profile_id() {
        val left = fixture("left")
        val right = fixture("right")
        val leftProfile = profile(value = "warm")
        val rightProfile = profile(value = "formal")

        assertIs<PersonalityInstallResult.Installed>(left.composition.install(leftProfile))
        assertNull(right.composition.find(leftProfile.id))

        assertIs<PersonalityInstallResult.Installed>(right.composition.install(rightProfile))
        assertEquals(leftProfile, left.composition.find(leftProfile.id))
        assertEquals(rightProfile, right.composition.find(rightProfile.id))
    }

    @Test
    fun equal_numeric_generations_across_compositions_do_not_create_shared_ownership() {
        val left = fixture("left-generation")
        val right = fixture("right-generation")
        val leftOwnership = assertIs<PersonalityInstallResult.Installed>(
            left.composition.install(profile(value = "warm"))
        ).ownership
        val rightOwnership = assertIs<PersonalityInstallResult.Installed>(
            right.composition.install(profile(value = "formal"))
        ).ownership

        assertEquals(leftOwnership.generation.value, rightOwnership.generation.value)
        assertTrue(leftOwnership.remove())
        assertNull(left.composition.find(leftOwnership.profile.id))
        assertEquals(rightOwnership.profile, right.composition.find(rightOwnership.profile.id))
        assertTrue(rightOwnership.remove())
    }

    @Test
    fun profile_attributes_are_structural_data_only_and_create_no_implicit_behavior_or_trust_effect() {
        val f = fixture("structural")
        val p = profile(
            value = "always-be-warm",
            selfId = "self-not-installed",
            selfGeneration = 999L
        )

        assertIs<PersonalityInstallResult.Installed>(f.composition.install(p))
        assertEquals(p, f.composition.find(p.id))

        val metadata = f.logs.snapshot().first().metadata
        assertEquals("self-not-installed", metadata["selfIdentityId"])
        assertEquals("999", metadata["selfGeneration"])
        assertFalse(metadata.values.any { it == "always-be-warm" })
        assertFalse(metadata.keys.any {
            it.contains("behavior", ignoreCase = true) ||
                it.contains("prompt", ignoreCase = true) ||
                it.contains("trust", ignoreCase = true) ||
                it.contains("authority", ignoreCase = true) ||
                it.contains("decision", ignoreCase = true) ||
                it.contains("execution", ignoreCase = true)
        })
    }

    @Test
    fun profile_string_remains_redacted_at_readiness_boundary() {
        val rendered = profile(value = "private-personality-value").toString()

        assertTrue(rendered.contains("attributeCount=1"))
        assertFalse(rendered.contains("private-personality-value"))
    }
}
