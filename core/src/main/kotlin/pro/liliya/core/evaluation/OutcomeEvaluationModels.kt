package pro.liliya.core.evaluation

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import pro.liliya.core.decision.DecisionGeneration
import pro.liliya.core.decision.DecisionId
import pro.liliya.core.episodic.RawEvidenceReference
import pro.liliya.core.execution.ExecutionActionId
import pro.liliya.core.planning.PlanningGeneration
import pro.liliya.core.planning.PlanningProposalId

@JvmInline
value class OutcomeEvaluationId(val value: String) {
    init {
        require(value.isNotBlank()) { "outcome evaluation id must not be blank" }
        require(value.length <= 96) { "outcome evaluation id is too long" }
    }
    override fun toString(): String = value
}

@JvmInline
value class OutcomeEvaluationVersion(val value: Long) {
    init { require(value > 0L) { "outcome evaluation version must be positive" } }
}

@JvmInline
value class EvaluatorPolicyId(val value: String) {
    init {
        require(value.isNotBlank()) { "evaluator policy id must not be blank" }
        require(value.toByteArray(StandardCharsets.UTF_8).size <= 128) { "evaluator policy id is too long" }
    }
}
@JvmInline
value class EvaluatorPolicyVersion(val value: Int) {
    init { require(value > 0) { "evaluator policy version must be positive" } }
}

@JvmInline
value class OutcomeValidationReference(val value: String) {
    init {
        require(value.isNotBlank()) { "outcome validation reference must not be blank" }
        require(value.toByteArray(StandardCharsets.UTF_8).size <= 256) { "outcome validation reference is too long" }
    }
}

@JvmInline
value class OutcomeText(val value: String) {
    init {
        require(value.isNotBlank()) { "outcome text must not be blank" }
        require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_UTF8_BYTES) {
            "outcome text exceeds bounded size"
        }
    }

    companion object {
        const val MAX_UTF8_BYTES = 16 * 1024
    }
}

enum class OutcomeEvaluationStatus {
    SUCCESS,
    FAILURE,
    PARTIAL,
    UNKNOWN
}

data class OutcomePlanReference(
    val id: PlanningProposalId,
    val generation: PlanningGeneration
)

