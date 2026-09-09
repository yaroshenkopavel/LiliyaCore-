package pro.liliya.app

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseCanonicalPayload
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicenseSignature
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseVersion
import pro.liliya.core.licensetransport.LicenseClientTransportFailure
import pro.liliya.core.licensetransport.LicenseClientTransportResult
import pro.liliya.core.licensetransport.LicenseRemoteServiceFailure

class ProductionAndroidLicenseEnvelopeAcquisitionContractTest {
    @Test
    fun signed_envelope_is_preserved_exactly() {
        val envelope = LicenseSignedEnvelope(
            schemaVersion = LicenseVersion(1),
            algorithm = LicenseAlgorithm("ECDSA-P256-SHA256"),
            signingKeyId = LicenseKeyId("primary"),
            payload = LicenseCanonicalPayload.of(byteArrayOf(1, 2, 3)),
            signature = LicenseSignature.of(byteArrayOf(4, 5, 6))
        )

        val result = ProductionAndroidLicenseEnvelopeAcquisition.acquire(
            ProductionAndroidLicenseEnvelopeTransportPort {
                LicenseClientTransportResult.Signed(envelope)
            }
        )

        val signed = assertIs<ProductionAndroidLicenseEnvelopeAcquisitionResult.Signed>(result)
        assertEquals(envelope, signed.envelope)
    }

    @Test
    fun service_rejection_is_preserved() {
        val result = ProductionAndroidLicenseEnvelopeAcquisition.acquire(
            ProductionAndroidLicenseEnvelopeTransportPort {
                LicenseClientTransportResult.ServiceRejected(
                    LicenseRemoteServiceFailure.ENROLLMENT_REQUIRED
                )
            }
        )

        val rejected =
            assertIs<ProductionAndroidLicenseEnvelopeAcquisitionResult.ServiceRejected>(result)
        assertEquals(LicenseRemoteServiceFailure.ENROLLMENT_REQUIRED, rejected.reason)
    }

    @Test
    fun transport_failure_is_preserved() {
        val result = ProductionAndroidLicenseEnvelopeAcquisition.acquire(
            ProductionAndroidLicenseEnvelopeTransportPort {
                LicenseClientTransportResult.Failed(
                    LicenseClientTransportFailure.TLS_FAILURE
                )
            }
        )

        val failed = assertIs<ProductionAndroidLicenseEnvelopeAcquisitionResult.Failed>(result)
        assertEquals(LicenseClientTransportFailure.TLS_FAILURE, failed.reason)
    }

    @Test
    fun unexpected_transport_exception_is_bounded() {
        val result = ProductionAndroidLicenseEnvelopeAcquisition.acquire(
            ProductionAndroidLicenseEnvelopeTransportPort {
                error("private transport failure")
            }
        )

        val failed = assertIs<ProductionAndroidLicenseEnvelopeAcquisitionResult.Failed>(result)
        assertEquals(LicenseClientTransportFailure.PROTOCOL_FAILURE, failed.reason)
    }
}
