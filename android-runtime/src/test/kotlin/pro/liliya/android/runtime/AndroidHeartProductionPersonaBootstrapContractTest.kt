package pro.liliya.android.runtime

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.junit.Test
import pro.liliya.core.diagnostics.DiagnosticRecorder
import pro.liliya.core.diagnostics.InMemoryDiagnosticSink
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.identity.SelfGeneration
import pro.liliya.core.identity.SelfIdentity
import pro.liliya.core.identity.SelfIdentityId
import pro.liliya.core.identity.SelfIdentitySnapshot
import pro.liliya.core.identity.SelfInstallResult
import pro.liliya.core.identity.SelfName
import pro.liliya.core.identity.SelfOrigin
import pro.liliya.core.identity.SelfOwnership
import pro.liliya.core.identity.SelfSourceId
import pro.liliya.core.identity.SelfSourceReference
import pro.liliya.core.logging.CorrelationIdGenerator
import pro.liliya.core.logging.InMemoryLogWriter
import pro.liliya.core.logging.StructuredLogger
import pro.liliya.core.observability.LoggerProvider
import pro.liliya.core.personality.PersonalityAttribute
import pro.liliya.core.personality.PersonalityAttributeKey
import pro.liliya.core.personality.PersonalityAttributeValue
import pro.liliya.core.personality.PersonalityGeneration
import pro.liliya.core.personality.PersonalityInstallResult
import pro.liliya.core.personality.PersonalityOwnership
import pro.liliya.core.personality.PersonalityProfile
import pro.liliya.core.personality.PersonalityProfileId
import pro.liliya.core.personality.PersonalityProfileSnapshot
import pro.liliya.core.personality.PersonalitySourceId
import pro.liliya.core.personality.PersonalitySourceReference
import pro.liliya.core.personality.PersonalityTarget

class AndroidHeartProductionPersonaBootstrapContractTest {

    @Test
    fun successful_bootstrap_installs_exact_self_then_personality_targeting_fresh_self() {
        val ready = assertIs<AndroidHeartProductionPersonaCreateResult.Ready>(
            AndroidHeartProductionPersonaBootstrap.create(
                foundation = foundation(),
                definition = definition()
            )
        ).composition

        val self = requireNotNull(ready.selfSnapshots.current())
        val personality = ready.personalitySnapshots.snapshot().single()

        assertEquals("liliya-self", self.identity.id.value)
        assertEquals("Liliya", self.identity.name.value)
        assertEquals(ready.installedSelf, self)
        assertEquals(ready.installedPersonality, personality)
        assertEquals(
            PersonalityTarget.Self(
                identityId = self.identity.id,
                generation = self.generation
            ),
            personality.profile.target
        )
    }

    @Test
    fun declarative_definition_detaches_attributes_and_redacts_private_values() {
        val attributes = mutableListOf(
            PersonalityAttribute(
                PersonalityAttributeKey("tone"),
                PersonalityAttributeValue("PRIVATE-WARM")
            )
        )
        val definition = definition(
            selfName = "PRIVATE-LILIYA",
            attributes = attributes
        )

        attributes += PersonalityAttribute(
            PersonalityAttributeKey("style"),
            PersonalityAttributeValue("PRIVATE-NEW")
        )

        assertEquals(1, definition.personalityAttributes.size)
        val rendered = definition.toString()
        assertFalse("PRIVATE-LILIYA" in rendered)
        assertFalse("PRIVATE-WARM" in rendered)
        assertFalse("PRIVATE-NEW" in rendered)
        assertTrue("personalityAttributeCount=1" in rendered)
    }

    @Test
    fun definition_contains_no_runtime_generation_input_surface() {
        val methodNames = AndroidHeartProductionPersonaDefinition::class.java.methods
            .map { it.name.lowercase() }
            .toSet()

        assertFalse(methodNames.any { "selfgeneration" in it })
        assertFalse(methodNames.any { "personalitygeneration" in it })
    }

    @Test
    fun configured_bounds_fail_closed_before_install() {
        val result = assertIs<AndroidHeartProductionPersonaCreateResult.Rejected>(
            AndroidHeartProductionPersonaBootstrap.create(
                foundation = foundation(),
                definition = definition(selfName = "Liliya"),
                limits = AndroidHeartProductionPersonaLimits(maxSelfNameChars = 3)
            )
        )

        assertEquals(
            AndroidHeartProductionPersonaCreateFailure.DEFINITION_REJECTED,
            result.reason
        )
    }

