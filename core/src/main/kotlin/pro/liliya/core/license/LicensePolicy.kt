package pro.liliya.core.license

import java.time.Instant

class LicensePolicyRequest(
    val productId: LicenseProductId,
    val feature: LicenseFeature,
    val subject: LicenseSubject? = null
) {
    override fun toString(): String =
        "LicensePolicyRequest(productId=$productId, feature=$feature, subject=<redacted>)"
}

class LicensePolicyContext(
    val now: Instant,
    val minimumRevocationEpoch: LicenseRevocationEpoch = LicenseRevocationEpoch(0),
    val minimumReplaySequence: LicenseReplaySequence? = null,
    val suspiciousTimeOrReplayState: Boolean = false
)

class LicenseDecisionReceipt internal constructor(
    val licenseId: LicenseId,
    val productId: LicenseProductId,
    val feature: LicenseFeature,
    val evaluatedAt: Instant,
    val licenseVersion: LicenseVersion,
    val revocationEpoch: LicenseRevocationEpoch,
    val replaySequence: LicenseReplaySequence?
) {
    override fun toString(): String =
        "LicenseDecisionReceipt(licenseId=$licenseId, productId=$productId, feature=$feature, " +
            "evaluatedAt=$evaluatedAt, licenseVersion=$licenseVersion, " +
            "revocationEpoch=$revocationEpoch, replaySequence=$replaySequence)"
}

sealed interface LicenseDecision {
    data class Entitled(val receipt: LicenseDecisionReceipt) : LicenseDecision
    data class Denied(val reason: LicenseDenialReason) : LicenseDecision
}

enum class LicenseDenialReason {
    PRODUCT_MISMATCH,
    FEATURE_NOT_ENTITLED,
    SUBJECT_MISMATCH,
    NOT_YET_VALID,
    EXPIRED,
    OFFLINE_LEASE_EXPIRED,
    STALE_REVOCATION_EPOCH,
    REPLAY_SEQUENCE_MISSING,
    STALE_REPLAY_SEQUENCE,
    SUSPICIOUS_TIME_OR_REPLAY_STATE
}

class LicensePolicy {
    fun evaluate(
        verified: LicenseVerificationResult.Verified,
        request: LicensePolicyRequest,
        context: LicensePolicyContext
    ): LicenseDecision {
        val entitlement = verified.entitlement

        if (context.suspiciousTimeOrReplayState) {
            return denied(LicenseDenialReason.SUSPICIOUS_TIME_OR_REPLAY_STATE)
        }
        if (entitlement.productId != request.productId) {
            return denied(LicenseDenialReason.PRODUCT_MISMATCH)
        }
        if (request.feature !in entitlement.features) {
            return denied(LicenseDenialReason.FEATURE_NOT_ENTITLED)
        }
        if (request.subject != null && entitlement.subject != request.subject) {
            return denied(LicenseDenialReason.SUBJECT_MISMATCH)
        }
        if (context.now.isBefore(entitlement.notBefore)) {
            return denied(LicenseDenialReason.NOT_YET_VALID)
        }
        if (entitlement.expiresAt != null && !context.now.isBefore(entitlement.expiresAt)) {
            return denied(LicenseDenialReason.EXPIRED)
        }
        if (
            entitlement.offlineLeaseUntil != null &&
            !context.now.isBefore(entitlement.offlineLeaseUntil)
        ) {
            return denied(LicenseDenialReason.OFFLINE_LEASE_EXPIRED)
        }
        if (entitlement.revocationEpoch.value < context.minimumRevocationEpoch.value) {
            return denied(LicenseDenialReason.STALE_REVOCATION_EPOCH)
        }

        val minimumReplay = context.minimumReplaySequence
        if (minimumReplay != null) {
            val actualReplay = entitlement.replaySequence
                ?: return denied(LicenseDenialReason.REPLAY_SEQUENCE_MISSING)
            if (actualReplay.value < minimumReplay.value) {
                return denied(LicenseDenialReason.STALE_REPLAY_SEQUENCE)
            }
        }

        return LicenseDecision.Entitled(
            LicenseDecisionReceipt(
                licenseId = entitlement.id,
                productId = entitlement.productId,
                feature = request.feature,
                evaluatedAt = context.now,
                licenseVersion = entitlement.version,
                revocationEpoch = entitlement.revocationEpoch,
                replaySequence = entitlement.replaySequence
            )
        )
    }

    private fun denied(reason: LicenseDenialReason): LicenseDecision.Denied =
        LicenseDecision.Denied(reason)
}
