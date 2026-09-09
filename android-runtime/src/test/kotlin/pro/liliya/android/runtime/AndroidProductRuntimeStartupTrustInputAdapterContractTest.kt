package pro.liliya.android.runtime

import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import org.junit.After
import org.junit.Test
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseCanonicalPayload
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicenseSignature
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseVersion

class AndroidProductRuntimeStartupTrustInputAdapterContractTest {
    private val roots = mutableListOf<File>()

    @After
    fun cleanup() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun exact_observability_trust_material_and_envelope_are_connected() {
        val root = Files.createTempDirectory("liliya-trust-input").toFile().also(roots::add)
        val observability = AndroidProductRuntimeObservability.create(root)
        val trustMaterial = assertIs<AndroidProductRuntimeLicenseTrustMaterialResult.Ready>(
            AndroidProductRuntimeLicenseTrustMaterial.create(
                listOf(
                    AndroidProductRuntimeLicenseTrustKey(
                        keyId = "primary",
                        material = byteArrayOf(1, 2, 3)
                    )
                )
            )
        )
        val envelope = envelope()

        val result = AndroidProductRuntimeStartupTrustInputAdapter.create(
            observability = observability,
            trustMaterial = trustMaterial,
            choice = AndroidProductRuntimeStartupTrustChoice(
                supportedSchemaVersion = 1,
                licenseEnvelope = envelope
            )
        )

        val ready = assertIs<AndroidProductRuntimeStartupTrustInputAdapterResult.Ready>(result)
        assertEquals(LicenseVersion(1), ready.input.supportedLicenseSchemaVersion)
        assertSame(envelope, ready.input.licenseEnvelope)
        assertEquals(
            LicenseAlgorithm("ECDSA-P256-SHA256"),
            ready.input.supportedAlgorithms.single()
        )
        val resolved = requireNotNull(ready.input.trustedKeys.resolve(LicenseKeyId("primary")))
        assertEquals(LicenseKeyId("primary"), resolved.keyId)
    }

    @Test
    fun invalid_schema_version_is_rejected_without_substitution() {
        val root = Files.createTempDirectory("liliya-trust-input-invalid").toFile()
            .also(roots::add)
        val observability = AndroidProductRuntimeObservability.create(root)
        val trustMaterial = assertIs<AndroidProductRuntimeLicenseTrustMaterialResult.Ready>(
            AndroidProductRuntimeLicenseTrustMaterial.create(
                listOf(
                    AndroidProductRuntimeLicenseTrustKey(
                        keyId = "primary",
                        material = byteArrayOf(1)
                    )
                )
            )
        )

        val result = AndroidProductRuntimeStartupTrustInputAdapter.create(
            observability = observability,
            trustMaterial = trustMaterial,
            choice = AndroidProductRuntimeStartupTrustChoice(
                supportedSchemaVersion = 0,
                licenseEnvelope = envelope()
            )
        )

        assertIs<AndroidProductRuntimeStartupTrustInputAdapterResult.Rejected>(result)
    }

    private fun envelope(): LicenseSignedEnvelope =
        LicenseSignedEnvelope(
            schemaVersion = LicenseVersion(1),
            algorithm = LicenseAlgorithm("ECDSA-P256-SHA256"),
            signingKeyId = LicenseKeyId("primary"),
            payload = LicenseCanonicalPayload.of(byteArrayOf(4, 5, 6)),
            signature = LicenseSignature.of(byteArrayOf(7, 8, 9))
        )
}