    @Test
    fun self_install_rejection_fails_before_personality_install() {
        var personalityCalls = 0
        val result = assertIs<AndroidHeartProductionPersonaCreateResult.Rejected>(
            AndroidHeartProductionPersonaBootstrap.createInternal(
                definition = definition(),
                self = object : AndroidHeartProductionPersonaSelfPort {
                    override fun install(identity: SelfIdentity): SelfInstallResult =
                        SelfInstallResult.Rejected("rejected")
                    override fun inspect(): SelfIdentitySnapshot? = null
                },
                personality = object : AndroidHeartProductionPersonaPersonalityPort {
                    override fun install(profile: PersonalityProfile): PersonalityInstallResult {
                        personalityCalls += 1
                        error("must not install personality")
                    }
                    override fun inspect(id: PersonalityProfileId): PersonalityProfileSnapshot? = null
                    override fun snapshotEntries(): List<PersonalityProfileSnapshot> = emptyList()
                }
            )
        )

        assertEquals(
            AndroidHeartProductionPersonaCreateFailure.SELF_INSTALL_REJECTED,
            result.reason
        )
        assertEquals(0, personalityCalls)
    }

    @Test
    fun self_snapshot_mismatch_fails_closed() {
        val expected = selfIdentity()
        val ownership = selfOwnership(expected, SelfGeneration(1))
        val result = assertIs<AndroidHeartProductionPersonaCreateResult.Rejected>(
            AndroidHeartProductionPersonaBootstrap.createInternal(
                definition = definition(),
                self = object : AndroidHeartProductionPersonaSelfPort {
                    override fun install(identity: SelfIdentity): SelfInstallResult =
                        SelfInstallResult.Installed(ownership)
                    override fun inspect(): SelfIdentitySnapshot =
                        SelfIdentitySnapshot(identity = expected, generation = SelfGeneration(2))
                },
                personality = noPersonalityCalls()
            )
        )

        assertEquals(
            AndroidHeartProductionPersonaCreateFailure.SELF_SNAPSHOT_MISMATCH,
            result.reason
        )
    }

    @Test
    fun personality_install_rejection_fails_closed_after_exact_self() {
        val identity = selfIdentity()
        val generation = SelfGeneration(4)
        val result = assertIs<AndroidHeartProductionPersonaCreateResult.Rejected>(
            AndroidHeartProductionPersonaBootstrap.createInternal(
                definition = definition(),
                self = exactSelfPort(identity, generation),
                personality = object : AndroidHeartProductionPersonaPersonalityPort {
                    override fun install(profile: PersonalityProfile): PersonalityInstallResult =
                        PersonalityInstallResult.Rejected("rejected")
                    override fun inspect(id: PersonalityProfileId): PersonalityProfileSnapshot? = null
                    override fun snapshotEntries(): List<PersonalityProfileSnapshot> = emptyList()
                }
            )
        )

        assertEquals(
            AndroidHeartProductionPersonaCreateFailure.PERSONALITY_INSTALL_REJECTED,
            result.reason
        )
    }

    @Test
    fun personality_snapshot_mismatch_fails_closed() {
        val identity = selfIdentity()
        val selfGeneration = SelfGeneration(6)
        var installed: PersonalityProfile? = null
        val personalityOwnership = object : PersonalityOwnership {
            override val profile: PersonalityProfile
                get() = requireNotNull(installed)
            override val generation = PersonalityGeneration(1)
            override fun remove(): Boolean = true
        }
        val result = assertIs<AndroidHeartProductionPersonaCreateResult.Rejected>(
            AndroidHeartProductionPersonaBootstrap.createInternal(
                definition = definition(),
                self = exactSelfPort(identity, selfGeneration),
                personality = object : AndroidHeartProductionPersonaPersonalityPort {
                    override fun install(profile: PersonalityProfile): PersonalityInstallResult {
                        installed = profile
                        return PersonalityInstallResult.Installed(personalityOwnership)
                    }
                    override fun inspect(id: PersonalityProfileId): PersonalityProfileSnapshot =
                        PersonalityProfileSnapshot(
                            profile = requireNotNull(installed),
                            generation = PersonalityGeneration(2)
                        )
                    override fun snapshotEntries(): List<PersonalityProfileSnapshot> = emptyList()
                }
            )
        )

        assertEquals(
            AndroidHeartProductionPersonaCreateFailure.PERSONALITY_SNAPSHOT_MISMATCH,
            result.reason
        )
    }

