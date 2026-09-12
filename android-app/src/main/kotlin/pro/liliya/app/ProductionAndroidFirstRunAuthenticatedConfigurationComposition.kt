package pro.liliya.app

import pro.liliya.core.licensetransport.LicenseHttpTransportClient
import pro.liliya.core.licensetransport.LicenseServiceTransportRequest

/**
 * Explicit outer-product inputs for composing one authenticated first-run configuration.
 *
 * Composition Input != Credential Storage.
 * Composition Input != License Verification Trust.
 * Composition Input != Authority Policy.
 * Composition Input != Model Selection.
 *
 * Every dependency remains caller-owned. In particular, bearer material is not created or retained
 * while the configuration is composed; the credential factory is invoked only by an acquisition
 * attempt and the accepted acquisition boundary closes that attempt credential before returning.
 */
internal data class ProductionAndroidFirstRunAuthenticatedConfigurationInput(
    val licenseClient: LicenseHttpTransportClient,
    val licenseRequest: LicenseServiceTransportRequest,
    val bearerCredentialFactory: ProductionAndroidLicenseBearerCredentialFactory,
    val productInput: ProductionAndroidFirstRunProductInputPort
)

/**
 * Pure composition boundary for the accepted authenticated license-acquisition path.
 *
 * Configuration Composition != Network Execution.
 * Configuration Composition != License Issuance or Verification.
 * Configuration Composition != Trust or Authority Discovery.
 *
 * The resulting process-local configuration contains ports only. It does not contain bearer bytes,
 * execute transport, mint authority, or manufacture policy-sensitive first-run defaults.
 */
internal object ProductionAndroidFirstRunAuthenticatedConfigurationComposition {
    fun create(
        input: ProductionAndroidFirstRunAuthenticatedConfigurationInput
    ): ProductionAndroidFirstRunConfiguration =
        ProductionAndroidFirstRunConfiguration(
            licenseAcquisition = ProductionAndroidFirstRunLicenseAcquisitionPort {
                ProductionAndroidLicenseEnvelopeAcquisition.acquire(
                    client = input.licenseClient,
                    request = input.licenseRequest,
                    authentication = input.bearerCredentialFactory
                )
            },
            productInput = input.productInput
        )
}
