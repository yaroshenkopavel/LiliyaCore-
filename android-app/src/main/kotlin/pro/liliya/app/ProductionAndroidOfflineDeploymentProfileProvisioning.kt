package pro.liliya.app

import android.content.Context

internal object ProductionAndroidOfflineDeploymentProfileFactory {
    fun from(
        profile: ProductionAndroidFirstRunProductProfile
    ): ProductionAndroidOfflineDeploymentProfile? {
        if (profile.transport.developmentAllowInsecureHttp) return null
        return try {
            ProductionAndroidOfflineDeploymentProfile(
                productId = profile.licenseRequest.productId.value,
                endpoint = profile.transport.endpoint.toExternalForm(),
                connectTimeoutMillis = profile.transport.connectTimeoutMillis,
                readTimeoutMillis = profile.transport.readTimeoutMillis,
                tlsCertificates = profile.transport.tlsTrust?.copyCertificates().orEmpty(),
                supportedLicenseSchemaVersion =
                    profile.productInputTemplate.supportedLicenseSchemaVersion,
                licenseTrustKeys = profile.productInputTemplate.licenseTrustKeys.map { key ->
                    ProductionAndroidOfflineDeploymentLicenseTrustKey(
                        keyId = key.keyId,
                        material = key.copyMaterial()
                    )
                },
                semanticDirectoryName =
                    profile.productInputTemplate.semanticDirectoryName,
                cognitiveStorageDirectoryName =
                    profile.productInputTemplate.cognitiveStorageDirectoryName
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
