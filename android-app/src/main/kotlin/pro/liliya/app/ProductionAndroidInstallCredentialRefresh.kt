package pro.liliya.app

import android.content.Context
import java.time.Instant
import java.util.UUID
import pro.liliya.android.runtime.AndroidProductRuntimeLicenseTrustMaterial
import pro.liliya.android.runtime.AndroidProductRuntimeLicenseTrustMaterialResult
import pro.liliya.core.license.JcaEcdsaP256LicenseSignatureVerifier
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseDecision
import pro.liliya.core.license.LicensePolicy
import pro.liliya.core.license.LicensePolicyContext
import pro.liliya.core.license.LicenseReplaySequence
import pro.liliya.core.license.LicenseRevocationEpoch
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.license.LicenseVerifier
import pro.liliya.core.license.LicenseVersion
import pro.liliya.core.licensetransport.LicenseClientTransportFailure
import pro.liliya.core.licensetransport.LicenseClientTransportResult
import pro.liliya.core.licensetransport.LicenseHttpBearerCredential
import pro.liliya.core.licensetransport.LicenseHttpTransportClient
import pro.liliya.core.licensetransport.LicenseRefreshRequestFactory

internal sealed interface ProductionAndroidRefreshResult {
    data object StagedForRestart : ProductionAndroidRefreshResult
    data object Rejected : ProductionAndroidRefreshResult
    data class ServiceRejected(val reason: pro.liliya.core.licensetransport.LicenseRemoteServiceFailure) :
        ProductionAndroidRefreshResult
    data class TransportFailed(val reason: LicenseClientTransportFailure) : ProductionAndroidRefreshResult
}

/** REFRESH accepts and stages a new signed license; active runtime authority changes on restart. */
internal object ProductionAndroidInstallCredentialRefresh {
    @Synchronized
    fun executeAndStage(
        context: Context
    ): ProductionAndroidRefreshResult {
        val installed = ProductionAndroidRuntimeStartupInputConfiguration.current()
            ?: return ProductionAndroidRefreshResult.Rejected
        val verifiedCurrentLicense = installed.verifiedLicense
        val profile = try {
            ProductionAndroidFirstRunProductProfileSourceOwner.current()?.load()
        } catch (_: RuntimeException) {
            null
        } ?: return ProductionAndroidRefreshResult.Rejected
        val trust = when (val result = AndroidProductRuntimeLicenseTrustMaterial.create(
            profile.productInputTemplate.licenseTrustKeys
        )) {
            is AndroidProductRuntimeLicenseTrustMaterialResult.Ready -> result.resolver
            AndroidProductRuntimeLicenseTrustMaterialResult.Rejected ->
                return ProductionAndroidRefreshResult.Rejected
        }
        val material = try {
            ProductionAndroidInstallCredentialEncryptedStore.create(context).openMaterial()
        } catch (_: Throwable) {
            return ProductionAndroidRefreshResult.Rejected
        }

        val transportResult = material.use {
            val secret = material.copySecret()
            try {
                val request = LicenseRefreshRequestFactory.fromVerifiedLicense(
                    verified = verifiedCurrentLicense,
                    requestId = LicenseServiceRequestId(UUID.randomUUID().toString())
                )
                val client = if (profile.transport.endpoint.host == "liliya-licensing.internal") {
                    val engine = ProductionAndroidLanConnection.discoverEngine(context, profile)
                        ?: return ProductionAndroidRefreshResult.TransportFailed(
                            LicenseClientTransportFailure.CONNECT_FAILURE
                        )
                    LicenseHttpTransportClient(profile.transport, engine)
                } else {
                    LicenseHttpTransportClient(profile.transport)
                }
                LicenseHttpBearerCredential.of(secret).use { credential ->
                    client.execute(request, credential)
                }
            } catch (_: RuntimeException) {
                LicenseClientTransportResult.Failed(LicenseClientTransportFailure.INVALID_LOCAL_REQUEST)
            } finally {
                secret.fill(0)
            }
        }
        return when (transportResult) {
            is LicenseClientTransportResult.Failed ->
                ProductionAndroidRefreshResult.TransportFailed(transportResult.reason)
            is LicenseClientTransportResult.ServiceRejected ->
                ProductionAndroidRefreshResult.ServiceRejected(transportResult.reason)
            is LicenseClientTransportResult.Signed -> {
                val refreshed = try {
                    LicenseVerifier(
                        supportedSchemaVersion = LicenseVersion(profile.productInputTemplate.supportedLicenseSchemaVersion),
                        supportedAlgorithms = setOf(LicenseAlgorithm("ECDSA-P256-SHA256")),
                        trustedKeys = trust,
                        signatureVerifier = JcaEcdsaP256LicenseSignatureVerifier
                    ).verify(transportResult.envelope)
                } catch (_: RuntimeException) {
                    return ProductionAndroidRefreshResult.Rejected
                }
                if (refreshed !is LicenseVerificationResult.Verified) {
                    return ProductionAndroidRefreshResult.Rejected
                }
                val old = verifiedCurrentLicense.entitlement
                val fresh = refreshed.entitlement
                val oldSequence = old.replaySequence?.value
                    ?: return ProductionAndroidRefreshResult.Rejected
                if (
                    fresh.subject != old.subject || fresh.productId != old.productId ||
                    fresh.revocationEpoch.value < old.revocationEpoch.value ||
                    oldSequence == Long.MAX_VALUE ||
                    fresh.replaySequence?.value?.let { it > oldSequence } != true
                ) return ProductionAndroidRefreshResult.Rejected

                val decision = LicensePolicy().evaluate(
                    verified = refreshed,
                    request = installed.licenseRequest,
                    context = LicensePolicyContext(
                        now = Instant.now(),
                        minimumRevocationEpoch = LicenseRevocationEpoch(
                            maxOf(old.revocationEpoch.value,
                                installed.licensePolicyContext.minimumRevocationEpoch.value)
                        ),
                        minimumReplaySequence = LicenseReplaySequence(
                            maxOf(oldSequence + 1,
                                installed.licensePolicyContext.minimumReplaySequence?.value ?: 0L)
                        ),
                        suspiciousTimeOrReplayState = installed.licensePolicyContext.suspiciousTimeOrReplayState
                    )
                )
                if (decision !is LicenseDecision.Entitled) {
                    return ProductionAndroidRefreshResult.Rejected
                }
                if (!ProductionAndroidPendingActivationEnvelopeStore.create(context).save(transportResult.envelope)) {
                    return ProductionAndroidRefreshResult.Rejected
                }
                ProductionAndroidRefreshResult.StagedForRestart
            }
        }
    }
}
