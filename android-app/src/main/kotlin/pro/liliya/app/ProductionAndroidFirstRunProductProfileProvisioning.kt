package pro.liliya.app

import android.content.Context
import pro.liliya.core.licensetransport.LicenseHttpTransportClient
import pro.liliya.core.licensetransport.LicenseHttpTransportConfig
import pro.liliya.core.licensetransport.LicenseServiceTransportRequest

/**
 * Explicit non-secret product profile used to compose the authenticated first-run boundary.
 *
 * Product Profile != Product Auth Secret.
 * Product Profile != License Entitlement.
 * Product Profile != Trust Discovery.
 * Product Profile != Authority Minting.
 *
 * Every field is caller-approved. No endpoint, request identity, trust key or policy-sensitive
 * runtime input is discovered or defaulted by this layer.
 */
internal data class ProductionAndroidFirstRunProductProfile(
    val transport: LicenseHttpTransportConfig,
    val licenseRequest: LicenseServiceTransportRequest,
    val productInputTemplate: ProductionAndroidFirstRunProductInputTemplate
)

internal fun interface ProductionAndroidFirstRunProductProfileSource {
    fun load(): ProductionAndroidFirstRunProductProfile
}

/**
 * Bridges one explicit product profile to the already accepted authenticated install boundary.
 *
 * The production entry point obtains request-authentication material only from the dedicated
 * encrypted Product Auth store. The adapter still opens that secret only when an acquisition
 * attempt requests a bearer credential. This method itself performs no HTTP request and exposes
 * no plaintext credential bytes.
 */
internal object ProductionAndroidFirstRunProductProfileProvisioning {
    fun prepareAndInstall(
        context: Context,
        source: ProductionAndroidFirstRunProductProfileSource
    ): ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult =
        prepareAndInstall(
            source = source,
            credentialSource = ProductionAndroidProductAuthEncryptedStore.create(context),
            clientFactory = ::LicenseHttpTransportClient,
            install = ProductionAndroidFirstRunAuthenticatedProductConfigurationInstall::prepareAndInstall
        )

    internal fun prepareAndInstall(
        source: ProductionAndroidFirstRunProductProfileSource,
        credentialSource: ProductionAndroidProductAuthCredentialSource,
        clientFactory: (LicenseHttpTransportConfig) -> LicenseHttpTransportClient,
        install: (
            ProductionAndroidFirstRunAuthenticatedProductConfigurationInput
        ) -> ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult
    ): ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult {
        val profile = try {
            source.load()
        } catch (_: Throwable) {
            return ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult.Failed
        }

        val client = try {
            clientFactory(profile.transport)
        } catch (_: Throwable) {
            return ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult.Failed
        }

        val bearerFactory = ProductionAndroidProductAuthCredentialAdapter.bearerFactory(
            credentialSource
        )

        return try {
            install(
                ProductionAndroidFirstRunAuthenticatedProductConfigurationInput(
                    licenseClient = client,
                    licenseRequest = profile.licenseRequest,
                    bearerCredentialFactory = bearerFactory,
                    productInputTemplate = profile.productInputTemplate
                )
            )
        } catch (_: Throwable) {
            ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult.Failed
        }
    }
}

/** Application-owned install entry point for one explicit caller-approved product profile. */
internal fun LiliyaApplication.configureAuthenticatedFirstRunProduct(
    source: ProductionAndroidFirstRunProductProfileSource
): ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult =
    ProductionAndroidFirstRunProductProfileProvisioning.prepareAndInstall(
        context = this,
        source = source
    )

