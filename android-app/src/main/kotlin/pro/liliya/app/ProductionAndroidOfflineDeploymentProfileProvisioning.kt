package pro.liliya.app

import android.content.Context

internal object ProductionAndroidOfflineDeploymentProfileFactory {
    fun from(
        profile: ProductionAndroidFirstRunProductProfile
    ): ProductionAndroidOfflineDeploymentProfile? =
        from(
            transport = profile.transport,
            productId = profile.licenseRequest.productId.value,
            template = profile.productInputTemplate
        )

    fun from(
        profile: ProductionAndroidActivationProfile
    ): ProductionAndroidOfflineDeploymentProfile? =
        from(
            transport = profile.transport,
            productId = profile.productInputTemplate.admission.productId,
            template = profile.productInputTemplate
        )

    private fun from(
        transport: pro.liliya.core.licensetransport.LicenseHttpTransportConfig,
        productId: String,
        template: ProductionAndroidFirstRunProductInputTemplate
    ): ProductionAndroidOfflineDeploymentProfile? {
        if (transport.developmentAllowInsecureHttp) return null
        return try {
            ProductionAndroidOfflineDeploymentProfile(
                productId = productId,
                endpoint = transport.endpoint.toExternalForm(),
                connectTimeoutMillis = transport.connectTimeoutMillis,
                readTimeoutMillis = transport.readTimeoutMillis,
                tlsCertificates = transport.tlsTrust?.copyCertificates().orEmpty(),
                supportedLicenseSchemaVersion =
                    template.supportedLicenseSchemaVersion,
                licenseTrustKeys = template.licenseTrustKeys.map { key ->
                    ProductionAndroidOfflineDeploymentLicenseTrustKey(
                        keyId = key.keyId,
                        material = key.copyMaterial()
                    )
                },
                offlineResumePolicyId = template.offlineResumePolicyId,
                offlineResumePolicyVersion = template.offlineResumePolicyVersion,
                modelSignerTrustKeys = template.offlineResumeModelSignerTrustKeys.map { key ->
                    ProductionAndroidOfflineDeploymentModelSignerTrustKey(
                        signerId = key.signerId,
                        material = key.copyMaterial()
                    )
                },
                semanticDirectoryName = template.semanticDirectoryName,
                cognitiveStorageDirectoryName = template.cognitiveStorageDirectoryName
            )
        } catch (_: Throwable) {
            null
        }
    }
}

internal fun interface ProductionAndroidOfflineDeploymentProfileCommitPort {
    fun commit(profile: ProductionAndroidOfflineDeploymentProfile): Boolean
}

internal class ProductionAndroidDurableFirstRunProductProfileSource private constructor(
    private val delegate: ProductionAndroidFirstRunProductProfileSource,
    private val commitPort: ProductionAndroidOfflineDeploymentProfileCommitPort
) : ProductionAndroidFirstRunProductProfileSource {
    constructor(
        context: Context,
        delegate: ProductionAndroidFirstRunProductProfileSource
    ) : this(
        delegate = delegate,
        commitPort = ProductionAndroidOfflineDeploymentProfileCommitPort { profile ->
            when (
                ProductionAndroidOfflineDeploymentProfileEncryptedStore
                    .create(context.applicationContext)
                    .store(profile)
            ) {
                ProductionAndroidOfflineDeploymentProfileStoreResult.Stored -> true
                ProductionAndroidOfflineDeploymentProfileStoreResult.Rejected,
                ProductionAndroidOfflineDeploymentProfileStoreResult.Failed -> false
            }
        }
    )

    internal constructor(
        delegate: ProductionAndroidFirstRunProductProfileSource,
        commit: (ProductionAndroidOfflineDeploymentProfile) -> Boolean
    ) : this(
        delegate = delegate,
        commitPort = ProductionAndroidOfflineDeploymentProfileCommitPort(commit)
    )

    override fun load(): ProductionAndroidFirstRunProductProfile {
        val profile = delegate.load()
        val durable = ProductionAndroidOfflineDeploymentProfileFactory.from(profile)
            ?: throw IllegalStateException("offline deployment profile rejected")
        if (!commitPort.commit(durable)) {
            throw IllegalStateException("offline deployment profile commit failed")
        }
        return profile
    }
}


internal class ProductionAndroidDurableActivationProfileSource private constructor(
    private val delegate: ProductionAndroidActivationProfileSource,
    private val commitPort: ProductionAndroidOfflineDeploymentProfileCommitPort
) : ProductionAndroidActivationProfileSource {
    constructor(
        context: Context,
        delegate: ProductionAndroidActivationProfileSource
    ) : this(
        delegate = delegate,
        commitPort = ProductionAndroidOfflineDeploymentProfileCommitPort { profile ->
            when (
                ProductionAndroidOfflineDeploymentProfileEncryptedStore
                    .create(context.applicationContext)
                    .store(profile)
            ) {
                ProductionAndroidOfflineDeploymentProfileStoreResult.Stored -> true
                ProductionAndroidOfflineDeploymentProfileStoreResult.Rejected,
                ProductionAndroidOfflineDeploymentProfileStoreResult.Failed -> false
            }
        }
    )

    internal constructor(
        delegate: ProductionAndroidActivationProfileSource,
        commit: (ProductionAndroidOfflineDeploymentProfile) -> Boolean
    ) : this(
        delegate = delegate,
        commitPort = ProductionAndroidOfflineDeploymentProfileCommitPort(commit)
    )

    override fun load(): ProductionAndroidActivationProfile {
        val profile = delegate.load()
        val durable = ProductionAndroidOfflineDeploymentProfileFactory.from(profile)
            ?: throw IllegalStateException("offline activation deployment profile rejected")
        if (!commitPort.commit(durable)) {
            throw IllegalStateException("offline activation deployment profile commit failed")
        }
        return profile
    }
}
