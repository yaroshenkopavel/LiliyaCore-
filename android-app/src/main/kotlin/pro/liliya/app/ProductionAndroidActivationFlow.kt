package pro.liliya.app

import android.content.Context
import java.net.URL
import pro.liliya.core.licensetransport.LicenseActivationTransportClient
import pro.liliya.core.licensetransport.LicenseActivationTransportConfig
import pro.liliya.core.licensetransport.LicenseClientTransportFailure
import pro.liliya.core.licensetransport.LicenseClientTransportResult
import pro.liliya.core.licensetransport.LicenseRemoteServiceFailure

internal sealed interface ProductionAndroidActivationResult {
    data object Activated : ProductionAndroidActivationResult
    data object AlreadyActivated : ProductionAndroidActivationResult
    data object ProductProfileRequired : ProductionAndroidActivationResult
    data class ServiceRejected(val reason: LicenseRemoteServiceFailure) :
        ProductionAndroidActivationResult
    data class TransportFailed(val reason: LicenseClientTransportFailure) :
        ProductionAndroidActivationResult
    data object Failed : ProductionAndroidActivationResult
}

internal object ProductionAndroidActivationFlow {
    fun activate(
        context: Context,
        activationCode: String
    ): ProductionAndroidActivationResult {
        if (activationCode.isBlank()) return ProductionAndroidActivationResult.Failed

        val source = ProductionAndroidFirstRunProductProfileSourceOwner.current()
            ?: return ProductionAndroidActivationResult.ProductProfileRequired
        val profile = try {
            source.load()
        } catch (_: Throwable) {
            return ProductionAndroidActivationResult.Failed
        }

        val credentialStore = ProductionAndroidInstallCredentialEncryptedStore.create(context)
        when (credentialStore.provisionIfAbsent()) {
            ProductionAndroidInstallCredentialProvisionResult.Provisioned,
            ProductionAndroidInstallCredentialProvisionResult.AlreadyProvisioned -> Unit
            ProductionAndroidInstallCredentialProvisionResult.Failed ->
                return ProductionAndroidActivationResult.Failed
        }

        val material = try {
            credentialStore.openMaterial()
        } catch (_: Throwable) {
            return ProductionAndroidActivationResult.Failed
        }

        return material.use {
            val secret = material.copySecret()
            try {
                val activationEndpoint = try {
                    val endpoint = profile.transport.endpoint
                    URL(
                        endpoint.protocol,
                        endpoint.host,
                        endpoint.port,
                        "/v1/activate"
                    )
                } catch (_: Throwable) {
                    return ProductionAndroidActivationResult.Failed
                }

                val client = try {
                    val configuration = LicenseActivationTransportConfig(
                            endpoint = activationEndpoint,
                            connectTimeoutMillis = profile.transport.connectTimeoutMillis,
                            readTimeoutMillis = profile.transport.readTimeoutMillis,
                            developmentAllowInsecureHttp =
                                profile.transport.developmentAllowInsecureHttp
                    )
                    if (activationEndpoint.host == "liliya-licensing.internal") {
                        val engine = ProductionAndroidLanConnection.discoverEngine(context, profile)
                            ?: return ProductionAndroidActivationResult.TransportFailed(
                                LicenseClientTransportFailure.CONNECT_FAILURE
                            )
                        LicenseActivationTransportClient(configuration, engine)
                    } else {
                        LicenseActivationTransportClient(configuration)
                    }
                } catch (_: Throwable) {
                    return ProductionAndroidActivationResult.Failed
                }

                when (
                    val result = client.execute(
                        activationCode = activationCode,
                        activationRequestId = material.installId,
                        installId = material.installId,
                        installSecret = secret
                    )
                ) {
                    is LicenseClientTransportResult.Signed -> {
                        val pending =
                            ProductionAndroidPendingActivationEnvelopeStore.create(context)
                        if (!pending.save(result.envelope)) {
                            return ProductionAndroidActivationResult.Failed
                        }
                        when (installPendingConfiguration(profile, pending)) {
                            PendingInstallResult.Installed ->
                                ProductionAndroidActivationResult.Activated
                            PendingInstallResult.AlreadyConfigured ->
                                ProductionAndroidActivationResult.AlreadyActivated
                            PendingInstallResult.Failed ->
                                ProductionAndroidActivationResult.Failed
                        }
                    }
                    is LicenseClientTransportResult.ServiceRejected ->
                        ProductionAndroidActivationResult.ServiceRejected(result.reason)
                    is LicenseClientTransportResult.Failed ->
                        ProductionAndroidActivationResult.TransportFailed(result.reason)
                }
            } finally {
                secret.fill(0)
            }
        }
    }

    fun restorePendingConfiguration(
        context: Context
    ): ProductionAndroidActivationResult {
        val source = ProductionAndroidFirstRunProductProfileSourceOwner.current()
            ?: return ProductionAndroidActivationResult.ProductProfileRequired
        val profile = try {
            source.load()
        } catch (_: Throwable) {
            return ProductionAndroidActivationResult.Failed
        }
        val pending = ProductionAndroidPendingActivationEnvelopeStore.create(context)
        if (!pending.exists()) return ProductionAndroidActivationResult.Failed
        return when (installPendingConfiguration(profile, pending)) {
            PendingInstallResult.Installed ->
                ProductionAndroidActivationResult.Activated
            PendingInstallResult.AlreadyConfigured ->
                ProductionAndroidActivationResult.AlreadyActivated
            PendingInstallResult.Failed ->
                ProductionAndroidActivationResult.Failed
        }
    }

    private fun installPendingConfiguration(
        profile: ProductionAndroidFirstRunProductProfile,
        pending: ProductionAndroidPendingActivationEnvelopeStore
    ): PendingInstallResult {
        if (ProductionAndroidFirstRunConfigurationOwner.current() != null) {
            return PendingInstallResult.AlreadyConfigured
        }
        val configuration = ProductionAndroidFirstRunConfiguration(
            licenseAcquisition = ProductionAndroidFirstRunLicenseAcquisitionPort {
                try {
                    ProductionAndroidLicenseEnvelopeAcquisitionResult.Signed(
                        pending.openEnvelope()
                    )
                } catch (_: Throwable) {
                    ProductionAndroidLicenseEnvelopeAcquisitionResult.Failed(
                        LicenseClientTransportFailure.INVALID_LOCAL_REQUEST
                    )
                }
            },
            productInput = profile.productInputTemplate.productInputPort()
        )
        return if (ProductionAndroidFirstRunConfigurationOwner.install(configuration)) {
            PendingInstallResult.Installed
        } else {
            PendingInstallResult.AlreadyConfigured
        }
    }

    private enum class PendingInstallResult {
        Installed,
        AlreadyConfigured,
        Failed
    }
}
