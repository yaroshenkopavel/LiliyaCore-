package pro.liliya.app

import android.content.Context
import java.time.Instant
import pro.liliya.android.devicekey.AndroidActivationDeviceBinding
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingProvider
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingResult
import pro.liliya.android.runtime.AndroidProductRuntimeStartupAdmissionInput
import pro.liliya.android.runtime.AndroidProductRuntimeStartupTrustOwnership
import pro.liliya.core.license.LicenseDeviceBindingReference
import pro.liliya.core.license.LicenseDeviceBindingReferenceFactory
import pro.liliya.core.license.LicensePolicyContext
import pro.liliya.core.license.LicenseReplaySequence
import pro.liliya.core.license.LicenseRevocationEpoch

internal data class ProductionAndroidOfflineResumeAdmissionPolicy(
    val feature: String,
    val principal: String,
    val capability: String,
    val authorityScope: String
)

internal data class ProductionAndroidOfflineResumeVerifiedLicenseFacts(
    val productId: String,
    val subject: String,
    val revocationEpoch: Long,
    val replaySequence: Long?,
    val deviceBindingReference: String?
)

internal enum class ProductionAndroidOfflineResumeAdmissionFailure {
    DEPLOYMENT_PRODUCT_MISMATCH,
    DEVICE_BINDING_MISSING,
    DEVICE_BINDING_MISMATCH,
    DEVICE_BINDING_REJECTED,
    SECURITY_STATE_REJECTED,
    FAILED
}

internal sealed interface ProductionAndroidOfflineResumeAdmissionResult {
    data class Ready(
        val input: AndroidProductRuntimeStartupAdmissionInput,
        val effectivePolicyContext: LicensePolicyContext
    ) : ProductionAndroidOfflineResumeAdmissionResult

    data class Rejected(
        val reason: ProductionAndroidOfflineResumeAdmissionFailure
    ) : ProductionAndroidOfflineResumeAdmissionResult
}

internal fun interface ProductionAndroidOfflineResumeDeviceBindingPort {
    fun load(): AndroidActivationDeviceBindingResult
}

internal fun interface ProductionAndroidOfflineResumeSecurityFloorPort {
    fun merge(
        current: LicensePolicyContext
    ): ProductionAndroidLicenseServiceSecuritySyncCoreResult
}

/**
 * Builds fresh cold-resume admission only after verified License facts, current device binding and
 * durable License-Service floors agree.
 *
 * Admission Build != License verification.
 * Admission Build != Authority ownership.
 * Admission Build != Execution permission.
 */
internal object ProductionAndroidOfflineResumeAdmissionBuilder {
    fun build(
        context: Context,
        material: ProductionAndroidOfflineResumeMaterial,
        trust: AndroidProductRuntimeStartupTrustOwnership,
        policy: ProductionAndroidOfflineResumeAdmissionPolicy,
        now: Instant = Instant.now()
    ): ProductionAndroidOfflineResumeAdmissionResult {
        val entitlement = trust.verifiedLicense.entitlement
        val facts = ProductionAndroidOfflineResumeVerifiedLicenseFacts(
            productId = entitlement.productId.value,
            subject = entitlement.subject.value,
            revocationEpoch = entitlement.revocationEpoch.value,
            replaySequence = entitlement.replaySequence?.value,
            deviceBindingReference = entitlement.deviceBindingReference?.value
        )

        return build(
            deploymentProductId = material.deploymentProfile.productId,
            facts = facts,
            policy = policy,
            now = now,
            deviceBindingPort = ProductionAndroidOfflineResumeDeviceBindingPort {
                try {
                    AndroidActivationDeviceBindingProvider(context.applicationContext).loadOrCreate()
                } catch (_: Throwable) {
                    AndroidActivationDeviceBindingResult.Rejected("INTERNAL_FAILURE")
                }
            },
            securityFloorPort = ProductionAndroidOfflineResumeSecurityFloorPort { current ->
                ProductionAndroidLicenseServiceSecuritySync.refreshCore(
                    context = context.applicationContext,
                    foundation = trust.foundation,
                    verifiedLicense = trust.verifiedLicense,
                    currentPolicyContext = current
                )
            }
        )
    }

