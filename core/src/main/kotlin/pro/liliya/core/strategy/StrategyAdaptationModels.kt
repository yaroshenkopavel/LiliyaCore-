package pro.liliya.core.strategy

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import pro.liliya.core.reflection.ReflectionFindingKind
import pro.liliya.core.reflection.ReflectionResultId
import pro.liliya.core.reflection.ReflectionVersion

@JvmInline
value class StrategyCandidateId(val value: String) {
    init { require(value.isNotBlank() && value.length <= 96) }
}
@JvmInline
value class StrategyValidationId(val value: String) {
    init { require(value.isNotBlank() && value.length <= 96) }
}
@JvmInline
value class StrategyAdoptionId(val value: String) {
    init { require(value.isNotBlank() && value.length <= 96) }
}
@JvmInline
value class StrategyApplicationIntentId(val value: String) {
    init { require(value.isNotBlank() && value.length <= 96) }
}
@JvmInline
value class StrategyVersion(val value: Long) {
    init { require(value > 0L) }
}
@JvmInline
value class StrategyText(val value: String) {
    init {
        require(value.isNotBlank())
        require(value.toByteArray(StandardCharsets.UTF_8).size <= 16 * 1024)
    }
}
@JvmInline
value class StrategyScope(val value: String) {
    init {
        require(value.isNotBlank())
        require(value.toByteArray(StandardCharsets.UTF_8).size <= 256)
    }
}
@JvmInline
value class StrategyPolicyId(val value: String) {
    init {
        require(value.isNotBlank())
        require(value.toByteArray(StandardCharsets.UTF_8).size <= 128)
    }
}
@JvmInline
value class StrategyPolicyVersion(val value: Int) {
    init { require(value > 0) }
}

enum class StrategyTarget {
    RETRIEVAL,
    PLANNING,
    CONTEXT_ASSEMBLY,
    RESPONSE_SELECTION
}

data class StrategyReflectionSource(
    val resultId: ReflectionResultId,
    val resultVersion: ReflectionVersion,
    val findingIndex: Int,
    val findingKind: ReflectionFindingKind
) {
    init {
        require(findingIndex >= 0)
        require(findingKind == ReflectionFindingKind.STRATEGY_CANDIDATE_INPUT)
    }
}

data class StrategyReference(
    val id: StrategyCandidateId,
    val version: StrategyVersion
)

data class StrategyCompatibilityConstraint(
    val key: String,
    val expected: String
) {
    init {
        require(key.isNotBlank() && expected.isNotBlank())
        require(key.toByteArray(StandardCharsets.UTF_8).size <= 256)
        require(expected.toByteArray(StandardCharsets.UTF_8).size <= 256)
    }
}

