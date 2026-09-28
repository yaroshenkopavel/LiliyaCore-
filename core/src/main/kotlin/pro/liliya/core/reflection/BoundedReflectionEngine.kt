package pro.liliya.core.reflection

import java.nio.charset.StandardCharsets
import java.time.Instant
import pro.liliya.core.episodic.RawEvidenceReference
import pro.liliya.core.evaluation.OutcomeEvaluationLookupResult
import pro.liliya.core.evaluation.OutcomeEvaluationRecord
import pro.liliya.core.evaluation.OutcomeEvaluationRepository

@JvmInline
value class ReflectionEvidenceText(val value: String) {
    init {
        require(value.isNotBlank()) { "reflection evidence material must not be blank" }
        require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_UTF8_BYTES) {
            "reflection evidence material exceeds bounded size"
        }
    }
    companion object { const val MAX_UTF8_BYTES = 8 * 1024 }
}

data class ReflectionEvidenceMaterial(
    val reference: RawEvidenceReference,
    val text: ReflectionEvidenceText
)

sealed interface RawEvidenceResolutionResult {
    data class Resolved(val material: ReflectionEvidenceMaterial) : RawEvidenceResolutionResult
    data object Missing : RawEvidenceResolutionResult
    data class Failed(val reason: String) : RawEvidenceResolutionResult
}

fun interface RawEvidenceReferenceResolver {
    fun resolve(reference: RawEvidenceReference): RawEvidenceResolutionResult
}

data class ReflectionAnalysisInput(
    val request: ReflectionRequest,
    val evidence: List<ReflectionEvidenceMaterial>,
    val outcomes: List<OutcomeEvaluationRecord>
)

fun interface ReflectionAnalyzer {
    fun analyze(input: ReflectionAnalysisInput): List<ReflectionFinding>
}

sealed interface ReflectionExecutionResult {
    data class Completed(val result: BoundedReflectionResult) : ReflectionExecutionResult
    data class MissingEvidence(val reference: RawEvidenceReference) : ReflectionExecutionResult
    data class MissingOutcome(val reference: ReflectionOutcomeReference) : ReflectionExecutionResult
    data class InvalidOutcome(
        val reference: ReflectionOutcomeReference,
        val reason: String
    ) : ReflectionExecutionResult
    data class EvidenceResolutionFailed(
        val reference: RawEvidenceReference,
        val reason: String
    ) : ReflectionExecutionResult
    data class Rejected(val reason: String) : ReflectionExecutionResult
    data class Failed(val reason: String, val throwable: Throwable? = null) : ReflectionExecutionResult
}

class BoundedReflectionEngine(
    private val outcomeRepository: OutcomeEvaluationRepository,
    private val evidenceResolver: RawEvidenceReferenceResolver,
    private val analyzer: ReflectionAnalyzer
) {
    fun reflect(
        request: ReflectionRequest,
        resultVersion: ReflectionVersion,
        completedAt: Instant
    ): ReflectionExecutionResult {
        if (completedAt < request.requestedAt) {
            return ReflectionExecutionResult.Rejected("reflection completion cannot predate request")
        }

        val evidence = ArrayList<ReflectionEvidenceMaterial>(request.evidence.size)
        request.evidence.forEach { reference ->
            when (val resolved = evidenceResolver.resolve(reference)) {
                is RawEvidenceResolutionResult.Resolved -> {
                    if (resolved.material.reference != reference) {
                        return ReflectionExecutionResult.EvidenceResolutionFailed(
                            reference,
                            "resolved evidence identity mismatch"
                        )
                    }
                    evidence += resolved.material
                }
                RawEvidenceResolutionResult.Missing ->
                    return ReflectionExecutionResult.MissingEvidence(reference)
                is RawEvidenceResolutionResult.Failed ->
                    return ReflectionExecutionResult.EvidenceResolutionFailed(reference, resolved.reason)
            }
        }

        val outcomes = ArrayList<OutcomeEvaluationRecord>(request.outcomes.size)
        request.outcomes.forEach { reference ->
            when (val lookup = outcomeRepository.lookup(reference.id)) {
                OutcomeEvaluationLookupResult.Missing ->
                    return ReflectionExecutionResult.MissingOutcome(reference)
                is OutcomeEvaluationLookupResult.Found -> {
                    if (lookup.snapshot.record.version != reference.version) {
                        return ReflectionExecutionResult.InvalidOutcome(
                            reference,
                            "outcome evaluation version mismatch"
                        )
                    }
                    outcomes += lookup.snapshot.record
                }
                OutcomeEvaluationLookupResult.Corrupt ->
                    return ReflectionExecutionResult.InvalidOutcome(reference, "corrupt outcome evaluation")
                is OutcomeEvaluationLookupResult.Incompatible ->
                    return ReflectionExecutionResult.InvalidOutcome(reference, lookup.reason)
                is OutcomeEvaluationLookupResult.EncryptionUnavailable ->
                    return ReflectionExecutionResult.InvalidOutcome(
                        reference,
                        "outcome evaluation encryption unavailable"
                    )
                is OutcomeEvaluationLookupResult.Failed ->
                    return ReflectionExecutionResult.InvalidOutcome(reference, lookup.reason)
            }
        }

        val findings = try {
            analyzer.analyze(ReflectionAnalysisInput(request, evidence, outcomes))
        } catch (t: Throwable) {
            return ReflectionExecutionResult.Failed("reflection analyzer failed", t)
        }

        if (findings.isEmpty()) {
            return ReflectionExecutionResult.Rejected("reflection analyzer returned no findings")
        }
        if (findings.size > request.budget.maxFindings) {
            return ReflectionExecutionResult.Rejected("reflection finding budget exceeded")
        }

        var totalBytes = 0
        val allowedEvidence = request.evidence.toSet()
        val allowedOutcomes = request.outcomes.toSet()
        findings.forEach { finding ->
            if (!allowedEvidence.containsAll(finding.evidence)) {
                return ReflectionExecutionResult.Rejected(
                    "reflection finding references evidence outside request"
                )
            }
            if (!allowedOutcomes.containsAll(finding.outcomes)) {
                return ReflectionExecutionResult.Rejected(
                    "reflection finding references outcome outside request"
                )
            }
            totalBytes += finding.text.value.toByteArray(StandardCharsets.UTF_8).size
            if (totalBytes > request.budget.maxTotalOutputUtf8Bytes) {
                return ReflectionExecutionResult.Rejected("reflection output byte budget exceeded")
            }
        }

        return ReflectionExecutionResult.Completed(
            BoundedReflectionResult.create(
                version = resultVersion,
                requestId = request.id,
                findings = findings,
                policyId = request.policyId,
                policyVersion = request.policyVersion,
                completedAt = completedAt
            )
        )
    }
}
