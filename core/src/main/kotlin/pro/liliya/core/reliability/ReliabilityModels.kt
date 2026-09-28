package pro.liliya.core.reliability

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import pro.liliya.core.diagnostics.DiagnosticSeverity
import pro.liliya.core.evaluation.OutcomeEvaluationId
import pro.liliya.core.evaluation.OutcomeEvaluationVersion
import pro.liliya.core.reflection.ReflectionResultId
import pro.liliya.core.reflection.ReflectionVersion
import pro.liliya.core.strategy.StrategyCandidateId
import pro.liliya.core.strategy.StrategyVersion

@JvmInline
value class ReliabilityAssessmentId(val value: String) {
    init {
        require(value.isNotBlank()) { "reliability assessment id must not be blank" }
        require(value.length <= 96) { "reliability assessment id is too long" }
    }
}

@JvmInline
value class ReliabilityResultId(val value: String) {
    init {
        require(value.isNotBlank()) { "reliability result id must not be blank" }
        require(value.length <= 96) { "reliability result id is too long" }
    }
}

@JvmInline
value class ReliabilityVersion(val value: Long) {
    init { require(value > 0L) { "reliability version must be positive" } }
}

@JvmInline
value class ReliabilityPolicyId(val value: String) {
    init {
        require(value.isNotBlank()) { "reliability policy id must not be blank" }
        require(value.toByteArray(StandardCharsets.UTF_8).size <= 128) {
            "reliability policy id is too long"
        }
    }
}

@JvmInline
value class ReliabilityPolicyVersion(val value: Int) {
    init { require(value > 0) { "reliability policy version must be positive" } }
}

enum class ReliabilityInputDomain {
    OUTCOME_EVALUATION,
    REFLECTION,
    STRATEGY_ADAPTATION,
    DIAGNOSTIC
}

enum class ReliabilitySignalKind {
    EVIDENCE_QUALITY,
    UNRESOLVED_CONFLICT,
    STALENESS,
    COVERAGE,
    STRATEGY_PERFORMANCE,
    CAPABILITY_LIMIT,
    DIAGNOSTIC_HEALTH
}

enum class ReliabilitySignalState {
    GOOD,
    CAUTION,
    POOR,
    UNKNOWN
}

enum class CapabilityState {
    SUPPORTED,
    LIMITED,
    UNSUPPORTED,
    UNKNOWN
}

enum class ReliabilityProvenanceDomain {
    REQUEST,
    OUTCOME_EVALUATION,
    REFLECTION,
    STRATEGY_ADAPTATION,
    DIAGNOSTIC,
    CAPABILITY
}

data class ReliabilityProvenanceReference(
    val domain: ReliabilityProvenanceDomain,
    val id: String,
    val version: Long
) {
    init {
        require(id.isNotBlank()) { "reliability provenance id must not be blank" }
        require(id.toByteArray(StandardCharsets.UTF_8).size <= 256) {
            "reliability provenance id exceeds bounded size"
        }
        require(version > 0L) { "reliability provenance version must be positive" }
    }
}

data class OutcomeReliabilityReference(
    val id: OutcomeEvaluationId,
    val version: OutcomeEvaluationVersion
)

data class ReflectionReliabilityReference(
    val id: ReflectionResultId,
    val version: ReflectionVersion
)

data class StrategyReliabilityReference(
    val id: StrategyCandidateId,
    val version: StrategyVersion
)

data class DiagnosticReliabilityEvidence(
    val sequence: Long,
    val timestampMillis: Long,
    val severity: DiagnosticSeverity,
    val code: String
) {
    init {
        require(sequence > 0L) { "diagnostic sequence must be positive" }
        require(timestampMillis >= 0L) { "diagnostic timestamp must not be negative" }
        require(code.isNotBlank()) { "diagnostic code must not be blank" }
        require(code.toByteArray(StandardCharsets.UTF_8).size <= 256) {
            "diagnostic code exceeds bounded size"
        }
    }

    fun provenance(): ReliabilityProvenanceReference =
        ReliabilityProvenanceReference(
            domain = ReliabilityProvenanceDomain.DIAGNOSTIC,
            id = "$sequence:$timestampMillis:$code",
            version = 1
        )
}

