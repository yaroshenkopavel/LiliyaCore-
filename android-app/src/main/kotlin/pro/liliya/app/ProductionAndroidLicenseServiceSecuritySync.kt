package pro.liliya.app

import android.content.Context
import java.util.UUID
import pro.liliya.android.runtime.AndroidProductRuntimeStartupRequestSourceInput
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.license.JcaEcdsaP256LicenseServiceProofVerifier
import pro.liliya.core.license.LicensePolicyContext
import pro.liliya.core.license.LicenseReplaySequence
import pro.liliya.core.license.LicenseRevocationEpoch
import pro.liliya.core.license.LicenseServiceDurableInitializationResult
import pro.liliya.core.license.LicenseServiceDurablePolicyContextResult
import pro.liliya.core.license.LicenseServiceDurableStateAcceptanceResult
import pro.liliya.core.license.LicenseServiceDurableStateCoordinator
import pro.liliya.core.license.LicenseServiceDurableStoreId
import pro.liliya.core.license.LicenseServiceEvidenceProfile
import pro.liliya.core.license.LicenseServiceEvidencePurpose
import pro.liliya.core.license.LicenseServiceProtocolVersion
import pro.liliya.core.license.LicenseServiceRequestId
import pro.liliya.core.license.LicenseServiceSecurityScope
import pro.liliya.core.license.LicenseServiceTrustedKeyResolver
import pro.liliya.core.license.LicenseServiceTrustedVerificationKey
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.licensetransport.LicenseHttpTransportConfig
import pro.liliya.core.licensetransport.ServiceStateHttpClient
import pro.liliya.core.licensetransport.ServiceStateHttpRequest
import pro.liliya.core.licensetransport.ServiceStateHttpResult

/**
 * Explicit non-secret trust and transport inputs for opportunistic production revocation sync.
 *
 * Sync Profile != Product Auth Secret.
 * Sync Profile != License Entitlement.
 * Sync Profile != Authority/Execution permission.
 */
internal data class ProductionAndroidLicenseServiceSecuritySyncProfile(
    val transport: LicenseHttpTransportConfig,
    val trustedServiceStateKey: LicenseServiceTrustedVerificationKey
)

internal fun interface ProductionAndroidLicenseServiceSecuritySyncProfileSource {
    fun load(): ProductionAndroidLicenseServiceSecuritySyncProfile
}

internal object ProductionAndroidLicenseServiceSecuritySyncProfileSourceOwner {
    @Volatile
    private var source: ProductionAndroidLicenseServiceSecuritySyncProfileSource? = null

    @Synchronized
    fun install(value: ProductionAndroidLicenseServiceSecuritySyncProfileSource): Boolean {
        if (source != null) return false
        source = value
        return true
    }

    fun current(): ProductionAndroidLicenseServiceSecuritySyncProfileSource? = source

    @Synchronized
    internal fun clearForTests() {
        source = null
    }
}

internal enum class ProductionAndroidLicenseServiceSecuritySyncContact {
    NOT_CONFIGURED,
    TRANSPORT_UNAVAILABLE,
    VERIFIED_UNCHANGED,
    VERIFIED_ADVANCED
}

internal enum class ProductionAndroidLicenseServiceSecuritySyncFailure {
    PROFILE_INVALID,
    DURABLE_STATE_REJECTED,
    SERVICE_REJECTED,
    EVIDENCE_REJECTED
}

internal sealed interface ProductionAndroidLicenseServiceSecuritySyncCoreResult {
    data class Ready(
        val context: LicensePolicyContext,
        val contact: ProductionAndroidLicenseServiceSecuritySyncContact
    ) : ProductionAndroidLicenseServiceSecuritySyncCoreResult

    data class Rejected(
        val reason: ProductionAndroidLicenseServiceSecuritySyncFailure
    ) : ProductionAndroidLicenseServiceSecuritySyncCoreResult
}

internal sealed interface ProductionAndroidLicenseServiceSecuritySyncResult {
    data class Ready(
        val input: AndroidProductRuntimeStartupRequestSourceInput,
        val contact: ProductionAndroidLicenseServiceSecuritySyncContact
    ) : ProductionAndroidLicenseServiceSecuritySyncResult

    data class Rejected(
        val reason: ProductionAndroidLicenseServiceSecuritySyncFailure
    ) : ProductionAndroidLicenseServiceSecuritySyncResult
}

/**
 * Restores the last authenticated service-security floor first, then opportunistically contacts the
 * Licensing Service when an explicit sync profile is installed.
 *
 * No successful contact is required for ordinary offline startup. A failed transport never lowers
 * or clears durable state. A successful evidence response must verify and durably commit before its
 * revocation/replay floor can affect LicensePolicy.
 */
