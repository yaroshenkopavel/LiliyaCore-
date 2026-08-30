package pro.liliya.core.license

import java.time.Instant

@JvmInline
value class LicenseId(val value: String) {
    init { require(value.isNotBlank()) { "license id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class LicenseSubject(val value: String) {
    init { require(value.isNotBlank()) { "license subject must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class LicenseProductId(val value: String) {
    init { require(value.isNotBlank()) { "license product id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class LicenseFeature(val value: String) {
    init { require(value.isNotBlank()) { "license feature must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class LicenseVersion(val value: Long) {
    init { require(value > 0L) { "license version must be positive" } }
    override fun toString(): String = value.toString()
}

@JvmInline
value class LicenseKeyId(val value: String) {
    init { require(value.isNotBlank()) { "license key id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class LicenseGeneration(val value: Long) {
    init { require(value > 0L) { "license generation must be positive" } }
    override fun toString(): String = value.toString()
}

@JvmInline
value class LicenseRevocationEpoch(val value: Long) {
    init { require(value >= 0L) { "license revocation epoch must not be negative" } }
    override fun toString(): String = value.toString()
}

@JvmInline
value class LicenseReplaySequence(val value: Long) {
    init { require(value >= 0L) { "license replay sequence must not be negative" } }
    override fun toString(): String = value.toString()
}

class LicenseEntitlement(
    val id: LicenseId,
    val subject: LicenseSubject,
    val productId: LicenseProductId,
    features: Set<LicenseFeature>,
    val version: LicenseVersion,
    val signingKeyId: LicenseKeyId,
    val issuedAt: Instant,
    val notBefore: Instant,
    val expiresAt: Instant?,
    val offlineLeaseUntil: Instant?,
    val revocationEpoch: LicenseRevocationEpoch,
    val replaySequence: LicenseReplaySequence?
) {
    val features: Set<LicenseFeature> = features.toSet()

    init {
        require(this.features.isNotEmpty()) { "license features must not be empty" }
        require(expiresAt == null || expiresAt.isAfter(notBefore)) {
            "license expiry must be after not-before"
        }
        require(offlineLeaseUntil == null || !offlineLeaseUntil.isBefore(notBefore)) {
            "license offline lease must not end before not-before"
        }
        require(expiresAt == null || offlineLeaseUntil == null || !offlineLeaseUntil.isAfter(expiresAt)) {
            "license offline lease must not exceed license expiry"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is LicenseEntitlement &&
            id == other.id &&
            subject == other.subject &&
            productId == other.productId &&
            features == other.features &&
            version == other.version &&
            signingKeyId == other.signingKeyId &&
            issuedAt == other.issuedAt &&
            notBefore == other.notBefore &&
            expiresAt == other.expiresAt &&
            offlineLeaseUntil == other.offlineLeaseUntil &&
            revocationEpoch == other.revocationEpoch &&
            replaySequence == other.replaySequence

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + subject.hashCode()
        result = 31 * result + productId.hashCode()
        result = 31 * result + features.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + signingKeyId.hashCode()
        result = 31 * result + issuedAt.hashCode()
        result = 31 * result + notBefore.hashCode()
        result = 31 * result + (expiresAt?.hashCode() ?: 0)
        result = 31 * result + (offlineLeaseUntil?.hashCode() ?: 0)
        result = 31 * result + revocationEpoch.hashCode()
        result = 31 * result + (replaySequence?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "LicenseEntitlement(id=$id, productId=$productId, featureCount=${features.size}, " +
            "version=$version, signingKeyId=$signingKeyId, revocationEpoch=$revocationEpoch, " +
            "replaySequence=$replaySequence)"
}

data class LicenseSnapshot(
    val entitlement: LicenseEntitlement,
    val generation: LicenseGeneration
)
