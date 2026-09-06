package pro.liliya.android.licensestate

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import pro.liliya.core.license.LicenseServiceDurableProtectorFailure
import pro.liliya.core.license.LicenseServiceDurableProtectorInitializationResult
import pro.liliya.core.license.LicenseServiceDurableProtectorOpenResult
import pro.liliya.core.license.LicenseServiceDurableProtectorSealResult
import pro.liliya.core.license.LicenseServiceDurableStateAssociatedDataEncoder
import pro.liliya.core.license.LicenseServiceDurableStateBinding
import pro.liliya.core.license.LicenseServiceDurableStateEncryptionProfile
import pro.liliya.core.license.LicenseServiceDurableStateEnvelope
import pro.liliya.core.license.LicenseServiceDurableStatePayload
import pro.liliya.core.license.LicenseServiceDurableStateProtector
import pro.liliya.core.license.LicenseServiceDurableStateProtectorGeneration
import pro.liliya.core.license.LicenseServiceDurableStateProtectorId
import pro.liliya.core.license.LicenseServiceDurableStateProtectorReference
import pro.liliya.core.license.LicenseServiceDurableStoreId

/**
 * Dedicated non-exportable Android Keystore AES-256-GCM protector for Licensing Service state.
 *
 * This is intentionally not Device Key and not the Cognitive DEK protector. V0.1 owns one fixed
 * protector generation per logical licensing store and performs no implicit rotation or recovery.
 */
enum class AndroidLicenseServiceDurableProtectorSecurityRequirement(
    internal val code: String
) {
    ANDROID_KEYSTORE("keystore"),
    STRONGBOX("strongbox")
}