internal object ProductionAndroidLicenseServiceSecuritySync {
    fun refresh(
        context: Context,
        input: AndroidProductRuntimeStartupRequestSourceInput
    ): ProductionAndroidLicenseServiceSecuritySyncResult =
        when (
            val core = refreshCore(
                context = context,
                foundation = input.foundation,
                verifiedLicense = input.verifiedLicense,
                currentPolicyContext = input.licensePolicyContext
            )
        ) {
            is ProductionAndroidLicenseServiceSecuritySyncCoreResult.Ready ->
                ProductionAndroidLicenseServiceSecuritySyncResult.Ready(
                    input = input.copy(licensePolicyContext = core.context),
                    contact = core.contact
                )

            is ProductionAndroidLicenseServiceSecuritySyncCoreResult.Rejected ->
                ProductionAndroidLicenseServiceSecuritySyncResult.Rejected(core.reason)
        }

    internal fun refreshCore(
        context: Context,
        foundation: FoundationComposition,
        verifiedLicense: LicenseVerificationResult.Verified,
        currentPolicyContext: LicensePolicyContext
    ): ProductionAndroidLicenseServiceSecuritySyncCoreResult {
        val profile = try {
            ProductionAndroidLicenseServiceSecuritySyncProfileSourceOwner.current()?.load()
        } catch (_: Throwable) {
            return ProductionAndroidLicenseServiceSecuritySyncCoreResult.Rejected(
                ProductionAndroidLicenseServiceSecuritySyncFailure.PROFILE_INVALID
            )
        }

        val scope = LicenseServiceSecurityScope(
            productId = verifiedLicense.entitlement.productId,
            subject = verifiedLicense.entitlement.subject
        )
        val coordinator = try {
            coordinator(
                context = context,
                foundation = foundation,
                profile = profile
            )
        } catch (_: Throwable) {
            return ProductionAndroidLicenseServiceSecuritySyncCoreResult.Rejected(
                ProductionAndroidLicenseServiceSecuritySyncFailure.DURABLE_STATE_REJECTED
            )
        }

        when (coordinator.initialize()) {
            LicenseServiceDurableInitializationResult.Missing -> Unit
            is LicenseServiceDurableInitializationResult.Restored,
            is LicenseServiceDurableInitializationResult.AlreadyInitialized -> Unit
            is LicenseServiceDurableInitializationResult.Rejected ->
                return ProductionAndroidLicenseServiceSecuritySyncCoreResult.Rejected(
                    ProductionAndroidLicenseServiceSecuritySyncFailure.DURABLE_STATE_REJECTED
                )
        }

        var effective = mergeDurableContext(
            current = currentPolicyContext,
            coordinator = coordinator,
            scope = scope
        ) ?: return ProductionAndroidLicenseServiceSecuritySyncCoreResult.Rejected(
            ProductionAndroidLicenseServiceSecuritySyncFailure.DURABLE_STATE_REJECTED
        )

        if (profile == null) {
            return ProductionAndroidLicenseServiceSecuritySyncCoreResult.Ready(
                context = effective,
                contact = ProductionAndroidLicenseServiceSecuritySyncContact.NOT_CONFIGURED
            )
        }

        val credentialFactory = try {
            ProductionAndroidProductAuthCredentialAdapter.bearerFactory(
                ProductionAndroidProductAuthEncryptedStore.create(context)
            )
        } catch (_: Throwable) {
            return ProductionAndroidLicenseServiceSecuritySyncCoreResult.Ready(
                context = effective,
                contact = ProductionAndroidLicenseServiceSecuritySyncContact.TRANSPORT_UNAVAILABLE
            )
        }
        val credential = try {
            credentialFactory.create()
        } catch (_: Throwable) {
            return ProductionAndroidLicenseServiceSecuritySyncCoreResult.Ready(
                context = effective,
                contact = ProductionAndroidLicenseServiceSecuritySyncContact.TRANSPORT_UNAVAILABLE
            )
        }

        val response = try {
            ServiceStateHttpClient(profile.transport).execute(
                request = ServiceStateHttpRequest(
                    protocolVersion = LicenseServiceProtocolVersion(PROTOCOL_VERSION),
                    scope = scope,
                    requestId = LicenseServiceRequestId(
                        "android-startup-service-state-" + UUID.randomUUID().toString()
                    )
                ),
                authentication = credential
            )
        } catch (_: Throwable) {
            ServiceStateHttpResult.Failed(
                pro.liliya.core.licensetransport.LicenseClientTransportFailure.CONNECT_FAILURE
            )
        } finally {
            credential.close()
        }

        val contact = when (response) {
            is ServiceStateHttpResult.Failed ->
                return ProductionAndroidLicenseServiceSecuritySyncCoreResult.Ready(
                    context = effective,
                    contact = ProductionAndroidLicenseServiceSecuritySyncContact.TRANSPORT_UNAVAILABLE
                )

            is ServiceStateHttpResult.ServiceRejected ->
                return ProductionAndroidLicenseServiceSecuritySyncCoreResult.Rejected(
                    ProductionAndroidLicenseServiceSecuritySyncFailure.SERVICE_REJECTED
                )

            is ServiceStateHttpResult.Evidence ->
                when (coordinator.verifyAndAccept(response.envelope)) {
                    is LicenseServiceDurableStateAcceptanceResult.Advanced ->
                        ProductionAndroidLicenseServiceSecuritySyncContact.VERIFIED_ADVANCED
                    is LicenseServiceDurableStateAcceptanceResult.Unchanged ->
                        ProductionAndroidLicenseServiceSecuritySyncContact.VERIFIED_UNCHANGED
                    is LicenseServiceDurableStateAcceptanceResult.VerificationRejected,
                    is LicenseServiceDurableStateAcceptanceResult.StateRejected ->
                        return ProductionAndroidLicenseServiceSecuritySyncCoreResult.Rejected(
                            ProductionAndroidLicenseServiceSecuritySyncFailure.EVIDENCE_REJECTED
                        )
                    is LicenseServiceDurableStateAcceptanceResult.DurableRejected ->
                        return ProductionAndroidLicenseServiceSecuritySyncCoreResult.Rejected(
                            ProductionAndroidLicenseServiceSecuritySyncFailure.DURABLE_STATE_REJECTED
                        )
                }
        }

        effective = mergeDurableContext(
            current = effective,
            coordinator = coordinator,
            scope = scope
        ) ?: return ProductionAndroidLicenseServiceSecuritySyncCoreResult.Rejected(
            ProductionAndroidLicenseServiceSecuritySyncFailure.DURABLE_STATE_REJECTED
        )

        return ProductionAndroidLicenseServiceSecuritySyncCoreResult.Ready(
            context = effective,
            contact = contact
        )
    }

