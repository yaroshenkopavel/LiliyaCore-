package pro.liliya.app

import pro.liliya.core.licensetransport.LicenseHttpTransportClient
import pro.liliya.core.licensetransport.LicenseServiceTransportRequest

/**
 * Exact outer-product inputs required to install the accepted authenticated first-run configuration.
 *
 * Install Input != Credential Storage.
 * Install Input != License Trust Discovery.
 * Install Input != Authority Minting.
 * Install Input != Model Discovery/Selection.
 *
 * The product-input template owns only caller-approved static product inputs. The bearer factory is
 * preserved as a per-attempt owner and is not invoked while configuration is installed.
 */
internal data class ProductionAndroidFirstRunAuthenticatedProductConfigurationInput(
    val licenseClient: LicenseHttpTransportClient,
    val licenseRequest: LicenseServiceTransportRequest,
    val bearerCredentialFactory: ProductionAndroidLicenseBearerCredentialFactory,
    val productInputTemplate: ProductionAndroidFirstRunProductInputTemplate
)

internal sealed interface ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult {
    data object Installed : ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult
    data object AlreadyConfigured : ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult
    data object Failed : ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult
}

internal fun interface ProductionAndroidFirstRunAuthenticatedProductConfigurationCompositionPort {
    fun create(): ProductionAndroidFirstRunConfiguration
}

/**
 * Atomically composes and installs the explicit authenticated first-run product configuration.
 *
 * Configuration Install != Network Execution.
 * Configuration Install != License Issuance/Verification.
 * Configuration Install != Bearer Acquisition.
 * Configuration Install != Application Auto-Configuration.
 *
 * Construction remains inert. Real license transport and bearer creation happen only when the
 * already accepted first-run acquisition path is invoked later.
 */
internal object ProductionAndroidFirstRunAuthenticatedProductConfigurationInstall {
    fun prepareAndInstall(
        input: ProductionAndroidFirstRunAuthenticatedProductConfigurationInput
    ): ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult =
        prepareAndInstall(
            ProductionAndroidFirstRunAuthenticatedProductConfigurationCompositionPort {
                ProductionAndroidFirstRunAuthenticatedConfigurationComposition.create(
                    ProductionAndroidFirstRunAuthenticatedConfigurationInput(
                        licenseClient = input.licenseClient,
                        licenseRequest = input.licenseRequest,
                        bearerCredentialFactory = input.bearerCredentialFactory,
                        productInput = input.productInputTemplate.productInputPort()
                    )
                )
            }
        )

    @Synchronized
    internal fun prepareAndInstall(
        composition: ProductionAndroidFirstRunAuthenticatedProductConfigurationCompositionPort
    ): ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult {
        if (ProductionAndroidFirstRunConfigurationOwner.current() != null) {
            return ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult.AlreadyConfigured
        }

        val configuration = try {
            composition.create()
        } catch (_: Exception) {
            return ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult.Failed
        }

        return if (ProductionAndroidFirstRunConfigurationOwner.install(configuration)) {
            ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult.Installed
        } else {
            ProductionAndroidFirstRunAuthenticatedProductConfigurationInstallResult.AlreadyConfigured
        }
    }
}
