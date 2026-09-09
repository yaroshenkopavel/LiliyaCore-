package pro.liliya.android.runtime

import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseCanonicalPayload
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicenseSignature
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseVersion

class AndroidProductRuntimeStartupTrustInputFactoryContractTest {
    @Test
    fun exact_acquired_envelope_keys_and_observability_are_preserved() {
        val root = Files.createTempDirectory("liliya-trust-input").toFile()
        try {
            val observability = AndroidProductRuntimeObservability.create(root)
            val envelope = envelope("primary")
            val material = byteArrayOf(1, 2, 3, 4)

            val result = AndroidProductRuntimeStartupTrustInputFactory.create(
                observability = observability,
                supportedLicenseSchemaVersion = 1L,
                keys = listOf(
                    AndroidProductRuntimeLicenseTrustKey(
                        keyId = "primary",
                        material = material
                    )
                ),
                licenseEnvelope = envelope
            )

            val ready = assertIs<AndroidProductRuntimeStartupTrustInputFactoryResult.Ready>(result)
            assertTrue(ready.input.diagnostics === observability.diagnostics)
            assertTrue(ready.input.loggerProvider === observability.loggerProvider)
            assertEquals(LicenseVersion(1), ready.input.supportedLicenseSchemaVersion)
            assertTrue(ready.input.licenseEnvelope === envelope)

            val trusted = requireNotNull(
                ready.input.trustedKeys.resolve(LicenseKeyId("primary"))
            )
            assertEquals(
                LicenseAlgorithm("ECDSA-P256-SHA256"),
                trusted.algorithm
            )
            assertContentEquals(material, trusted.copyMaterial())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun empty_trust_material_is_rejected() {
        val root = Files.createTempDirectory("liliya-trust-input-empty").toFile()
        try {
            val result = AndroidProductRuntimeStartupTrustInputFactory.create(
                observability = AndroidProductRuntimeObservability.create(root),
                supportedLicenseSchemaVersion = 1L,
                keys = emptyList(),
                licenseEnvelope = envelope("primary")
            )

            assertIs<AndroidProductRuntimeStartupTrustInputFactoryResult.Rejected>(result)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalid_schema_version_is_rejected_without_substitution() {
        val root = Files.createTempDirectory("liliya-trust-input-version").toFile()
        try {
            val result = AndroidProductRuntimeStartupTrustInputFactory.create(
                observability = AndroidProductRuntimeObservability.create(root),
                supportedLicenseSchemaVersion = 0L,
                keys = listOf(
                    AndroidProductRuntimeLicenseTrustKey(
                        "primary",
                        byteArrayOf(1)
                    )
                ),
                licenseEnvelope = envelope("primary")
            )

            assertIs<AndroidProductRuntimeStartupTrustInputFactoryResult.Rejected>(result)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun envelope(keyId: String): LicenseSignedEnvelope =
        LicenseSignedEnvelope(
            schemaVersion = LicenseVersion(1),
            algorithm = LicenseAlgorithm("ECDSA-P256-SHA256"),
            signingKeyId = LicenseKeyId(keyId),
            payload = LicenseCanonicalPayload.of(byteArrayOf(9, 8, 7)),
            signature = LicenseSignature.of(byteArrayOf(6, 5, 4))
        )
}
