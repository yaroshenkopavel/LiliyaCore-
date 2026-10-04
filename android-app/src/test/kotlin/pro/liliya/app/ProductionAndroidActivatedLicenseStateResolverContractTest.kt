package pro.liliya.app

import kotlin.test.assertEquals
import org.junit.Test
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseCanonicalPayload
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicenseSignature
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseVersion

class ProductionAndroidActivatedLicenseStateResolverContractTest {
    @Test
    fun missing_available_and_rejected_are_classified_without_minting_runtime_state() {
        assertEquals(
            ProductionAndroidActivatedLicenseState.MISSING,
            ProductionAndroidActivatedLicenseStateResolver.resolve {
                ProductionAndroidActivatedLicenseLoadResult.Missing
            }
        )
        assertEquals(
            ProductionAndroidActivatedLicenseState.AVAILABLE,
            ProductionAndroidActivatedLicenseStateResolver.resolve {
                ProductionAndroidActivatedLicenseLoadResult.Loaded(envelope())
            }
        )
        assertEquals(
            ProductionAndroidActivatedLicenseState.REJECTED,
            ProductionAndroidActivatedLicenseStateResolver.resolve {
                ProductionAndroidActivatedLicenseLoadResult.Rejected
            }
        )
    }

    private fun envelope() = LicenseSignedEnvelope(
        schemaVersion = LicenseVersion(1),
        algorithm = LicenseAlgorithm("ECDSA-P256-SHA256"),
        signingKeyId = LicenseKeyId("cold-resume-test-key"),
        payload = LicenseCanonicalPayload.of(byteArrayOf(1)),
        signature = LicenseSignature.of(byteArrayOf(2))
    )
}