data class StrategyCandidate(
    val id: StrategyCandidateId,
    val version: StrategyVersion,
    val source: StrategyReflectionSource,
    val target: StrategyTarget,
    val scope: StrategyScope,
    val proposal: StrategyText,
    val compatibility: List<StrategyCompatibilityConstraint>,
    val rollbackTo: StrategyReference?,
    val createdAt: Instant,
    val expiresAt: Instant
) {
    init {
        require(expiresAt > createdAt)
        require(compatibility.isNotEmpty())
        require(compatibility.size <= MAX_COMPATIBILITY_CONSTRAINTS)
        require(compatibility.distinct().size == compatibility.size)
        require(compatibility == canonicalCompatibility(compatibility))
        require(rollbackTo?.let { it.id != id || it.version != version } ?: true)
        require(id == deterministicId(
            version, source, target, scope, proposal, compatibility, rollbackTo, createdAt, expiresAt
        ))
    }

    fun isExpired(at: Instant): Boolean = !at.isBefore(expiresAt)

    companion object {
        const val MAX_COMPATIBILITY_CONSTRAINTS = 32

        fun create(
            version: StrategyVersion,
            source: StrategyReflectionSource,
            target: StrategyTarget,
            scope: StrategyScope,
            proposal: StrategyText,
            compatibility: List<StrategyCompatibilityConstraint>,
            rollbackTo: StrategyReference?,
            createdAt: Instant,
            expiresAt: Instant
        ): StrategyCandidate {
            val canonical = canonicalCompatibility(compatibility)
            return StrategyCandidate(
                deterministicId(
                    version, source, target, scope, proposal, canonical, rollbackTo, createdAt, expiresAt
                ),
                version, source, target, scope, proposal, canonical, rollbackTo, createdAt, expiresAt
            )
        }

        private fun canonicalCompatibility(values: List<StrategyCompatibilityConstraint>) =
            values.sortedWith(compareBy<StrategyCompatibilityConstraint>({ it.key }, { it.expected }))

        internal fun deterministicId(
            version: StrategyVersion,
            source: StrategyReflectionSource,
            target: StrategyTarget,
            scope: StrategyScope,
            proposal: StrategyText,
            compatibility: List<StrategyCompatibilityConstraint>,
            rollbackTo: StrategyReference?,
            createdAt: Instant,
            expiresAt: Instant
        ): StrategyCandidateId {
            val d = MessageDigest.getInstance("SHA-256")
            put(d, "strategy-candidate-v1")
            put(d, version.value.toString())
            put(d, source.resultId.value)
            put(d, source.resultVersion.value.toString())
            put(d, source.findingIndex.toString())
            put(d, source.findingKind.name)
            put(d, target.name)
            put(d, scope.value)
            put(d, proposal.value)
            compatibility.forEach { put(d, it.key); put(d, it.expected) }
            put(d, rollbackTo?.id?.value ?: "")
            put(d, rollbackTo?.version?.value?.toString() ?: "")
            put(d, createdAt.epochSecond.toString())
            put(d, createdAt.nano.toString())
            put(d, expiresAt.epochSecond.toString())
            put(d, expiresAt.nano.toString())
            return StrategyCandidateId("strategy-candidate-" + d.digest().toHex())
        }

        internal fun put(digest: MessageDigest, value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }

        internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}

enum class StrategyConstraintDisposition { SATISFIED, UNSATISFIED, UNKNOWN }

data class StrategyConstraintResult(
    val constraint: StrategyCompatibilityConstraint,
    val disposition: StrategyConstraintDisposition,
    val reason: String
) {
    init {
        require(reason.isNotBlank())
        require(reason.toByteArray(StandardCharsets.UTF_8).size <= 1024)
    }
}

enum class StrategyValidationDisposition { VALID, INVALID, UNKNOWN, EXPIRED }

data class StrategyValidationRecord(
    val id: StrategyValidationId,
    val candidate: StrategyReference,
    val disposition: StrategyValidationDisposition,
    val constraintResults: List<StrategyConstraintResult>,
    val policyId: StrategyPolicyId,
    val policyVersion: StrategyPolicyVersion,
    val validatedAt: Instant
) {
    init {
        require(constraintResults.isNotEmpty())
        require(constraintResults.size <= StrategyCandidate.MAX_COMPATIBILITY_CONSTRAINTS)
        require(constraintResults.map { it.constraint }.distinct().size == constraintResults.size)
        require(
            constraintResults.map { it.constraint } ==
                constraintResults.map { it.constraint }.sortedWith(
                    compareBy<StrategyCompatibilityConstraint>({ it.key }, { it.expected })
                )
        ) { "strategy validation constraint results must use canonical order" }
        when (disposition) {
            StrategyValidationDisposition.VALID ->
                require(constraintResults.all {
                    it.disposition == StrategyConstraintDisposition.SATISFIED
                }) { "VALID strategy validation requires all constraints satisfied" }
            StrategyValidationDisposition.INVALID ->
                require(constraintResults.any {
                    it.disposition == StrategyConstraintDisposition.UNSATISFIED
                }) { "INVALID strategy validation requires an unsatisfied constraint" }
            StrategyValidationDisposition.UNKNOWN ->
                require(
                    constraintResults.none {
                        it.disposition == StrategyConstraintDisposition.UNSATISFIED
                    } && constraintResults.any {
                        it.disposition == StrategyConstraintDisposition.UNKNOWN
                    }
                ) { "UNKNOWN strategy validation requires unresolved constraints only" }
            StrategyValidationDisposition.EXPIRED ->
                require(constraintResults.all {
                    it.disposition == StrategyConstraintDisposition.UNKNOWN
                }) { "EXPIRED strategy validation must not claim compatibility evidence" }
        }
        require(id == deterministicId(
            candidate, disposition, constraintResults, policyId, policyVersion, validatedAt
        ))
    }

    companion object {
        fun create(
            candidate: StrategyReference,
            disposition: StrategyValidationDisposition,
            constraintResults: List<StrategyConstraintResult>,
            policyId: StrategyPolicyId,
            policyVersion: StrategyPolicyVersion,
            validatedAt: Instant
        ) = StrategyValidationRecord(
            deterministicId(candidate, disposition, constraintResults, policyId, policyVersion, validatedAt),
            candidate, disposition, constraintResults.toList(), policyId, policyVersion, validatedAt
        )

        internal fun deterministicId(
            candidate: StrategyReference,
            disposition: StrategyValidationDisposition,
            results: List<StrategyConstraintResult>,
            policyId: StrategyPolicyId,
            policyVersion: StrategyPolicyVersion,
            validatedAt: Instant
        ): StrategyValidationId {
            val d = MessageDigest.getInstance("SHA-256")
            StrategyCandidate.put(d, "strategy-validation-v1")
            StrategyCandidate.put(d, candidate.id.value)
            StrategyCandidate.put(d, candidate.version.value.toString())
            StrategyCandidate.put(d, disposition.name)
            results.forEach {
                StrategyCandidate.put(d, it.constraint.key)
                StrategyCandidate.put(d, it.constraint.expected)
                StrategyCandidate.put(d, it.disposition.name)
                StrategyCandidate.put(d, it.reason)
            }
            StrategyCandidate.put(d, policyId.value)
            StrategyCandidate.put(d, policyVersion.value.toString())
            StrategyCandidate.put(d, validatedAt.epochSecond.toString())
            StrategyCandidate.put(d, validatedAt.nano.toString())
            return StrategyValidationId(
                "strategy-validation-" + with(StrategyCandidate) { d.digest().toHex() }
            )
        }
    }
}

enum class StrategyAdoptionDisposition { ADOPT, REJECT }

data class StrategyValidationReference(
    val id: StrategyValidationId,
    val candidate: StrategyReference
)

data class StrategyAdoptionRecord(
    val id: StrategyAdoptionId,
    val candidate: StrategyReference,
    val validation: StrategyValidationReference,
    val disposition: StrategyAdoptionDisposition,
    val rationale: String,
    val decidedAt: Instant
) {
    init {
        require(rationale.isNotBlank())
        require(rationale.toByteArray(StandardCharsets.UTF_8).size <= 2048)
        require(validation.candidate == candidate)
        require(id == deterministicId(candidate, validation, disposition, rationale, decidedAt))
    }

    companion object {
        fun create(
            candidate: StrategyReference,
            validation: StrategyValidationReference,
            disposition: StrategyAdoptionDisposition,
            rationale: String,
            decidedAt: Instant
        ) = StrategyAdoptionRecord(
            deterministicId(candidate, validation, disposition, rationale, decidedAt),
            candidate, validation, disposition, rationale, decidedAt
        )

        private fun deterministicId(
            candidate: StrategyReference,
            validation: StrategyValidationReference,
            disposition: StrategyAdoptionDisposition,
            rationale: String,
            decidedAt: Instant
        ): StrategyAdoptionId {
            val d = MessageDigest.getInstance("SHA-256")
            StrategyCandidate.put(d, "strategy-adoption-v1")
            StrategyCandidate.put(d, candidate.id.value)
            StrategyCandidate.put(d, candidate.version.value.toString())
            StrategyCandidate.put(d, validation.id.value)
            StrategyCandidate.put(d, disposition.name)
            StrategyCandidate.put(d, rationale)
            StrategyCandidate.put(d, decidedAt.epochSecond.toString())
            StrategyCandidate.put(d, decidedAt.nano.toString())
            return StrategyAdoptionId(
                "strategy-adoption-" + with(StrategyCandidate) { d.digest().toHex() }
            )
        }
    }
}

data class StrategyAdoptionReference(
    val id: StrategyAdoptionId,
    val candidate: StrategyReference
)

data class StrategyApplicationIntent(
    val id: StrategyApplicationIntentId,
    val candidate: StrategyReference,
    val adoption: StrategyAdoptionReference,
    val target: StrategyTarget,
    val scope: StrategyScope,
    val createdAt: Instant
) {
    init {
        require(adoption.candidate == candidate)
        require(id == deterministicId(candidate, adoption, target, scope, createdAt))
    }

    companion object {
        fun create(
            candidate: StrategyReference,
            adoption: StrategyAdoptionReference,
            target: StrategyTarget,
            scope: StrategyScope,
            createdAt: Instant
        ) = StrategyApplicationIntent(
            deterministicId(candidate, adoption, target, scope, createdAt),
            candidate, adoption, target, scope, createdAt
        )

        private fun deterministicId(
            candidate: StrategyReference,
            adoption: StrategyAdoptionReference,
            target: StrategyTarget,
            scope: StrategyScope,
            createdAt: Instant
        ): StrategyApplicationIntentId {
            val d = MessageDigest.getInstance("SHA-256")
            StrategyCandidate.put(d, "strategy-application-intent-v1")
            StrategyCandidate.put(d, candidate.id.value)
            StrategyCandidate.put(d, candidate.version.value.toString())
            StrategyCandidate.put(d, adoption.id.value)
            StrategyCandidate.put(d, target.name)
            StrategyCandidate.put(d, scope.value)
            StrategyCandidate.put(d, createdAt.epochSecond.toString())
            StrategyCandidate.put(d, createdAt.nano.toString())
            return StrategyApplicationIntentId(
                "strategy-application-intent-" + with(StrategyCandidate) { d.digest().toHex() }
            )
        }
    }
}

data class StrategyAdaptationRecord(
    val candidate: StrategyCandidate,
    val validation: StrategyValidationRecord,
    val adoption: StrategyAdoptionRecord,
    val applicationIntent: StrategyApplicationIntent?
) {
    init {
        val ref = StrategyReference(candidate.id, candidate.version)
        require(validation.candidate == ref)
        require(adoption.candidate == ref)
        require(adoption.validation.id == validation.id)
        require(validation.validatedAt >= candidate.createdAt)
        require(adoption.decidedAt >= validation.validatedAt)
        require(validation.constraintResults.map { it.constraint } == candidate.compatibility) {
            "strategy validation must cover exact canonical candidate constraints"
        }
        if (validation.disposition == StrategyValidationDisposition.EXPIRED) {
            require(candidate.isExpired(validation.validatedAt)) {
                "strategy validation cannot claim expiry before candidate expires"
            }
        } else {
            require(!candidate.isExpired(validation.validatedAt)) {
                "expired strategy validation must use EXPIRED disposition"
            }
            val derived = when {
                validation.constraintResults.any {
                    it.disposition == StrategyConstraintDisposition.UNSATISFIED
                } -> StrategyValidationDisposition.INVALID
                validation.constraintResults.any {
                    it.disposition == StrategyConstraintDisposition.UNKNOWN
                } -> StrategyValidationDisposition.UNKNOWN
                else -> StrategyValidationDisposition.VALID
            }
            require(validation.disposition == derived) {
                "strategy validation disposition must match constraint results"
            }
        }
        if (adoption.disposition == StrategyAdoptionDisposition.ADOPT) {
            require(validation.disposition == StrategyValidationDisposition.VALID)
            require(!candidate.isExpired(adoption.decidedAt))
            require(applicationIntent != null)
        } else {
            require(applicationIntent == null)
        }
        applicationIntent?.let {
            require(it.candidate == ref)
            require(it.adoption.id == adoption.id)
            require(it.target == candidate.target)
            require(it.scope == candidate.scope)
            require(it.createdAt >= adoption.decidedAt)
            require(!candidate.isExpired(it.createdAt))
        }
    }
}

data class StrategyAdaptationSnapshot(
    val record: StrategyAdaptationRecord,
    val generation: Long
) {
    init { require(generation > 0L) }
}