data class CapabilityDeclaration(
    val capability: String,
    val state: CapabilityState,
    val rationale: String
) {
    init {
        require(capability.isNotBlank()) { "capability name must not be blank" }
        require(capability.toByteArray(StandardCharsets.UTF_8).size <= 256) {
            "capability name exceeds bounded size"
        }
        require(rationale.isNotBlank()) { "capability rationale must not be blank" }
        require(rationale.toByteArray(StandardCharsets.UTF_8).size <= 2048) {
            "capability rationale exceeds bounded size"
        }
    }

    fun provenance(): ReliabilityProvenanceReference =
        ReliabilityProvenanceReference(
            domain = ReliabilityProvenanceDomain.CAPABILITY,
            id = capability,
            version = 1
        )
}

data class StrategyPerformanceLink(
    val strategy: StrategyReliabilityReference,
    val outcomes: List<OutcomeReliabilityReference>
) {
    init {
        require(outcomes.isNotEmpty()) { "strategy performance link requires outcomes" }
        require(outcomes.size <= MAX_OUTCOMES) { "too many strategy performance outcomes" }
        require(outcomes.distinct().size == outcomes.size) {
            "strategy performance outcomes must be unique"
        }
        require(outcomes == outcomes.sortedWith(
            compareBy<OutcomeReliabilityReference>({ it.id.value }, { it.version.value })
        )) {
            "strategy performance outcomes must use canonical order"
        }
    }

    companion object {
        const val MAX_OUTCOMES = 32

        fun create(
            strategy: StrategyReliabilityReference,
            outcomes: List<OutcomeReliabilityReference>
        ) = StrategyPerformanceLink(
            strategy = strategy,
            outcomes = outcomes.sortedWith(
                compareBy<OutcomeReliabilityReference>({ it.id.value }, { it.version.value })
            )
        )
    }
}

data class ReliabilityFreshnessPolicy(
    val maxSourceAgeSeconds: Long,
    val assessmentTtlSeconds: Long
) {
    init {
        require(maxSourceAgeSeconds in 1..MAX_SOURCE_AGE_SECONDS) {
            "reliability max source age is out of bounds"
        }
        require(assessmentTtlSeconds in 1..MAX_ASSESSMENT_TTL_SECONDS) {
            "reliability assessment ttl is out of bounds"
        }
    }

    companion object {
        const val MAX_SOURCE_AGE_SECONDS = 30L * 24L * 60L * 60L
        const val MAX_ASSESSMENT_TTL_SECONDS = 24L * 60L * 60L
    }
}

