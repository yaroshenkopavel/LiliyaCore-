package pro.liliya.android.licensestate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.core.license.LicenseServiceDurableBackendCommitResult
import pro.liliya.core.license.LicenseServiceDurableBackendLoadResult
import pro.liliya.core.license.LicenseServiceDurableExpectedRevision
import pro.liliya.core.license.LicenseServiceDurableProtectorFailure
import pro.liliya.core.license.LicenseServiceDurableProtectorInitializationResult
import pro.liliya.core.license.LicenseServiceDurableProtectorOpenResult
import pro.liliya.core.license.LicenseServiceDurableProtectorSealResult
import pro.liliya.core.license.LicenseServiceDurableStateBinding
import pro.liliya.core.license.LicenseServiceDurableStateEncryptionProfile
import pro.liliya.core.license.LicenseServiceDurableStateEnvelope
import pro.liliya.core.license.LicenseServiceDurableStateEnvelopeCanonicalCodec
import pro.liliya.core.license.LicenseServiceDurableStateEnvelopeEncodeResult
import pro.liliya.core.license.LicenseServiceDurableStateEnvelopePayload
import pro.liliya.core.license.LicenseServiceDurableStateEnvelopeVersion
import pro.liliya.core.license.LicenseServiceDurableStateGeneration
import pro.liliya.core.license.LicenseServiceDurableStatePayload
import pro.liliya.core.license.LicenseServiceDurableStateProtectorGeneration
import pro.liliya.core.license.LicenseServiceDurableStateProtectorReference
import pro.liliya.core.license.LicenseServiceDurableStatePurpose
import pro.liliya.core.license.LicenseServiceDurableStateSchemaVersion
import pro.liliya.core.license.LicenseServiceDurableStoreId
import pro.liliya.core.license.LicenseServiceDurableBackendRevision

@RunWith(AndroidJUnit4::class)
class AndroidLicenseServiceDurableStateInstrumentedTest {

    @Test
    fun dedicated_keystore_protector_is_nonexported_exact_and_authenticates_tamper() {
        val storeId = LicenseServiceDurableStoreId("license-test-protector")
        val protector = AndroidLicenseServiceDurableStateProtector()
        protector.deleteForTest(storeId)

        try {
            val fresh = assertIs<LicenseServiceDurableProtectorInitializationResult.Fresh>(
                protector.prepareInitialization(storeId)
            )
            assertIs<LicenseServiceDurableProtectorInitializationResult.Existing>(
                protector.prepareInitialization(storeId)
            )

            val binding = binding(storeId, fresh.reference, revision = 1L)
            val plaintext = "retained-license-security-minima".encodeToByteArray()
            val sealed = assertIs<LicenseServiceDurableProtectorSealResult.Sealed>(
                protector.seal(binding, LicenseServiceDurableStatePayload.of(plaintext))
            )
            val opened = assertIs<LicenseServiceDurableProtectorOpenResult.Opened>(
                protector.open(sealed.envelope)
            )
            assertContentEquals(plaintext, opened.payload.copyBytes())

            val corruptedCiphertext = sealed.envelope.copyCiphertext()
            corruptedCiphertext[0] = (corruptedCiphertext[0].toInt() xor 1).toByte()
            val tampered = LicenseServiceDurableStateEnvelope(
                binding = sealed.envelope.binding,
                nonce = sealed.envelope.copyNonce(),
                ciphertext = corruptedCiphertext,
                authenticationTag = sealed.envelope.copyAuthenticationTag()
            )
            val rejected = assertIs<LicenseServiceDurableProtectorOpenResult.Rejected>(
                protector.open(tampered)
            )
            assertEquals(
                LicenseServiceDurableProtectorFailure.AUTHENTICATION_FAILED,
                rejected.reason
            )

            val staleReference = LicenseServiceDurableStateProtectorReference(
                id = fresh.reference.id,
                generation = LicenseServiceDurableStateProtectorGeneration(2)
            )
            val stale = assertIs<LicenseServiceDurableProtectorSealResult.Rejected>(
                protector.seal(
                    binding(storeId, staleReference, revision = 2L),
                    LicenseServiceDurableStatePayload.of(plaintext)
                )
            )
            assertEquals(
                LicenseServiceDurableProtectorFailure.STALE_PROTECTOR_OWNERSHIP,
                stale.reason
            )

            protector.deleteForTest(storeId)
            val missing = assertIs<LicenseServiceDurableProtectorOpenResult.Rejected>(
                protector.open(sealed.envelope)
            )
            assertEquals(LicenseServiceDurableProtectorFailure.PROTECTOR_MISSING, missing.reason)
        } finally {
            protector.deleteForTest(storeId)
        }
    }

    @Test
    fun backend_reopens_exact_revision_rejects_stale_cas_and_detects_corruption() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val directory = "license-state-backend-reopen-test"
        val root = File(context.filesDir, directory)
        root.deleteRecursively()
        val storeId = LicenseServiceDurableStoreId("license-test-backend")
        val protector = AndroidLicenseServiceDurableStateProtector()
        protector.deleteForTest(storeId)

