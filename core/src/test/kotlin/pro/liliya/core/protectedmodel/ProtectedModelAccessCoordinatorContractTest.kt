package pro.liliya.core.protectedmodel

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProtectedModelAccessCoordinatorContractTest {
    @Test
    fun fresh_policy_rejection_blocks_key_resolution_and_publish() {
        val fixture = fixture(ProtectedModelGeneration(1), "model-v1")
        val ownership = ProtectedModelRuntimeOwnership().also { it.replaceTarget(fixture.manifest.model) }
        var keyResolutionCalled = false
        var published = false
        val coordinator = coordinator(
            fixture = fixture,
            ownership = ownership,
            policy = ProtectedModelAccessPolicy {
                ProtectedModelPolicyDecision.Rejected(ProtectedModelPolicyFailure.ENTITLEMENT_REJECTED)
            },
            keyResolver = { _, _ ->
                keyResolutionCalled = true
                fixture.modelKey
            }
        )

        val result = coordinator.openAndPublish(
            fixture.envelope,
            fixture.ciphertext,
            ProtectedModelPlaintextConsumer { _, plaintext -> plaintext.size }
        ) { _, _ -> published = true }

        assertEquals(
            ProtectedModelAccessFailure.POLICY_REJECTED,
            assertIs<ProtectedModelAccessResult.Rejected>(result).reason
        )
        assertFalse(keyResolutionCalled)
        assertFalse(published)
    }

    @Test
    fun policy_is_fresh_for_each_open_attempt() {
        val fixture = fixture(ProtectedModelGeneration(1), "model-v1")
        val ownership = ProtectedModelRuntimeOwnership().also { it.replaceTarget(fixture.manifest.model) }
        var decisions = 0
        val coordinator = coordinator(
            fixture,
            ownership,
            ProtectedModelAccessPolicy {
                decisions += 1
                if (decisions == 1) ProtectedModelPolicyDecision.Allowed
                else ProtectedModelPolicyDecision.Rejected(ProtectedModelPolicyFailure.EVIDENCE_STALE)
            }
        )

        assertIs<ProtectedModelAccessResult.Opened<Int>>(
            coordinator.openAndPublish(
                fixture.envelope,
                fixture.ciphertext,
                ProtectedModelPlaintextConsumer { _, plaintext -> plaintext.size }
            ) { _, _ -> }
        )
        assertEquals(
            ProtectedModelAccessFailure.POLICY_REJECTED,
            assertIs<ProtectedModelAccessResult.Rejected>(
                coordinator.openAndPublish(
                    fixture.envelope,
                    fixture.ciphertext,
                    ProtectedModelPlaintextConsumer { _, plaintext -> plaintext.size }
                ) { _, _ -> }
            ).reason
        )
        assertEquals(2, decisions)
    }

    @Test
    fun stale_worker_cannot_publish_after_new_generation_replaces_target() {
        val old = fixture(ProtectedModelGeneration(1), "model-v1")
        val newer = fixture(ProtectedModelGeneration(2), "model-v2", packageId = old.manifest.model.packageId)
        val ownership = ProtectedModelRuntimeOwnership().also { it.replaceTarget(old.manifest.model) }
        var published = false
        val coordinator = coordinator(old, ownership, ProtectedModelAccessPolicy { ProtectedModelPolicyDecision.Allowed })

        val result = coordinator.openAndPublish(
            old.envelope,
            old.ciphertext,
            ProtectedModelPlaintextConsumer { _, plaintext ->
                ownership.replaceTarget(newer.manifest.model)
                plaintext.size
            }
        ) { _, _ -> published = true }

        assertEquals(
            ProtectedModelAccessFailure.STALE_OWNERSHIP,
            assertIs<ProtectedModelAccessResult.Rejected>(result).reason
        )
        assertFalse(published)
        assertEquals(newer.manifest.model, ownership.currentReference())
    }

    @Test
    fun rotated_generation_opens_only_after_exact_target_replacement() {
        val old = fixture(ProtectedModelGeneration(1), "model-v1")
        val newer = fixture(ProtectedModelGeneration(2), "model-v2", packageId = old.manifest.model.packageId)
        val ownership = ProtectedModelRuntimeOwnership().also { it.replaceTarget(old.manifest.model) }
        val oldCoordinator = coordinator(old, ownership, ProtectedModelAccessPolicy { ProtectedModelPolicyDecision.Allowed })
        val newCoordinator = coordinator(newer, ownership, ProtectedModelAccessPolicy { ProtectedModelPolicyDecision.Allowed })

        assertEquals(
            ProtectedModelAccessFailure.TARGET_MISMATCH,
            assertIs<ProtectedModelAccessResult.Rejected>(
                newCoordinator.openAndPublish(
                    newer.envelope,
                    newer.ciphertext,
                    ProtectedModelPlaintextConsumer { _, plaintext -> plaintext.size }
                ) { _, _ -> }
            ).reason
        )

        ownership.replaceTarget(newer.manifest.model)
        assertIs<ProtectedModelAccessResult.Opened<Int>>(
            newCoordinator.openAndPublish(
                newer.envelope,
                newer.ciphertext,
                ProtectedModelPlaintextConsumer { _, plaintext -> plaintext.size }
            ) { reference, _ -> assertEquals(newer.manifest.model, reference) }
        )

        assertEquals(
            ProtectedModelAccessFailure.TARGET_MISMATCH,
            assertIs<ProtectedModelAccessResult.Rejected>(
                oldCoordinator.openAndPublish(
                    old.envelope,
                    old.ciphertext,
                    ProtectedModelPlaintextConsumer { _, plaintext -> plaintext.size }
                ) { _, _ -> }
            ).reason
        )
    }

    @Test
    fun retired_target_fails_closed_without_implicit_recovery() {
        val fixture = fixture(ProtectedModelGeneration(1), "model-v1")
        val ownership = ProtectedModelRuntimeOwnership().also { it.replaceTarget(fixture.manifest.model) }
        assertTrue(ownership.retire(fixture.manifest.model))
        val coordinator = coordinator(fixture, ownership, ProtectedModelAccessPolicy { ProtectedModelPolicyDecision.Allowed })

        val result = coordinator.openAndPublish(
            fixture.envelope,
            fixture.ciphertext,
            ProtectedModelPlaintextConsumer { _, plaintext -> plaintext.size }
        ) { _, _ -> error("must not publish") }

        assertEquals(
            ProtectedModelAccessFailure.NO_ACTIVE_TARGET,
            assertIs<ProtectedModelAccessResult.Rejected>(result).reason
        )
        assertEquals(null, ownership.currentReference())
    }

    @Test
    fun policy_and_publish_failure_rendering_omits_secret_messages() {
        val policyFailure = ProtectedModelPolicyDecision.Failed(
            ProtectedModelPolicyFailure.PROVIDER_FAILED,
            IllegalStateException("secret-policy-message")
        )
        assertFalse(policyFailure.toString().contains("secret-policy-message"))

        val accessFailure = ProtectedModelAccessResult.Failed(
            ProtectedModelAccessFailure.PUBLISH_FAILED,
            IllegalStateException("secret-publish-message")
        )
        assertFalse(accessFailure.toString().contains("secret-publish-message"))
        assertFalse(accessFailure.toString().contains("IllegalStateException: secret"))
    }

    private fun coordinator(
        fixture: Fixture,
        ownership: ProtectedModelRuntimeOwnership,
        policy: ProtectedModelAccessPolicy,
        keyResolver: (ProtectedModelReference, ModelDekReference) -> SecretKey? = { _, _ -> fixture.modelKey }
    ): ProtectedModelAccessCoordinator {
        val loader = ProtectedModelPayloadLoader(
            verifier = fixture.verifier,
            dekResolver = ProtectedModelDekResolver(keyResolver),
            maxPlaintextSizeBytes = 1024L * 1024L
        )
        return ProtectedModelAccessCoordinator(policy, ownership, loader)
    }

    private fun fixture(
        generation: ProtectedModelGeneration,
        text: String,
        packageId: ProtectedModelPackageId = ProtectedModelPackageId("model-package")
    ): Fixture {
        val plaintext = text.encodeToByteArray()
        val signerKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val modelKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val model = ProtectedModelReference(packageId, generation)
        val dek = ModelDekReference(ModelDekId("model-dek-${generation.value}"), ModelDekGeneration(generation.value))
        val nonce = ByteArray(12) { (it + generation.value.toInt() + 3).toByte() }
        val manifest = ProtectedModelManifest(
            formatVersion = ProtectedModelFormatVersion(1),
            model = model,
            profileId = ProtectedModelProfileId("gguf-q4"),
            plaintextSizeBytes = plaintext.size.toLong(),
            ciphertextSizeBytes = plaintext.size.toLong(),
            modelDek = dek,
            encryptionProfile = ProtectedModelEncryptionProfile.AES_256_GCM,
            signatureAlgorithm = ProtectedModelSignatureAlgorithm.ED25519,
            signerId = ProtectedModelSignerId("release-signer")
        )
        val encrypted = encrypt(manifest, plaintext, modelKey, nonce)
        val digest = MessageDigest.getInstance("SHA-256").digest(encrypted.ciphertext)
        val signature = sign(manifest, digest, nonce, encrypted.tag, signerKeys.private)
        val envelope = ProtectedModelPackageEnvelope(
            manifest,
            digest,
            nonce,
            encrypted.tag,
            signature
        )
        val verifier = ProtectedModelPackageVerifier(
            ProtectedModelSignerResolver { signerId, algorithm ->
                if (signerId == manifest.signerId && algorithm == manifest.signatureAlgorithm) signerKeys.public else null
            }
        )
        return Fixture(manifest, envelope, encrypted.ciphertext, modelKey, verifier)
    }

    private fun encrypt(
        manifest: ProtectedModelManifest,
        plaintext: ByteArray,
        key: SecretKey,
        nonce: ByteArray
    ): Encrypted {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(ProtectedModelManifestCanonicalCodec.encode(manifest))
        val output = cipher.doFinal(plaintext)
        return try {
            Encrypted(
                output.copyOfRange(0, output.size - 16),
                output.copyOfRange(output.size - 16, output.size)
            )
        } finally {
            output.fill(0)
        }
    }

    private fun sign(
        manifest: ProtectedModelManifest,
        digest: ByteArray,
        nonce: ByteArray,
        tag: ByteArray,
        privateKey: PrivateKey
    ): ByteArray {
        val input = ProtectedModelManifestCanonicalCodec.signatureInput(manifest, digest, nonce, tag)
        return try {
            Signature.getInstance("Ed25519").run {
                initSign(privateKey)
                update(input)
                sign()
            }
        } finally {
            input.fill(0)
        }
    }

    private data class Encrypted(val ciphertext: ByteArray, val tag: ByteArray)

    private data class Fixture(
        val manifest: ProtectedModelManifest,
        val envelope: ProtectedModelPackageEnvelope,
        val ciphertext: ByteArray,
        val modelKey: SecretKey,
        val verifier: ProtectedModelPackageVerifier
    )
}