data class OutcomeDecisionReference(
    val id: DecisionId,
    val generation: DecisionGeneration
)
data class OutcomeEvaluationRecord(
    val id: OutcomeEvaluationId,
    val version: OutcomeEvaluationVersion,
    val plan: OutcomePlanReference,
    val decision: OutcomeDecisionReference,
    val authorizedActionId: ExecutionActionId?,
    val expected: OutcomeText,
    val observed: OutcomeText,
    val evidence: List<RawEvidenceReference>,
    val validationReferences: List<OutcomeValidationReference>,
    val status: OutcomeEvaluationStatus,
    val evaluatorPolicyId: EvaluatorPolicyId,
    val evaluatorPolicyVersion: EvaluatorPolicyVersion,
    val evaluatedAt: Instant
) {
    init {
        requireReferenceBound(plan.id.value, "plan id")
        requireReferenceBound(decision.id.value, "decision id")
        authorizedActionId?.let { requireReferenceBound(it.value, "authorized action id") }
        require(evidence.isNotEmpty()) { "outcome evaluation must reference canonical raw evidence" }
        require(evidence.size <= MAX_EVIDENCE_REFERENCES) { "too many outcome evidence references" }
        require(evidence.all {
            it.namespace.value.toByteArray(StandardCharsets.UTF_8).size <= MAX_REFERENCE_UTF8_BYTES &&
                it.id.value.toByteArray(StandardCharsets.UTF_8).size <= MAX_REFERENCE_UTF8_BYTES
        }) { "outcome evidence reference exceeds bounded size" }
        require(evidence.distinct().size == evidence.size) { "outcome evidence references must be unique" }
        require(evidence == canonicalEvidence(evidence)) { "outcome evidence references must use canonical order" }
        require(validationReferences.size <= MAX_VALIDATION_REFERENCES) {
            "too many outcome validation references"
        }
        require(validationReferences.distinct().size == validationReferences.size) {
            "outcome validation references must be unique"
        }
        require(validationReferences == validationReferences.sortedBy { it.value }) {
            "outcome validation references must use canonical order"
        }
        require(
            id == deterministicId(
                version, plan, decision, authorizedActionId, expected, observed,
                evidence, validationReferences, status, evaluatorPolicyId,
                evaluatorPolicyVersion, evaluatedAt
            )
        ) { "outcome evaluation id does not match deterministic content identity" }
    }

    companion object {
        const val MAX_EVIDENCE_REFERENCES = 128
        const val MAX_VALIDATION_REFERENCES = 128
        const val MAX_REFERENCE_UTF8_BYTES = 256

        private fun requireReferenceBound(value: String, label: String) {
            require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_REFERENCE_UTF8_BYTES) {
                "$label exceeds bounded size"
            }
        }

        fun create(
            version: OutcomeEvaluationVersion,
            plan: OutcomePlanReference,
            decision: OutcomeDecisionReference,
            authorizedActionId: ExecutionActionId? = null,
            expected: OutcomeText,
            observed: OutcomeText,
            evidence: List<RawEvidenceReference>,
            validationReferences: List<OutcomeValidationReference> = emptyList(),
            status: OutcomeEvaluationStatus,
            evaluatorPolicyId: EvaluatorPolicyId,
            evaluatorPolicyVersion: EvaluatorPolicyVersion,
            evaluatedAt: Instant
        ): OutcomeEvaluationRecord {
            val canonicalEvidence = evidence.sortedWith(
                compareBy<RawEvidenceReference>({ it.namespace.value }, { it.id.value })
            )
            val canonicalValidation = validationReferences.sortedBy { it.value }
            return OutcomeEvaluationRecord(
                id = deterministicId(
                    version, plan, decision, authorizedActionId, expected, observed,
                    canonicalEvidence, canonicalValidation, status, evaluatorPolicyId,
                    evaluatorPolicyVersion, evaluatedAt
                ),
                version = version,
                plan = plan,
                decision = decision,
                authorizedActionId = authorizedActionId,
                expected = expected,
                observed = observed,
                evidence = canonicalEvidence,
                validationReferences = canonicalValidation,
                status = status,
                evaluatorPolicyId = evaluatorPolicyId,
                evaluatorPolicyVersion = evaluatorPolicyVersion,
                evaluatedAt = evaluatedAt
            )
        }

        internal fun deterministicId(
            version: OutcomeEvaluationVersion,
            plan: OutcomePlanReference,
            decision: OutcomeDecisionReference,
            authorizedActionId: ExecutionActionId?,
            expected: OutcomeText,
            observed: OutcomeText,
            evidence: List<RawEvidenceReference>,
            validationReferences: List<OutcomeValidationReference>,
            status: OutcomeEvaluationStatus,
            evaluatorPolicyId: EvaluatorPolicyId,
            evaluatorPolicyVersion: EvaluatorPolicyVersion,
            evaluatedAt: Instant
        ): OutcomeEvaluationId {
            val digest = MessageDigest.getInstance("SHA-256")
            put(digest, "outcome-evaluation-v1")
            put(digest, version.value.toString())
            put(digest, plan.id.value)
            put(digest, plan.generation.value.toString())
            put(digest, decision.id.value)
            put(digest, decision.generation.value.toString())
            put(digest, authorizedActionId?.value ?: "")
            put(digest, expected.value)
            put(digest, observed.value)
            evidence.forEach {
                put(digest, it.namespace.value)
                put(digest, it.id.value)
            }
            validationReferences.forEach { put(digest, it.value) }
            put(digest, status.name)
            put(digest, evaluatorPolicyId.value)
            put(digest, evaluatorPolicyVersion.value.toString())
            put(digest, evaluatedAt.epochSecond.toString())
            put(digest, evaluatedAt.nano.toString())
            return OutcomeEvaluationId(
                "outcome-evaluation-" + digest.digest().joinToString("") { "%02x".format(it) }
            )
        }
        internal fun canonicalEvidence(
            evidence: List<RawEvidenceReference>
        ): List<RawEvidenceReference> =
            evidence.distinct().sortedWith(
                compareBy<RawEvidenceReference>({ it.namespace.value }, { it.id.value })
            )

        private fun put(digest: MessageDigest, value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
    }
}

data class OutcomeEvaluationSnapshot(
    val record: OutcomeEvaluationRecord,
    val generation: Long
) {
    init { require(generation > 0L) { "outcome evaluation generation must be positive" } }
}