data class ReliabilityAssessmentRequest(
    val id: ReliabilityAssessmentId,
    val version: ReliabilityVersion,
    val outcomes: List<OutcomeReliabilityReference>,
    val reflections: List<ReflectionReliabilityReference>,
    val strategies: List<StrategyReliabilityReference>,
    val diagnostics: List<DiagnosticReliabilityEvidence>,
    val performanceLinks: List<StrategyPerformanceLink>,
    val capabilities: List<CapabilityDeclaration>,
    val requiredDomains: List<ReliabilityInputDomain>,
    val freshnessPolicy: ReliabilityFreshnessPolicy,
    val policyId: ReliabilityPolicyId,
    val policyVersion: ReliabilityPolicyVersion,
    val assessedAt: Instant
) {
    init {
        require(
            outcomes.isNotEmpty() ||
                reflections.isNotEmpty() ||
                strategies.isNotEmpty() ||
                diagnostics.isNotEmpty() ||
                capabilities.isNotEmpty()
        ) { "reliability request must contain explicit selected inputs" }
        require(outcomes.size <= MAX_REFERENCES) { "too many outcome reliability references" }
        require(reflections.size <= MAX_REFERENCES) { "too many reflection reliability references" }
        require(strategies.size <= MAX_REFERENCES) { "too many strategy reliability references" }
        require(diagnostics.size <= MAX_REFERENCES) { "too many diagnostic reliability inputs" }
        require(performanceLinks.size <= MAX_PERFORMANCE_LINKS) {
            "too many strategy performance links"
        }
        require(capabilities.size <= MAX_CAPABILITIES) {
            "too many capability declarations"
        }
        require(outcomes.distinct().size == outcomes.size) { "outcome references must be unique" }
        require(reflections.distinct().size == reflections.size) { "reflection references must be unique" }
        require(strategies.distinct().size == strategies.size) { "strategy references must be unique" }
        require(diagnostics.distinct().size == diagnostics.size) { "diagnostic inputs must be unique" }
        require(performanceLinks.distinct().size == performanceLinks.size) {
            "strategy performance links must be unique"
        }
        require(performanceLinks.map { it.strategy }.distinct().size == performanceLinks.size) {
            "strategy performance links must use one link per strategy"
        }
        require(performanceLinks.all { it.strategy in strategies }) {
            "strategy performance links must reference selected strategies"
        }
        require(performanceLinks.all { link -> link.outcomes.all { it in outcomes } }) {
            "strategy performance links must reference selected outcomes"
        }
        require(capabilities.isNotEmpty()) {
            "reliability request requires explicit capability declarations"
        }
        require(capabilities.map { it.capability }.distinct().size == capabilities.size) {
            "capability declarations must be unique"
        }
        require(requiredDomains.isNotEmpty()) { "required reliability domains must not be empty" }
        require(requiredDomains.distinct().size == requiredDomains.size) {
            "required reliability domains must be unique"
        }

        require(outcomes == canonicalOutcomes(outcomes)) { "outcome references must use canonical order" }
        require(reflections == canonicalReflections(reflections)) {
            "reflection references must use canonical order"
        }
        require(strategies == canonicalStrategies(strategies)) {
            "strategy references must use canonical order"
        }
        require(diagnostics == canonicalDiagnostics(diagnostics)) {
            "diagnostic inputs must use canonical order"
        }
        require(performanceLinks == canonicalPerformanceLinks(performanceLinks)) {
            "strategy performance links must use canonical order"
        }
        require(capabilities == capabilities.sortedBy { it.capability }) {
            "capability declarations must use canonical order"
        }
        require(requiredDomains == requiredDomains.sortedBy { it.name }) {
            "required reliability domains must use canonical order"
        }
        require(id == deterministicId(
            version,
            outcomes,
            reflections,
            strategies,
            diagnostics,
            performanceLinks,
            capabilities,
            requiredDomains,
            freshnessPolicy,
            policyId,
            policyVersion,
            assessedAt
        )) { "reliability request id does not match deterministic content identity" }
    }

    companion object {
        const val MAX_REFERENCES = 64
        const val MAX_PERFORMANCE_LINKS = 32
        const val MAX_CAPABILITIES = 32

        fun create(
            version: ReliabilityVersion,
            outcomes: List<OutcomeReliabilityReference> = emptyList(),
            reflections: List<ReflectionReliabilityReference> = emptyList(),
            strategies: List<StrategyReliabilityReference> = emptyList(),
            diagnostics: List<DiagnosticReliabilityEvidence> = emptyList(),
            performanceLinks: List<StrategyPerformanceLink> = emptyList(),
            capabilities: List<CapabilityDeclaration>,
            requiredDomains: List<ReliabilityInputDomain>,
            freshnessPolicy: ReliabilityFreshnessPolicy,
            policyId: ReliabilityPolicyId,
            policyVersion: ReliabilityPolicyVersion,
            assessedAt: Instant
        ): ReliabilityAssessmentRequest {
            val co = canonicalOutcomes(outcomes)
            val cr = canonicalReflections(reflections)
            val cs = canonicalStrategies(strategies)
            val cd = canonicalDiagnostics(diagnostics)
            val cp = canonicalPerformanceLinks(performanceLinks)
            val cc = capabilities.sortedBy { it.capability }
            val rd = requiredDomains.sortedBy { it.name }
            return ReliabilityAssessmentRequest(
                id = deterministicId(
                    version, co, cr, cs, cd, cp, cc, rd,
                    freshnessPolicy, policyId, policyVersion, assessedAt
                ),
                version = version,
                outcomes = co,
                reflections = cr,
                strategies = cs,
                diagnostics = cd,
                performanceLinks = cp,
                capabilities = cc,
                requiredDomains = rd,
                freshnessPolicy = freshnessPolicy,
                policyId = policyId,
                policyVersion = policyVersion,
                assessedAt = assessedAt
            )
        }

        private fun canonicalOutcomes(values: List<OutcomeReliabilityReference>) =
            values.sortedWith(compareBy<OutcomeReliabilityReference>({ it.id.value }, { it.version.value }))

        private fun canonicalReflections(values: List<ReflectionReliabilityReference>) =
            values.sortedWith(compareBy<ReflectionReliabilityReference>({ it.id.value }, { it.version.value }))

        private fun canonicalStrategies(values: List<StrategyReliabilityReference>) =
            values.sortedWith(compareBy<StrategyReliabilityReference>({ it.id.value }, { it.version.value }))

        private fun canonicalDiagnostics(values: List<DiagnosticReliabilityEvidence>) =
            values.sortedWith(
                compareBy<DiagnosticReliabilityEvidence>({ it.sequence }, { it.timestampMillis }, { it.code })
            )

        private fun canonicalPerformanceLinks(values: List<StrategyPerformanceLink>) =
            values.sortedWith(
                compareBy<StrategyPerformanceLink>({ it.strategy.id.value }, { it.strategy.version.value })
            )

        internal fun deterministicId(
            version: ReliabilityVersion,
            outcomes: List<OutcomeReliabilityReference>,
            reflections: List<ReflectionReliabilityReference>,
            strategies: List<StrategyReliabilityReference>,
            diagnostics: List<DiagnosticReliabilityEvidence>,
            performanceLinks: List<StrategyPerformanceLink>,
            capabilities: List<CapabilityDeclaration>,
            requiredDomains: List<ReliabilityInputDomain>,
            freshnessPolicy: ReliabilityFreshnessPolicy,
            policyId: ReliabilityPolicyId,
            policyVersion: ReliabilityPolicyVersion,
            assessedAt: Instant
        ): ReliabilityAssessmentId {
            val d = MessageDigest.getInstance("SHA-256")
            put(d, "reliability-request-v1")
            put(d, version.value.toString())
            outcomes.forEach {
                put(d, it.id.value)
                put(d, it.version.value.toString())
            }
            reflections.forEach {
                put(d, it.id.value)
                put(d, it.version.value.toString())
            }
            strategies.forEach {
                put(d, it.id.value)
                put(d, it.version.value.toString())
            }
            diagnostics.forEach {
                put(d, it.sequence.toString())
                put(d, it.timestampMillis.toString())
                put(d, it.severity.name)
                put(d, it.code)
            }
            performanceLinks.forEach { link ->
                put(d, link.strategy.id.value)
                put(d, link.strategy.version.value.toString())
                link.outcomes.forEach {
                    put(d, it.id.value)
                    put(d, it.version.value.toString())
                }
            }
            capabilities.forEach {
                put(d, it.capability)
                put(d, it.state.name)
                put(d, it.rationale)
            }
            requiredDomains.forEach { put(d, it.name) }
            put(d, freshnessPolicy.maxSourceAgeSeconds.toString())
            put(d, freshnessPolicy.assessmentTtlSeconds.toString())
            put(d, policyId.value)
            put(d, policyVersion.value.toString())
            put(d, assessedAt.epochSecond.toString())
            put(d, assessedAt.nano.toString())
            return ReliabilityAssessmentId(
                "reliability-assessment-" + d.digest().joinToString("") { "%02x".format(it) }
            )
        }

        internal fun put(digest: MessageDigest, value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
    }
}

data class ReliabilitySignal(
    val kind: ReliabilitySignalKind,
    val state: ReliabilitySignalState,
    val rationale: String,
    val provenance: List<ReliabilityProvenanceReference>
) {
    init {
        require(rationale.isNotBlank()) { "reliability signal rationale must not be blank" }
        require(rationale.toByteArray(StandardCharsets.UTF_8).size <= 2048) {
            "reliability signal rationale exceeds bounded size"
        }
        require(provenance.isNotEmpty()) { "reliability signal must retain provenance" }
        require(provenance.size <= MAX_PROVENANCE_REFERENCES) {
            "too many reliability signal provenance references"
        }
        require(provenance.distinct().size == provenance.size) {
            "reliability signal provenance must be unique"
        }
        require(provenance == provenance.sortedWith(
            compareBy<ReliabilityProvenanceReference>({ it.domain.name }, { it.id }, { it.version })
        )) {
            "reliability signal provenance must use canonical order"
        }
    }
}

private const val MAX_PROVENANCE_REFERENCES =
    ReliabilityAssessmentRequest.MAX_REFERENCES * 4 +
        ReliabilityAssessmentRequest.MAX_CAPABILITIES + 1

data class ReliabilityAssessment(
    val id: ReliabilityResultId,
    val requestId: ReliabilityAssessmentId,
    val version: ReliabilityVersion,
    val signals: List<ReliabilitySignal>,
    val policyId: ReliabilityPolicyId,
    val policyVersion: ReliabilityPolicyVersion,
    val assessedAt: Instant,
    val expiresAt: Instant
) {
    init {
        require(signals.isNotEmpty()) { "reliability assessment must contain signals" }
        require(signals.size == ReliabilitySignalKind.entries.size) {
            "reliability assessment must contain exactly one signal per kind"
        }
        require(signals.map { it.kind }.distinct().size == signals.size) {
            "reliability assessment signal kinds must be unique"
        }
        require(signals == signals.sortedBy { it.kind.name }) {
            "reliability assessment signals must use canonical order"
        }
        require(expiresAt > assessedAt) { "reliability assessment expiry must be after assessment time" }
        require(id == deterministicId(
            requestId, version, signals, policyId, policyVersion, assessedAt, expiresAt
        )) { "reliability assessment id does not match deterministic content identity" }
    }

    companion object {
        fun create(
            requestId: ReliabilityAssessmentId,
            version: ReliabilityVersion,
            signals: List<ReliabilitySignal>,
            policyId: ReliabilityPolicyId,
            policyVersion: ReliabilityPolicyVersion,
            assessedAt: Instant,
            expiresAt: Instant
        ): ReliabilityAssessment {
            val canonical = signals.sortedBy { it.kind.name }
            return ReliabilityAssessment(
                id = deterministicId(
                    requestId, version, canonical, policyId, policyVersion, assessedAt, expiresAt
                ),
                requestId = requestId,
                version = version,
                signals = canonical,
                policyId = policyId,
                policyVersion = policyVersion,
                assessedAt = assessedAt,
                expiresAt = expiresAt
            )
        }

        private fun deterministicId(
            requestId: ReliabilityAssessmentId,
            version: ReliabilityVersion,
            signals: List<ReliabilitySignal>,
            policyId: ReliabilityPolicyId,
            policyVersion: ReliabilityPolicyVersion,
            assessedAt: Instant,
            expiresAt: Instant
        ): ReliabilityResultId {
            val d = MessageDigest.getInstance("SHA-256")
            ReliabilityAssessmentRequest.put(d, "reliability-assessment-result-v1")
            ReliabilityAssessmentRequest.put(d, requestId.value)
            ReliabilityAssessmentRequest.put(d, version.value.toString())
            signals.forEach { signal ->
                ReliabilityAssessmentRequest.put(d, signal.kind.name)
                ReliabilityAssessmentRequest.put(d, signal.state.name)
                ReliabilityAssessmentRequest.put(d, signal.rationale)
                signal.provenance.forEach {
                    ReliabilityAssessmentRequest.put(d, it.domain.name)
                    ReliabilityAssessmentRequest.put(d, it.id)
                    ReliabilityAssessmentRequest.put(d, it.version.toString())
                }
            }
            ReliabilityAssessmentRequest.put(d, policyId.value)
            ReliabilityAssessmentRequest.put(d, policyVersion.value.toString())
            ReliabilityAssessmentRequest.put(d, assessedAt.epochSecond.toString())
            ReliabilityAssessmentRequest.put(d, assessedAt.nano.toString())
            ReliabilityAssessmentRequest.put(d, expiresAt.epochSecond.toString())
            ReliabilityAssessmentRequest.put(d, expiresAt.nano.toString())
            return ReliabilityResultId(
                "reliability-result-" + d.digest().joinToString("") { "%02x".format(it) }
            )
        }
    }
}