    private fun coordinator(
        context: Context,
        foundation: FoundationComposition,
        profile: ProductionAndroidLicenseServiceSecuritySyncProfile?
    ): LicenseServiceDurableStateCoordinator {
        val storeId = LicenseServiceDurableStoreId(STORE_ID)
        val trusted = profile?.trustedServiceStateKey
        return LicenseServiceDurableStateCoordinator(
            foundation = foundation,
            storeId = storeId,
            supportedProtocolVersion = LicenseServiceProtocolVersion(PROTOCOL_VERSION),
            supportedPurposes = setOf(LicenseServiceEvidencePurpose.SECURITY_STATE),
            supportedProfiles = setOf(LicenseServiceEvidenceProfile(SERVICE_PROFILE)),
            trustedKeys = LicenseServiceTrustedKeyResolver { keyId, evidenceProfile ->
                trusted?.takeIf {
                    it.keyId == keyId && it.profile == evidenceProfile
                }
            },
            proofVerifier = JcaEcdsaP256LicenseServiceProofVerifier,
            backend = ProductionAndroidLicenseServiceDurableBackend.create(context),
            protector = ProductionAndroidLicenseServiceDurableProtector.create(
                context = context,
                storeId = storeId
            )
        )
    }

    private fun mergeDurableContext(
        current: LicensePolicyContext,
        coordinator: LicenseServiceDurableStateCoordinator,
        scope: LicenseServiceSecurityScope
    ): LicensePolicyContext? =
        when (
            val durable = coordinator.policyContext(
                scope = scope,
                now = current.now,
                suspiciousTimeOrReplayState = current.suspiciousTimeOrReplayState
            )
        ) {
            LicenseServiceDurablePolicyContextResult.Missing -> current

            is LicenseServiceDurablePolicyContextResult.Available -> {
                val context = durable.context
                LicensePolicyContext(
                    now = current.now,
                    minimumRevocationEpoch = maxRevocation(
                        current.minimumRevocationEpoch,
                        context.minimumRevocationEpoch
                    ),
                    minimumReplaySequence = maxReplay(
                        current.minimumReplaySequence,
                        context.minimumReplaySequence
                    ),
                    suspiciousTimeOrReplayState =
                        current.suspiciousTimeOrReplayState ||
                            context.suspiciousTimeOrReplayState,
                    requiredDeviceBindingReference =
                        current.requiredDeviceBindingReference
                )
            }
        }

    private fun maxRevocation(
        current: LicenseRevocationEpoch,
        durable: LicenseRevocationEpoch
    ): LicenseRevocationEpoch =
        if (durable.value > current.value) durable else current

    private fun maxReplay(
        current: LicenseReplaySequence?,
        durable: LicenseReplaySequence?
    ): LicenseReplaySequence? = when {
        current == null -> durable
        durable == null -> current
        durable.value > current.value -> durable
        else -> current
    }

    private const val STORE_ID = "license-service-security-state-v1"
    private const val PROTOCOL_VERSION = 1L
    private const val SERVICE_PROFILE = "ECDSA-P256-SHA256-SERVICE-STATE-V1"
}