    internal fun build(
        deploymentProductId: String,
        facts: ProductionAndroidOfflineResumeVerifiedLicenseFacts,
        policy: ProductionAndroidOfflineResumeAdmissionPolicy,
        now: Instant,
        deviceBindingPort: ProductionAndroidOfflineResumeDeviceBindingPort,
        securityFloorPort: ProductionAndroidOfflineResumeSecurityFloorPort
    ): ProductionAndroidOfflineResumeAdmissionResult {
        if (deploymentProductId != facts.productId) {
            return rejected(
                ProductionAndroidOfflineResumeAdmissionFailure.DEPLOYMENT_PRODUCT_MISMATCH
            )
        }

        val signedBinding = facts.deviceBindingReference
            ?: return rejected(
                ProductionAndroidOfflineResumeAdmissionFailure.DEVICE_BINDING_MISSING
            )

        val deviceBinding = when (
            val loaded = try {
                deviceBindingPort.load()
            } catch (_: Throwable) {
                return rejected(
                    ProductionAndroidOfflineResumeAdmissionFailure.DEVICE_BINDING_REJECTED
                )
            }
        ) {
            is AndroidActivationDeviceBindingResult.Ready -> loaded.binding
            is AndroidActivationDeviceBindingResult.Rejected,
            AndroidActivationDeviceBindingResult.MalformedLocalState ->
                return rejected(
                    ProductionAndroidOfflineResumeAdmissionFailure.DEVICE_BINDING_REJECTED
                )
        }

        val currentBinding = try {
            currentBindingReference(deviceBinding)
        } catch (_: Throwable) {
            return rejected(
                ProductionAndroidOfflineResumeAdmissionFailure.DEVICE_BINDING_REJECTED
            )
        }

        if (signedBinding != currentBinding) {
            return rejected(
                ProductionAndroidOfflineResumeAdmissionFailure.DEVICE_BINDING_MISMATCH
            )
        }

        val baseContext = try {
            LicensePolicyContext(
                now = now,
                minimumRevocationEpoch =
                    LicenseRevocationEpoch(facts.revocationEpoch),
                minimumReplaySequence =
                    facts.replaySequence?.let(::LicenseReplaySequence),
                suspiciousTimeOrReplayState = false,
                requiredDeviceBindingReference =
                    LicenseDeviceBindingReference(currentBinding)
            )
        } catch (_: Throwable) {
            return rejected(ProductionAndroidOfflineResumeAdmissionFailure.FAILED)
        }

        val effective = when (
            val merged = try {
                securityFloorPort.merge(baseContext)
            } catch (_: Throwable) {
                return rejected(
                    ProductionAndroidOfflineResumeAdmissionFailure.SECURITY_STATE_REJECTED
                )
            }
        ) {
            is ProductionAndroidLicenseServiceSecuritySyncCoreResult.Ready -> merged.context
            is ProductionAndroidLicenseServiceSecuritySyncCoreResult.Rejected ->
                return rejected(
                    ProductionAndroidOfflineResumeAdmissionFailure.SECURITY_STATE_REJECTED
                )
        }

        val admission = try {
            AndroidProductRuntimeStartupAdmissionInput(
                productId = facts.productId,
                feature = policy.feature,
                subject = facts.subject,
                now = effective.now,
                minimumRevocationEpoch = effective.minimumRevocationEpoch.value,
                minimumReplaySequence = effective.minimumReplaySequence?.value,
                suspiciousTimeOrReplayState = effective.suspiciousTimeOrReplayState,
                requiredDeviceBindingReference =
                    effective.requiredDeviceBindingReference?.value,
                principal = policy.principal,
                capability = policy.capability,
                authorityScope = policy.authorityScope
            )
        } catch (_: Throwable) {
            return rejected(ProductionAndroidOfflineResumeAdmissionFailure.FAILED)
        }

        return ProductionAndroidOfflineResumeAdmissionResult.Ready(
            input = admission,
            effectivePolicyContext = effective
        )
    }

    private fun currentBindingReference(
        binding: AndroidActivationDeviceBinding
    ): String =
        LicenseDeviceBindingReferenceFactory.create(
            installationId = binding.installationId,
            deviceKeyFingerprint = binding.deviceKeyFingerprint
        ).value

    private fun rejected(
        reason: ProductionAndroidOfflineResumeAdmissionFailure
    ) = ProductionAndroidOfflineResumeAdmissionResult.Rejected(reason)
}
