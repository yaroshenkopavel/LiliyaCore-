package pro.liliya.app

/** Stable identity for a product-model candidate. No field here selects or downloads a model. */
internal data class ProductionAndroidProductModelCandidate(
    val modelId: String,
    val artifactSha256: String,
    val quantization: String
) {
    init {
        require(modelId.isNotBlank())
        require(artifactSha256.matches(Regex("[0-9a-f]{64}")))
        require(quantization.isNotBlank())
    }
}

/**
 * Independent acceptance evidence required before a candidate may become a product model.
 * Measurements remain owned by the external acceptance run; booleans mean those measurements were
 * reviewed against the product limits, not that this class performed or inferred them.
 */
internal data class ProductionAndroidProductModelAcceptanceEvidence(
    val provenanceAccepted: Boolean,
    val licenseAccepted: Boolean,
    val russianQualityAccepted: Boolean,
    val physicalArm64ExecutionAccepted: Boolean,
    val peakMemoryAccepted: Boolean,
    val latencyAccepted: Boolean,
    val contextAccepted: Boolean,
    val packageSizeAccepted: Boolean,
    val thermalAndBatteryAccepted: Boolean
)

internal enum class ProductionAndroidProductModelAcceptanceRejection {
    PROVENANCE_NOT_ACCEPTED,
    LICENSE_NOT_ACCEPTED,
    RUSSIAN_QUALITY_NOT_ACCEPTED,
    PHYSICAL_ARM64_NOT_ACCEPTED,
    PEAK_MEMORY_NOT_ACCEPTED,
    LATENCY_NOT_ACCEPTED,
    CONTEXT_NOT_ACCEPTED,
    PACKAGE_SIZE_NOT_ACCEPTED,
    THERMAL_AND_BATTERY_NOT_ACCEPTED
}

internal sealed interface ProductionAndroidProductModelAcceptanceResult {
    data class Ready(
        val candidate: ProductionAndroidProductModelCandidate
    ) : ProductionAndroidProductModelAcceptanceResult

    data class Rejected(
        val candidate: ProductionAndroidProductModelCandidate,
        val reason: ProductionAndroidProductModelAcceptanceRejection
    ) : ProductionAndroidProductModelAcceptanceResult
}

internal object ProductionAndroidProductModelAcceptanceGate {
    fun evaluate(
        candidate: ProductionAndroidProductModelCandidate,
        evidence: ProductionAndroidProductModelAcceptanceEvidence
    ): ProductionAndroidProductModelAcceptanceResult {
        val rejection = when {
            !evidence.provenanceAccepted ->
                ProductionAndroidProductModelAcceptanceRejection.PROVENANCE_NOT_ACCEPTED
            !evidence.licenseAccepted ->
                ProductionAndroidProductModelAcceptanceRejection.LICENSE_NOT_ACCEPTED
            !evidence.russianQualityAccepted ->
                ProductionAndroidProductModelAcceptanceRejection.RUSSIAN_QUALITY_NOT_ACCEPTED
            !evidence.physicalArm64ExecutionAccepted ->
                ProductionAndroidProductModelAcceptanceRejection.PHYSICAL_ARM64_NOT_ACCEPTED
            !evidence.peakMemoryAccepted ->
                ProductionAndroidProductModelAcceptanceRejection.PEAK_MEMORY_NOT_ACCEPTED
            !evidence.latencyAccepted ->
                ProductionAndroidProductModelAcceptanceRejection.LATENCY_NOT_ACCEPTED
            !evidence.contextAccepted ->
                ProductionAndroidProductModelAcceptanceRejection.CONTEXT_NOT_ACCEPTED
            !evidence.packageSizeAccepted ->
                ProductionAndroidProductModelAcceptanceRejection.PACKAGE_SIZE_NOT_ACCEPTED
            !evidence.thermalAndBatteryAccepted ->
                ProductionAndroidProductModelAcceptanceRejection.THERMAL_AND_BATTERY_NOT_ACCEPTED
            else -> null
        }
        return if (rejection == null) {
            ProductionAndroidProductModelAcceptanceResult.Ready(candidate)
        } else {
            ProductionAndroidProductModelAcceptanceResult.Rejected(candidate, rejection)
        }
    }
}
