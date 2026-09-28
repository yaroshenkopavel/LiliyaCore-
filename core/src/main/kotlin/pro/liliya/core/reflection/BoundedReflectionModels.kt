package pro.liliya.core.reflection

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import pro.liliya.core.episodic.RawEvidenceReference
import pro.liliya.core.evaluation.OutcomeEvaluationId
import pro.liliya.core.evaluation.OutcomeEvaluationVersion

@JvmInline
value class ReflectionRequestId(val value: String) {
    init {
        require(value.isNotBlank()) { "reflection request id must not be blank" }
        require(value.length <= 96) { "reflection request id is too long" }
    }
}
@JvmInline
value class ReflectionResultId(val value: String) {
    init {
        require(value.isNotBlank()) { "reflection result id must not be blank" }
        require(value.length <= 96) { "reflection result id is too long" }
    }
}
@JvmInline
value class ReflectionVersion(val value: Long) {
    init { require(value > 0L) { "reflection version must be positive" } }
}
@JvmInline
value class ReflectionPolicyId(val value: String) {
    init {
        require(value.isNotBlank()) { "reflection policy id must not be blank" }
        require(value.toByteArray(StandardCharsets.UTF_8).size <= 128) { "reflection policy id is too long" }
    }
}
@JvmInline
value class ReflectionPolicyVersion(val value: Int) {
    init { require(value > 0) { "reflection policy version must be positive" } }
}
data class ReflectionOutcomeReference(
    val id: OutcomeEvaluationId,
    val version: OutcomeEvaluationVersion
)
data class ReflectionWorkBudget(
    val maxFindings: Int,
    val maxTotalOutputUtf8Bytes: Int
) {
    init {
        require(maxFindings in 1..MAX_FINDINGS) { "reflection finding budget is out of bounds" }
        require(maxTotalOutputUtf8Bytes in 1..MAX_TOTAL_OUTPUT_UTF8_BYTES) { "reflection output budget is out of bounds" }
    }
    companion object {
        const val MAX_FINDINGS = 64
        const val MAX_TOTAL_OUTPUT_UTF8_BYTES = 64 * 1024
    }
}
enum class ReflectionFindingKind {
    EXPLANATION_CANDIDATE,
    PLANNING_MISTAKE,
    MISSING_CONTEXT,
    UNCERTAINTY_OR_CONFLICT,
    REQUEST_ADDITIONAL_EVIDENCE,
    STRATEGY_CANDIDATE_INPUT
}
@JvmInline
value class ReflectionFindingText(val value: String) {
    init {
        require(value.isNotBlank()) { "reflection finding text must not be blank" }
        require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_UTF8_BYTES) {
            "reflection finding text exceeds bounded size"
        }
    }
    companion object { const val MAX_UTF8_BYTES = 8 * 1024 }
}
data class ReflectionRequest(
    val id: ReflectionRequestId,
    val version: ReflectionVersion,
    val evidence: List<RawEvidenceReference>,
    val outcomes: List<ReflectionOutcomeReference>,
    val budget: ReflectionWorkBudget,
    val policyId: ReflectionPolicyId,
    val policyVersion: ReflectionPolicyVersion,
    val requestedAt: Instant
) {
    init {
        require(evidence.isNotEmpty() || outcomes.isNotEmpty()) { "reflection request must contain at least one explicit input" }
        require(evidence.size <= MAX_EVIDENCE_REFERENCES) { "too many reflection evidence references" }
        require(outcomes.size <= MAX_OUTCOME_REFERENCES) { "too many reflection outcome references" }
        require(evidence.all {
            it.namespace.value.toByteArray(StandardCharsets.UTF_8).size <= MAX_REFERENCE_UTF8_BYTES &&
                it.id.value.toByteArray(StandardCharsets.UTF_8).size <= MAX_REFERENCE_UTF8_BYTES
        }) { "reflection evidence reference exceeds bounded size" }
        require(evidence.distinct().size == evidence.size) { "reflection evidence references must be unique" }
        require(outcomes.distinct().size == outcomes.size) { "reflection outcome references must be unique" }
        require(evidence == canonicalEvidence(evidence)) { "reflection evidence references must use canonical order" }
        require(outcomes == canonicalOutcomes(outcomes)) { "reflection outcome references must use canonical order" }
        require(id == deterministicId(version, evidence, outcomes, budget, policyId, policyVersion, requestedAt)) {
            "reflection request id does not match deterministic content identity"
        }
    }
    companion object {
        const val MAX_EVIDENCE_REFERENCES = 64
        const val MAX_OUTCOME_REFERENCES = 64
        const val MAX_REFERENCE_UTF8_BYTES = 256
        fun create(
            version: ReflectionVersion,
            evidence: List<RawEvidenceReference> = emptyList(),
            outcomes: List<ReflectionOutcomeReference> = emptyList(),
            budget: ReflectionWorkBudget,
            policyId: ReflectionPolicyId,
            policyVersion: ReflectionPolicyVersion,
            requestedAt: Instant
        ): ReflectionRequest {
            val ce = canonicalEvidence(evidence)
            val co = canonicalOutcomes(outcomes)
            return ReflectionRequest(
                deterministicId(version, ce, co, budget, policyId, policyVersion, requestedAt),
                version, ce, co, budget, policyId, policyVersion, requestedAt
            )
        }
        private fun canonicalEvidence(values: List<RawEvidenceReference>) =
            values.sortedWith(compareBy<RawEvidenceReference>({ it.namespace.value }, { it.id.value }))
        private fun canonicalOutcomes(values: List<ReflectionOutcomeReference>) =
            values.sortedWith(compareBy<ReflectionOutcomeReference>({ it.id.value }, { it.version.value }))
        internal fun deterministicId(
            version: ReflectionVersion,
            evidence: List<RawEvidenceReference>,
            outcomes: List<ReflectionOutcomeReference>,
            budget: ReflectionWorkBudget,
            policyId: ReflectionPolicyId,
            policyVersion: ReflectionPolicyVersion,
            requestedAt: Instant
        ): ReflectionRequestId {
            val d = MessageDigest.getInstance("SHA-256")
            put(d, "reflection-request-v1")
            put(d, version.value.toString())
            evidence.forEach { put(d, it.namespace.value); put(d, it.id.value) }
            outcomes.forEach { put(d, it.id.value); put(d, it.version.value.toString()) }
            put(d, budget.maxFindings.toString())
            put(d, budget.maxTotalOutputUtf8Bytes.toString())
            put(d, policyId.value)
            put(d, policyVersion.value.toString())
            put(d, requestedAt.epochSecond.toString())
            put(d, requestedAt.nano.toString())
            return ReflectionRequestId("reflection-request-" + d.digest().joinToString("") { "%02x".format(it) })
        }
        internal fun put(digest: MessageDigest, value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
    }
}
data class ReflectionFinding(
    val kind: ReflectionFindingKind,
    val text: ReflectionFindingText,
    val evidence: List<RawEvidenceReference> = emptyList(),
    val outcomes: List<ReflectionOutcomeReference> = emptyList()
) {
    init {
        require(evidence.isNotEmpty() || outcomes.isNotEmpty()) { "reflection finding must retain explicit provenance" }
        require(evidence.distinct().size == evidence.size) { "reflection finding evidence references must be unique" }
        require(outcomes.distinct().size == outcomes.size) { "reflection finding outcome references must be unique" }
    }
}
data class BoundedReflectionResult(
    val id: ReflectionResultId,
    val version: ReflectionVersion,
    val requestId: ReflectionRequestId,
    val findings: List<ReflectionFinding>,
    val policyId: ReflectionPolicyId,
    val policyVersion: ReflectionPolicyVersion,
    val completedAt: Instant
) {
    init {
        require(findings.isNotEmpty()) { "reflection result must contain at least one finding" }
        require(findings.size <= ReflectionWorkBudget.MAX_FINDINGS) { "reflection result contains too many findings" }
        require(id == deterministicId(version, requestId, findings, policyId, policyVersion, completedAt)) {
            "reflection result id does not match deterministic content identity"
        }
    }
    companion object {
        fun create(
            version: ReflectionVersion,
            requestId: ReflectionRequestId,
            findings: List<ReflectionFinding>,
            policyId: ReflectionPolicyId,
            policyVersion: ReflectionPolicyVersion,
            completedAt: Instant
        ) = BoundedReflectionResult(
            deterministicId(version, requestId, findings, policyId, policyVersion, completedAt),
            version, requestId, findings.toList(), policyId, policyVersion, completedAt
        )
        internal fun deterministicId(
            version: ReflectionVersion,
            requestId: ReflectionRequestId,
            findings: List<ReflectionFinding>,
            policyId: ReflectionPolicyId,
            policyVersion: ReflectionPolicyVersion,
            completedAt: Instant
        ): ReflectionResultId {
            val d = MessageDigest.getInstance("SHA-256")
            ReflectionRequest.put(d, "bounded-reflection-result-v1")
            ReflectionRequest.put(d, version.value.toString())
            ReflectionRequest.put(d, requestId.value)
            findings.forEach { f ->
                ReflectionRequest.put(d, f.kind.name)
                ReflectionRequest.put(d, f.text.value)
                f.evidence.sortedWith(compareBy<RawEvidenceReference>({ it.namespace.value }, { it.id.value })).forEach {
                    ReflectionRequest.put(d, it.namespace.value); ReflectionRequest.put(d, it.id.value)
                }
                f.outcomes.sortedWith(compareBy<ReflectionOutcomeReference>({ it.id.value }, { it.version.value })).forEach {
                    ReflectionRequest.put(d, it.id.value); ReflectionRequest.put(d, it.version.value.toString())
                }
            }
            ReflectionRequest.put(d, policyId.value)
            ReflectionRequest.put(d, policyVersion.value.toString())
            ReflectionRequest.put(d, completedAt.epochSecond.toString())
            ReflectionRequest.put(d, completedAt.nano.toString())
            return ReflectionResultId("reflection-result-" + d.digest().joinToString("") { "%02x".format(it) })
        }
    }
}


data class BoundedReflectionRecord(
    val request: ReflectionRequest,
    val result: BoundedReflectionResult
) {
    init {
        require(result.requestId == request.id) { "reflection result must belong to request" }
        require(result.policyId == request.policyId) { "reflection result policy id mismatch" }
        require(result.policyVersion == request.policyVersion) { "reflection result policy version mismatch" }
        require(result.completedAt >= request.requestedAt) { "reflection result cannot predate request" }
        require(result.findings.size <= request.budget.maxFindings) {
            "reflection result exceeds request finding budget"
        }
        val allowedEvidence = request.evidence.toSet()
        val allowedOutcomes = request.outcomes.toSet()
        var outputBytes = 0
        result.findings.forEach { finding ->
            require(finding.evidence.size <= ReflectionRequest.MAX_EVIDENCE_REFERENCES) {
                "reflection finding has too many evidence references"
            }
            require(finding.outcomes.size <= ReflectionRequest.MAX_OUTCOME_REFERENCES) {
                "reflection finding has too many outcome references"
            }
            require(allowedEvidence.containsAll(finding.evidence)) {
                "reflection finding references evidence outside request"
            }
            require(allowedOutcomes.containsAll(finding.outcomes)) {
                "reflection finding references outcome outside request"
            }
            outputBytes += finding.text.value.toByteArray(StandardCharsets.UTF_8).size
            require(outputBytes <= request.budget.maxTotalOutputUtf8Bytes) {
                "reflection result exceeds request output byte budget"
            }
        }
    }
}

data class BoundedReflectionSnapshot(
    val record: BoundedReflectionRecord,
    val generation: Long
) {
    init { require(generation > 0L) { "bounded reflection generation must be positive" } }
}
