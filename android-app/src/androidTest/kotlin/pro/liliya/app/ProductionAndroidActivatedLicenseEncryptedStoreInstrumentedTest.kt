package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseCanonicalPayload
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicenseSignature
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseVersion

@RunWith(AndroidJUnit4::class)
class ProductionAndroidActivatedLicenseEncryptedStoreInstrumentedTest {
    @Test
    fun encrypted_store_round_trips_replaces_and_rejects_tamper() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alias =
            "pro.liliya.activated-license.test." + System.nanoTime().toString()
        val store = ProductionAndroidActivatedLicenseEncryptedStore.create(
            context = context,
            alias = alias
        )
        store.deleteForTests()

        try {
            assertIs<ProductionAndroidActivatedLicenseLoadResult.Missing>(store.load())

            val first = envelope(
                keyId = "license-signing-test-a",
                payload = "payload-a",
                signatureByte = 0x21
            )
            assertIs<ProductionAndroidActivatedLicenseStoreResult.Stored>(
                store.store(first)
            )
            assertEnvelopeEquals(
                first,
                assertIs<ProductionAndroidActivatedLicenseLoadResult.Loaded>(
                    store.load()
                ).envelope
            )

            val second = envelope(
                keyId = "license-signing-test-b",
                payload = "payload-b",
                signatureByte = 0x42
            )
            assertIs<ProductionAndroidActivatedLicenseStoreResult.Stored>(
                store.store(second)
            )
            assertEnvelopeEquals(
                second,
                assertIs<ProductionAndroidActivatedLicenseLoadResult.Loaded>(
                    store.load()
                ).envelope
            )

            val published = store.publishedFileForTest()
            val bytes = published.readBytes()
            try {
                assertTrue(bytes.size > 32)
                val index = bytes.lastIndex
                bytes[index] = (bytes[index].toInt() xor 0x01).toByte()
                published.writeBytes(bytes)
            } finally {
                bytes.fill(0)
            }

            assertIs<ProductionAndroidActivatedLicenseLoadResult.Rejected>(
                store.load()
            )
        } finally {
            store.deleteForTests()
        }
    }

    private fun envelope(
        keyId: String,
        payload: String,
        signatureByte: Int
    ): LicenseSignedEnvelope =
        LicenseSignedEnvelope(
            schemaVersion = LicenseVersion(1L),
            algorithm = LicenseAlgorithm("ECDSA_P256_SHA256"),
            signingKeyId = LicenseKeyId(keyId),
            payload = LicenseCanonicalPayload.of(
                payload.toByteArray(StandardCharsets.UTF_8)
            ),
            signature = LicenseSignature.of(
                ByteArray(64) { signatureByte.toByte() }
            )
        )

    private fun assertEnvelopeEquals(
        expected: LicenseSignedEnvelope,
        actual: LicenseSignedEnvelope
    ) {
        assertEquals(expected.schemaVersion, actual.schemaVersion)
        assertEquals(expected.algorithm, actual.algorithm)
        assertEquals(expected.signingKeyId, actual.signingKeyId)
        assertTrue(
            expected.payload.copyBytes()
                .contentEquals(actual.payload.copyBytes())
        )
        assertTrue(
            expected.signature.copyBytes()
                .contentEquals(actual.signature.copyBytes())
        )
    }
}