    @Test
    fun separate_bootstraps_retarget_personality_to_each_fresh_self_lifecycle() {
        val first = assertIs<AndroidHeartProductionPersonaCreateResult.Ready>(
            AndroidHeartProductionPersonaBootstrap.create(foundation(), definition())
        ).composition
        val second = assertIs<AndroidHeartProductionPersonaCreateResult.Ready>(
            AndroidHeartProductionPersonaBootstrap.create(foundation(), definition())
        ).composition

        assertNotSame(first, second)
        assertEquals(
            PersonalityTarget.Self(
                first.installedSelf.identity.id,
                first.installedSelf.generation
            ),
            first.installedPersonality.profile.target
        )
        assertEquals(
            PersonalityTarget.Self(
                second.installedSelf.identity.id,
                second.installedSelf.generation
            ),
            second.installedPersonality.profile.target
        )
    }

    @Test
    fun composition_rendering_exposes_no_self_name_or_personality_values() {
        val ready = assertIs<AndroidHeartProductionPersonaCreateResult.Ready>(
            AndroidHeartProductionPersonaBootstrap.create(
                foundation(),
                definition(
                    selfName = "PRIVATE-LILIYA",
                    attributes = listOf(
                        PersonalityAttribute(
                            PersonalityAttributeKey("tone"),
                            PersonalityAttributeValue("PRIVATE-PERSONALITY")
                        )
                    )
                )
            )
        ).composition

        val rendered = ready.toString()
        assertFalse("PRIVATE-LILIYA" in rendered)
        assertFalse("PRIVATE-PERSONALITY" in rendered)
    }

    private fun definition(
        selfName: String = "Liliya",
        attributes: List<PersonalityAttribute> = listOf(
            PersonalityAttribute(
                PersonalityAttributeKey("tone"),
                PersonalityAttributeValue("warm and concise")
            ),
            PersonalityAttribute(
                PersonalityAttributeKey("identity"),
                PersonalityAttributeValue("single coherent assistant persona")
            )
        )
    ): AndroidHeartProductionPersonaDefinition =
        AndroidHeartProductionPersonaDefinition(
            selfIdentityId = SelfIdentityId("liliya-self"),
            selfName = SelfName(selfName),
            selfSourceId = SelfSourceId("product-persona"),
            selfSourceReference = SelfSourceReference("liliya-v0.1"),
            selfCreatedAt = Instant.parse("2026-09-08T00:00:00Z"),
            personalityProfileId = PersonalityProfileId("liliya-personality"),
            personalityAttributes = attributes,
            personalitySourceId = PersonalitySourceId("product-persona"),
            personalitySourceReference = PersonalitySourceReference("liliya-v0.1"),
            personalityCreatedAt = Instant.parse("2026-09-08T00:00:01Z")
        )

    private fun selfIdentity(): SelfIdentity =
        SelfIdentity(
            id = SelfIdentityId("liliya-self"),
            name = SelfName("Liliya"),
            origin = SelfOrigin.Declared(
                SelfSourceId("product-persona"),
                SelfSourceReference("liliya-v0.1")
            ),
            createdAt = Instant.parse("2026-09-08T00:00:00Z")
        )

    private fun selfOwnership(
        identity: SelfIdentity,
        generation: SelfGeneration
    ): SelfOwnership = object : SelfOwnership {
        override val identity: SelfIdentity = identity
        override val generation: SelfGeneration = generation
        override fun remove(): Boolean = true
    }

    private fun exactSelfPort(
        identity: SelfIdentity,
        generation: SelfGeneration
    ): AndroidHeartProductionPersonaSelfPort =
        object : AndroidHeartProductionPersonaSelfPort {
            private val ownership = selfOwnership(identity, generation)
            override fun install(identity: SelfIdentity): SelfInstallResult =
                SelfInstallResult.Installed(ownership)
            override fun inspect(): SelfIdentitySnapshot =
                SelfIdentitySnapshot(ownership.identity, ownership.generation)
        }

    private fun noPersonalityCalls(): AndroidHeartProductionPersonaPersonalityPort =
        object : AndroidHeartProductionPersonaPersonalityPort {
            override fun install(profile: PersonalityProfile): PersonalityInstallResult =
                error("personality must not be called")
            override fun inspect(id: PersonalityProfileId): PersonalityProfileSnapshot? =
                error("personality must not be called")
            override fun snapshotEntries(): List<PersonalityProfileSnapshot> =
                error("personality must not be called")
        }

    private fun foundation(): FoundationComposition {
        val logs = InMemoryLogWriter()
        return FoundationComposition(
            diagnostics = DiagnosticRecorder(InMemoryDiagnosticSink()),
            loggerProvider = LoggerProvider { context -> StructuredLogger(context, logs) },
            correlationIds = CorrelationIdGenerator { "production-persona-test" }
        )
    }
}
