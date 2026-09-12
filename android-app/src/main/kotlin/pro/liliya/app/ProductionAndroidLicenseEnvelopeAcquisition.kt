package pro.liliya.app

import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.licensetransport.LicenseClientTransportFailure
import pro.liliya.core.licensetransport.LicenseClientTransportResult
import pro.liliya.core.licensetransport.LicenseHttpBearerCredential
import pro.liliya.core.licensetransport.LicenseHttpTransportClient
import pro.liliya.core.licensetransport.LicenseRemoteServiceFailure
import pro.liliya.core.licensetransport.LicenseServiceTransportRequest
import pro.liliya.core.licensetransport.LicenseTransportCancellation

sealed interface ProductionAndroidLicenseEnvelopeAcquisitionResult {
    data class Signed(
        val envelope: LicenseSignedEnvelope
    ) : ProductionAndroidLicenseEnvelopeAcquisitionResult

    data class ServiceRejected(
        val reason: LicenseRemoteServiceFailure
    ) : ProductionAndroidLicenseEnvelopeAcquisitionResult

    data class Failed(
        val reason: LicenseClientTransportFailure
    ) : ProductionAndroidLicenseEnvelopeAcquisitionResult
}

internal fun interface ProductionAndroidLicenseEnvelopeTransportPort {
    fun execute(): LicenseClientTransportResult
}

/**
 * Supplies one caller-provisioned bearer credential for exactly one acquisition attempt.
 *
 * Credential Factory != License Issuance.
 * Credential Factory != License Verification Trust.
 * Credential Factory != Capability Authority.
 *
 * The returned credential is owned by the acquisition attempt and is always closed before the
 * attempt returns. No credential is stored in first-run configuration or licensing wire DTOs.
 */
internal fun interface ProductionAndroidLicenseBearerCredentialFactory {
    fun create(): LicenseHttpBearerCredential
}

/**
 * App-host boundary for acquiring one exact signed license envelope.
 *
 * Envelope Acquisition != License Verification.
 * Envelope Acquisition != Retry Policy.
 * Envelope Acquisition != Trusted Key Discovery.
 * Envelope Acquisition != Entitlement Authority.
 */
object ProductionAndroidLicenseEnvelopeAcquisition {
    fun acquire(
        client: LicenseHttpTransportClient,
        request: LicenseServiceTransportRequest,
        cancellation: LicenseTransportCancellation = LicenseTransportCancellation()
    ): ProductionAndroidLicenseEnvelopeAcquisitionResult =
        acquire(
            ProductionAndroidLicenseEnvelopeTransportPort {
                client.execute(request, cancellation)
            }
        )

    internal fun acquire(
        client: LicenseHttpTransportClient,
        request: LicenseServiceTransportRequest,
        authentication: ProductionAndroidLicenseBearerCredentialFactory,
        cancellation: LicenseTransportCancellation = LicenseTransportCancellation()
    ): ProductionAndroidLicenseEnvelopeAcquisitionResult {
        val credential = try {
            authentication.create()
        } catch (_: Exception) {
            return ProductionAndroidLicenseEnvelopeAcquisitionResult.Failed(
                LicenseClientTransportFailure.INVALID_LOCAL_REQUEST
            )
        }

        return try {
            acquire(
                ProductionAndroidLicenseEnvelopeTransportPort {
                    client.execute(request, credential, cancellation)
                }
            )
        } finally {
            credential.close()
        }
    }

    internal fun acquire(
        transport: ProductionAndroidLicenseEnvelopeTransportPort
    ): ProductionAndroidLicenseEnvelopeAcquisitionResult {
        val result = try {
            transport.execute()
        } catch (_: Exception) {
            return ProductionAndroidLicenseEnvelopeAcquisitionResult.Failed(
                LicenseClientTransportFailure.PROTOCOL_FAILURE
            )
        }

        return when (result) {
            is LicenseClientTransportResult.Signed ->
                ProductionAndroidLicenseEnvelopeAcquisitionResult.Signed(result.envelope)
            is LicenseClientTransportResult.ServiceRejected ->
                ProductionAndroidLicenseEnvelopeAcquisitionResult.ServiceRejected(result.reason)
            is LicenseClientTransportResult.Failed ->
                ProductionAndroidLicenseEnvelopeAcquisitionResult.Failed(result.reason)
        }
    }
}