        try {
            val reference = assertIs<LicenseServiceDurableProtectorInitializationResult.Fresh>(
                protector.prepareInitialization(storeId)
            ).reference
            val payload = sealedPayload(protector, storeId, reference, revision = 1L)

            val first = AndroidLicenseServiceDurableBackend.create(context, storeId, directory)
            assertIs<LicenseServiceDurableBackendLoadResult.Missing>(first.load())
            val committed = assertIs<LicenseServiceDurableBackendCommitResult.Committed>(
                first.commit(LicenseServiceDurableExpectedRevision(0), payload)
            )
            assertEquals(1L, committed.revision.value)

            val reopened = AndroidLicenseServiceDurableBackend.create(context, storeId, directory)
            val loaded = assertIs<LicenseServiceDurableBackendLoadResult.Loaded>(reopened.load())
            assertEquals(1L, loaded.revision.value)
            assertEquals(payload, loaded.envelope)

            assertIs<LicenseServiceDurableBackendCommitResult.Conflict>(
                reopened.commit(LicenseServiceDurableExpectedRevision(0), payload)
            )

            val bytes = reopened.publishedFileForTest().readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            reopened.publishedFileForTest().writeBytes(bytes)
            assertIs<LicenseServiceDurableBackendLoadResult.Corrupt>(reopened.load())
        } finally {
            root.deleteRecursively()
            protector.deleteForTest(storeId)
        }
    }

    @Test
    fun two_backend_instances_share_process_cas_boundary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val directory = "license-state-backend-race-test"
        val root = File(context.filesDir, directory)
        root.deleteRecursively()
        val storeId = LicenseServiceDurableStoreId("license-test-race")
        val protector = AndroidLicenseServiceDurableStateProtector()
        protector.deleteForTest(storeId)

        try {
            val reference = assertIs<LicenseServiceDurableProtectorInitializationResult.Fresh>(
                protector.prepareInitialization(storeId)
            ).reference
            val payload = sealedPayload(protector, storeId, reference, revision = 1L)
            val first = AndroidLicenseServiceDurableBackend.create(context, storeId, directory)
            val second = AndroidLicenseServiceDurableBackend.create(context, storeId, directory)

            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val results = java.util.Collections.synchronizedList(
                mutableListOf<LicenseServiceDurableBackendCommitResult>()
            )
            listOf(first, second).forEach { backend ->
                Thread {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    results += backend.commit(LicenseServiceDurableExpectedRevision(0), payload)
                    done.countDown()
                }.start()
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(1, results.count { it is LicenseServiceDurableBackendCommitResult.Committed })
            assertEquals(1, results.count { it is LicenseServiceDurableBackendCommitResult.Conflict })
        } finally {
            root.deleteRecursively()
            protector.deleteForTest(storeId)
        }
    }

    @Test
    fun protector_without_backend_is_observable_as_uncertain_initialization_prerequisite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val directory = "license-state-uncertain-init-test"
        File(context.filesDir, directory).deleteRecursively()
        val storeId = LicenseServiceDurableStoreId("license-test-uncertain")
        val protector = AndroidLicenseServiceDurableStateProtector()
        protector.deleteForTest(storeId)

        try {
            assertIs<LicenseServiceDurableProtectorInitializationResult.Fresh>(
                protector.prepareInitialization(storeId)
            )
            val restartedProtector = AndroidLicenseServiceDurableStateProtector()
            assertIs<LicenseServiceDurableProtectorInitializationResult.Existing>(
                restartedProtector.prepareInitialization(storeId)
            )
            val backend = AndroidLicenseServiceDurableBackend.create(context, storeId, directory)
            assertIs<LicenseServiceDurableBackendLoadResult.Missing>(backend.load())
        } finally {
            File(context.filesDir, directory).deleteRecursively()
            protector.deleteForTest(storeId)
        }
    }

    private fun sealedPayload(
        protector: AndroidLicenseServiceDurableStateProtector,
        storeId: LicenseServiceDurableStoreId,
        reference: LicenseServiceDurableStateProtectorReference,
        revision: Long
    ): LicenseServiceDurableStateEnvelopePayload {
        val sealed = assertIs<LicenseServiceDurableProtectorSealResult.Sealed>(
            protector.seal(
                binding(storeId, reference, revision),
                LicenseServiceDurableStatePayload.of("state-$revision".encodeToByteArray())
            )
        )
        return assertIs<LicenseServiceDurableStateEnvelopeEncodeResult.Encoded>(
            LicenseServiceDurableStateEnvelopeCanonicalCodec.encode(sealed.envelope)
        ).payload
    }

    private fun binding(
        storeId: LicenseServiceDurableStoreId,
        reference: LicenseServiceDurableStateProtectorReference,
        revision: Long
    ): LicenseServiceDurableStateBinding =
        LicenseServiceDurableStateBinding(
            version = LicenseServiceDurableStateEnvelopeVersion(1),
            stateSchemaVersion = LicenseServiceDurableStateSchemaVersion(1),
            purpose = LicenseServiceDurableStatePurpose.LICENSE_SERVICE_SECURITY_STATE,
            profile = LicenseServiceDurableStateEncryptionProfile.AES_256_GCM,
            storeId = storeId,
            generation = LicenseServiceDurableStateGeneration(revision),
            backendRevision = LicenseServiceDurableBackendRevision(revision),
            protector = reference
        )
}
