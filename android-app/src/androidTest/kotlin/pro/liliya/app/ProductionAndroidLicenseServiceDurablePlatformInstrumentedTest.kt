package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.license.LicenseServiceDurableBackendCommitResult
import pro.liliya.core.license.LicenseServiceDurableBackendLoadResult
import pro.liliya.core.license.LicenseServiceDurableExpectedRevision
import pro.liliya.core.license.LicenseServiceDurableProtectorInitializationResult
import pro.liliya.core.license.LicenseServiceDurableProtectorOpenResult
import pro.liliya.core.license.LicenseServiceDurableProtectorSealResult
import pro.liliya.core.license.LicenseServiceDurableStateBinding
import pro.liliya.core.license.LicenseServiceDurableStateEncryptionProfile
import pro.liliya.core.license.LicenseServiceDurableStateEnvelopeCanonicalCodec
import pro.liliya.core.license.LicenseServiceDurableStateEnvelopeDecodeResult
import pro.liliya.core.license.LicenseServiceDurableStateEnvelopeEncodeResult
import pro.liliya.core.license.LicenseServiceDurableStateEnvelopeVersion
import pro.liliya.core.license.LicenseServiceDurableStateGeneration
import pro.liliya.core.license.LicenseServiceDurableStatePayload
import pro.liliya.core.license.LicenseServiceDurableStatePurpose
import pro.liliya.core.license.LicenseServiceDurableStateSchemaVersion
import pro.liliya.core.license.LicenseServiceDurableStoreId
import pro.liliya.core.license.LicenseServiceDurableBackendRevision

@RunWith(AndroidJUnit4::class)
class ProductionAndroidLicenseServiceDurablePlatformInstrumentedTest {
    @Test
    fun keystore_protector_and_atomic_backend_round_trip_and_detect_missing_record() {
        val context = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext
        val alias = "pro.liliya.test.license-service-state." + System.nanoTime()
        val root = File(context.noBackupFilesDir, DIRECTORY)
        root.deleteRecursively()
        deleteAlias(alias)

        try {
            val storeId = LicenseServiceDurableStoreId("physical-license-service-state-test")
            val backend = ProductionAndroidLicenseServiceDurableBackend.create(context)
            val protector = ProductionAndroidLicenseServiceDurableProtector.create(
                context = context,
                storeId = storeId,
                alias = alias
            )

            val initialized = assertIs<LicenseServiceDurableProtectorInitializationResult.Fresh>(
                protector.prepareInitialization(storeId)
            )
            val binding = LicenseServiceDurableStateBinding(
                version = LicenseServiceDurableStateEnvelopeVersion(1),
                stateSchemaVersion = LicenseServiceDurableStateSchemaVersion(1),
                purpose = LicenseServiceDurableStatePurpose.LICENSE_SERVICE_SECURITY_STATE,
                profile = LicenseServiceDurableStateEncryptionProfile.AES_256_GCM,
                storeId = storeId,
                generation = LicenseServiceDurableStateGeneration(1),
                backendRevision = LicenseServiceDurableBackendRevision(1),
                protector = initialized.reference
            )
            val plaintext = "license-service-durable-platform-test".encodeToByteArray()
            val sealed = assertIs<LicenseServiceDurableProtectorSealResult.Sealed>(
                protector.seal(
                    binding = binding,
                    payload = LicenseServiceDurableStatePayload.of(plaintext)
                )
            )
            val encoded = assertIs<LicenseServiceDurableStateEnvelopeEncodeResult.Encoded>(
                LicenseServiceDurableStateEnvelopeCanonicalCodec.encode(sealed.envelope)
            )

            val committed = assertIs<LicenseServiceDurableBackendCommitResult.Committed>(
                backend.commit(
                    expectedRevision = LicenseServiceDurableExpectedRevision(0),
                    envelope = encoded.payload
                )
            )
            assertEquals(1L, committed.revision.value)

            val loaded = assertIs<LicenseServiceDurableBackendLoadResult.Loaded>(backend.load())
            assertEquals(1L, loaded.revision.value)
            val loadedEnvelope = assertIs<LicenseServiceDurableStateEnvelopeDecodeResult.Decoded>(
                LicenseServiceDurableStateEnvelopeCanonicalCodec.decode(loaded.envelope)
            ).envelope
            val opened = assertIs<LicenseServiceDurableProtectorOpenResult.Opened>(
                protector.open(loadedEnvelope)
            )
            val reopened = opened.payload.copyBytes()
            try {
                assertContentEquals(plaintext, reopened)
            } finally {
                reopened.fill(0)
                plaintext.fill(0)
            }

            assertIs<LicenseServiceDurableBackendCommitResult.Conflict>(
                backend.commit(
                    expectedRevision = LicenseServiceDurableExpectedRevision(0),
                    envelope = encoded.payload
                )
            )

            assertTrue(root.deleteRecursively())
            val backendAfterDeletion = ProductionAndroidLicenseServiceDurableBackend.create(context)
            assertIs<LicenseServiceDurableBackendLoadResult.Missing>(backendAfterDeletion.load())
            assertIs<LicenseServiceDurableProtectorInitializationResult.Existing>(
                protector.prepareInitialization(storeId)
            )

            println(
                "LILIYA_LICENSE_SERVICE_DURABLE_PLATFORM=" +
                    "{\"keystoreAesGcm\":true,\"atomicCas\":true," +
                    "\"roundTrip\":true,\"revisionConflict\":true," +
                    "\"existingKeyMissingRecordDetected\":true}"
            )
        } finally {
            root.deleteRecursively()
            deleteAlias(alias)
        }
    }

    private fun deleteAlias(alias: String) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    private companion object {
        const val DIRECTORY = "liliya-license-service-state-v1"
    }
}