class AndroidLicenseServiceDurableStateProtector(
    private val securityRequirement: AndroidLicenseServiceDurableProtectorSecurityRequirement =
        AndroidLicenseServiceDurableProtectorSecurityRequirement.ANDROID_KEYSTORE
) : LicenseServiceDurableStateProtector {

    override fun prepareInitialization(
        storeId: LicenseServiceDurableStoreId
    ): LicenseServiceDurableProtectorInitializationResult {
        val reference = referenceFor(storeId)
        val alias = aliasFor(storeId)
        if (
            securityRequirement == AndroidLicenseServiceDurableProtectorSecurityRequirement.STRONGBOX &&
            Build.VERSION.SDK_INT < 31
        ) {
            return LicenseServiceDurableProtectorInitializationResult.Rejected(
                LicenseServiceDurableProtectorFailure.REQUIRED_SECURITY_LEVEL_UNAVAILABLE
            )
        }
        return try {
            val store = keyStore()
            if (store.containsAlias(alias)) {
                val key = loadKey(storeId)
                    ?: return LicenseServiceDurableProtectorInitializationResult.Rejected(
                        LicenseServiceDurableProtectorFailure.PROTECTOR_MISSING
                    )
                if (!meetsSecurityRequirement(key)) {
                    return LicenseServiceDurableProtectorInitializationResult.Rejected(
                        LicenseServiceDurableProtectorFailure.REQUIRED_SECURITY_LEVEL_UNAVAILABLE
                    )
                }
                LicenseServiceDurableProtectorInitializationResult.Existing(reference)
            } else {
                val generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val builder = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)

                if (
                    securityRequirement ==
                    AndroidLicenseServiceDurableProtectorSecurityRequirement.STRONGBOX
                ) {
                    builder.setIsStrongBoxBacked(true)
                }

                generator.init(builder.build())
                val key = generator.generateKey()
                if (!meetsSecurityRequirement(key)) {
                    try {
                        store.deleteEntry(alias)
                    } catch (_: Throwable) {
                        // Do not downgrade or reuse a key whose requested security level is unproven.
                    }
                    LicenseServiceDurableProtectorInitializationResult.Rejected(
                        LicenseServiceDurableProtectorFailure.REQUIRED_SECURITY_LEVEL_UNAVAILABLE
                    )
                } else {
                    LicenseServiceDurableProtectorInitializationResult.Fresh(reference)
                }
            }
        } catch (_: StrongBoxUnavailableException) {
            LicenseServiceDurableProtectorInitializationResult.Rejected(
                LicenseServiceDurableProtectorFailure.REQUIRED_SECURITY_LEVEL_UNAVAILABLE
            )
        } catch (_: Throwable) {
            LicenseServiceDurableProtectorInitializationResult.Rejected(
                LicenseServiceDurableProtectorFailure.FAILED
            )
        }
    }

    override fun seal(
        binding: LicenseServiceDurableStateBinding,
        payload: LicenseServiceDurableStatePayload
    ): LicenseServiceDurableProtectorSealResult {
        if (
            binding.profile != LicenseServiceDurableStateEncryptionProfile.AES_256_GCM ||
            binding.protector != referenceFor(binding.storeId)
        ) {
            return rejectedSeal(LicenseServiceDurableProtectorFailure.STALE_PROTECTOR_OWNERSHIP)
        }

        val key = loadKey(binding.storeId)
            ?: return rejectedSeal(LicenseServiceDurableProtectorFailure.PROTECTOR_MISSING)
        if (!meetsSecurityRequirement(key)) {
            return rejectedSeal(
                LicenseServiceDurableProtectorFailure.REQUIRED_SECURITY_LEVEL_UNAVAILABLE
            )
        }
        val plaintext = payload.copyBytes()
        val aad = LicenseServiceDurableStateAssociatedDataEncoder.encode(binding)
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val nonce = cipher.iv
            if (nonce.size != binding.profile.nonceSizeBytes) {
                return rejectedSeal(LicenseServiceDurableProtectorFailure.FAILED)
            }
            cipher.updateAAD(aad)
            val output = cipher.doFinal(plaintext)
            val tagBytes = binding.profile.authenticationTagSizeBits / 8
            val ciphertextBytes = output.size - tagBytes
            if (ciphertextBytes <= 0) {
                return rejectedSeal(LicenseServiceDurableProtectorFailure.FAILED)
            }
            LicenseServiceDurableProtectorSealResult.Sealed(
                LicenseServiceDurableStateEnvelope(
                    binding = binding,
                    nonce = nonce,
                    ciphertext = output.copyOfRange(0, ciphertextBytes),
                    authenticationTag = output.copyOfRange(ciphertextBytes, output.size)
                )
            )
        } catch (_: KeyPermanentlyInvalidatedException) {
            rejectedSeal(LicenseServiceDurableProtectorFailure.PROTECTOR_INVALIDATED)
        } catch (_: InvalidKeyException) {
            rejectedSeal(LicenseServiceDurableProtectorFailure.PROTECTOR_INVALIDATED)
        } catch (_: Throwable) {
            rejectedSeal(LicenseServiceDurableProtectorFailure.FAILED)
        } finally {
            plaintext.fill(0)
            aad.fill(0)
        }
    }

    override fun open(
        envelope: LicenseServiceDurableStateEnvelope
    ): LicenseServiceDurableProtectorOpenResult {
        val binding = envelope.binding
        if (
            binding.profile != LicenseServiceDurableStateEncryptionProfile.AES_256_GCM ||
            binding.protector != referenceFor(binding.storeId)
        ) {
            return rejectedOpen(LicenseServiceDurableProtectorFailure.STALE_PROTECTOR_OWNERSHIP)
        }

        val key = loadKey(binding.storeId)
            ?: return rejectedOpen(LicenseServiceDurableProtectorFailure.PROTECTOR_MISSING)
        if (!meetsSecurityRequirement(key)) {
            return rejectedOpen(
                LicenseServiceDurableProtectorFailure.REQUIRED_SECURITY_LEVEL_UNAVAILABLE
            )
        }
        val nonce = envelope.copyNonce()
        val ciphertext = envelope.copyCiphertext()
        val tag = envelope.copyAuthenticationTag()
        val input = ciphertext + tag
        val aad = LicenseServiceDurableStateAssociatedDataEncoder.encode(binding)
        ciphertext.fill(0)
        tag.fill(0)
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(binding.profile.authenticationTagSizeBits, nonce)
            )
            cipher.updateAAD(aad)
            val plaintext = cipher.doFinal(input)
            try {
                LicenseServiceDurableProtectorOpenResult.Opened(
                    LicenseServiceDurableStatePayload.of(plaintext)
                )
            } finally {
                plaintext.fill(0)
            }
        } catch (_: AEADBadTagException) {
            rejectedOpen(LicenseServiceDurableProtectorFailure.AUTHENTICATION_FAILED)
        } catch (_: KeyPermanentlyInvalidatedException) {
            rejectedOpen(LicenseServiceDurableProtectorFailure.PROTECTOR_INVALIDATED)
        } catch (_: InvalidKeyException) {
            rejectedOpen(LicenseServiceDurableProtectorFailure.PROTECTOR_INVALIDATED)
        } catch (_: Throwable) {
            rejectedOpen(LicenseServiceDurableProtectorFailure.FAILED)
        } finally {
            nonce.fill(0)
            input.fill(0)
            aad.fill(0)
        }
    }

    internal fun deleteForTest(storeId: LicenseServiceDurableStoreId) {
        val store = keyStore()
        val alias = aliasFor(storeId)
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun loadKey(storeId: LicenseServiceDurableStoreId): SecretKey? = try {
        val entry = keyStore().getEntry(aliasFor(storeId), null)
        (entry as? KeyStore.SecretKeyEntry)?.secretKey
    } catch (_: Throwable) {
        null
    }

    private fun referenceFor(
        storeId: LicenseServiceDurableStoreId
    ): LicenseServiceDurableStateProtectorReference =
        LicenseServiceDurableStateProtectorReference(
            id = LicenseServiceDurableStateProtectorId(referenceId(storeId)),
            generation = LicenseServiceDurableStateProtectorGeneration(1)
        )

    private fun referenceId(storeId: LicenseServiceDurableStoreId): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(
                ("license-state:" + securityRequirement.code + ":" + storeId.value)
                    .toByteArray(Charsets.UTF_8)
            )
        return "sha256:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun aliasFor(storeId: LicenseServiceDurableStoreId): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(storeId.value.toByteArray(Charsets.UTF_8))
        return ALIAS_PREFIX + securityRequirement.code + "." +
            Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun meetsSecurityRequirement(key: SecretKey): Boolean {
        if (
            securityRequirement ==
            AndroidLicenseServiceDurableProtectorSecurityRequirement.ANDROID_KEYSTORE
        ) {
            return true
        }
        if (Build.VERSION.SDK_INT < 31) return false
        return try {
            val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        } catch (_: Throwable) {
            false
        }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun rejectedSeal(
        reason: LicenseServiceDurableProtectorFailure
    ): LicenseServiceDurableProtectorSealResult =
        LicenseServiceDurableProtectorSealResult.Rejected(reason)

    private fun rejectedOpen(
        reason: LicenseServiceDurableProtectorFailure
    ): LicenseServiceDurableProtectorOpenResult =
        LicenseServiceDurableProtectorOpenResult.Rejected(reason)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CIPHER = "AES/GCM/NoPadding"
        const val ALIAS_PREFIX = "liliya.licensestate.v1."
    }
}
