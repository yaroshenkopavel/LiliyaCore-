package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionAndroidOfflineDeploymentProfileEncryptedStoreInstrumentedTest {
    @Test
    fun profile_round_trip_and_tamper_rejection_are_fail_closed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val store = ProductionAndroidOfflineDeploymentProfileEncryptedStore.create(
            context = context,
            directoryName = "offline-deployment-profile-test-$suffix",
            alias = "pro.liliya.offline-deployment-profile.test.$suffix"
        )

        val expected = ProductionAndroidOfflineDeploymentProfile(
            productId = "liliya-pro",
            endpoint = "https://127.0.0.1:8443/v1/license",
            connectTimeoutMillis = 5_000,
            readTimeoutMillis = 15_000,
            tlsCertificates = listOf(byteArrayOf(1, 2, 3, 4, 5)),
            supportedLicenseSchemaVersion = 1L,
            licenseTrustKeys = listOf(
                ProductionAndroidOfflineDeploymentLicenseTrustKey(
                    keyId = "license-signing-v1",
                    material = byteArrayOf(9, 8, 7, 6)
                )
            ),
            semanticDirectoryName = "semantic-v1",
            cognitiveStorageDirectoryName = "cognitive-v1"
        )

        try {
            assertIs<ProductionAndroidOfflineDeploymentProfileLoadResult.Missing>(store.load())
            assertIs<ProductionAndroidOfflineDeploymentProfileStoreResult.Stored>(
                store.store(expected)
            )

            val loaded = assertIs<ProductionAndroidOfflineDeploymentProfileLoadResult.Loaded>(
                store.load()
            ).profile
            assertEquals(expected.productId, loaded.productId)
            assertEquals(expected.endpoint, loaded.endpoint)
            assertEquals(expected.connectTimeoutMillis, loaded.connectTimeoutMillis)
            assertEquals(expected.readTimeoutMillis, loaded.readTimeoutMillis)
            assertEquals(
                expected.supportedLicenseSchemaVersion,
                loaded.supportedLicenseSchemaVersion
            )
            assertEquals(expected.semanticDirectoryName, loaded.semanticDirectoryName)
            assertEquals(
                expected.cognitiveStorageDirectoryName,
                loaded.cognitiveStorageDirectoryName
            )
            assertEquals(
                expected.copyTlsCertificates().single().toList(),
                loaded.copyTlsCertificates().single().toList()
            )
            val expectedKey = expected.copyLicenseTrustKeys().single()
            val loadedKey = loaded.copyLicenseTrustKeys().single()
            assertEquals(expectedKey.keyId, loadedKey.keyId)
            assertEquals(
                expectedKey.copyMaterial().toList(),
                loadedKey.copyMaterial().toList()
            )

            val file = store.publishedFileForTest()
            val bytes = file.readBytes()
            assertTrue(bytes.size > 24)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
            file.writeBytes(bytes)
            bytes.fill(0)

            assertIs<ProductionAndroidOfflineDeploymentProfileLoadResult.Rejected>(
                store.load()
            )
        } finally {
            store.deleteForTests()
        }

        assertIs<ProductionAndroidOfflineDeploymentProfileLoadResult.Missing>(store.load())
    }
}
