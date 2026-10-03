package pro.liliya.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import pro.liliya.core.license.LicenseServiceDurableProtectorFailure
import pro.liliya.core.license.LicenseServiceDurableProtectorInitializationResult
import pro.liliya.core.license.LicenseServiceDurableProtectorOpenResult
import pro.liliya.core.license.LicenseServiceDurableProtectorSealResult
import pro.liliya.core.license.LicenseServiceDurableStateAssociatedDataEncoder
import pro.liliya.core.license.LicenseServiceDurableStateBinding
import pro.liliya.core.license.LicenseServiceDurableStateEnvelope
import pro.liliya.core.license.LicenseServiceDurableStatePayload
import pro.liliya.core.license.LicenseServiceDurableStateProtector
import pro.liliya.core.license.LicenseServiceDurableStateProtectorGeneration
import pro.liliya.core.license.LicenseServiceDurableStateProtectorId
import pro.liliya.core.license.LicenseServiceDurableStateProtectorReference
import pro.liliya.core.license.LicenseServiceDurableStoreId

/**
 * Dedicated Android Keystore protector for durable Licensing Service security state.
 *
 * This key is intentionally separate from Device Key, Product Auth and protected-model keys.
 */
internal class ProductionAndroidLicenseServiceDurableProtector private constructor(
    private val storeId: LicenseServiceDurableStoreId,
    private val alias: String,
    private val reference: LicenseServiceDurableStateProtectorReference
) : LicenseServiceDurableStateProtector {

    override fun prepareInitialization(
        storeId: LicenseServiceDurableStoreId
    ): LicenseServiceDurableProtectorInitializationResult {
        if (storeId != this.storeId) {
            return LicenseServiceDurableProtectorInitializationResult.Rejected(
                LicenseServiceDurableProtectorFailure.STALE_PROTECTOR_OWNERSHIP
            )
        }

        return try {
            val store = keyStore()
            if (store.containsAlias(alias)) {
                LicenseServiceDurableProtectorInitializationResult.Existing(reference)
            } else {
                val generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                generator.init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
                generator.generateKey()
                LicenseServiceDurableProtectorInitializationResult.Fresh(reference)
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            LicenseServiceDurableProtectorInitializationResult.Rejected(
                LicenseServiceDurableProtectorFailure.PROTECTOR_INVALIDATED
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
        ownershipFailure(binding)?.let {
            return LicenseServiceDurableProtectorSealResult.Rejected(it)
        }

        val key = loadKey()
            ?: return LicenseServiceDurableProtectorSealResult.Rejected(
                LicenseServiceDurableProtectorFailure.PROTECTOR_MISSING
            )
        val plaintext = payload.copyBytes()
        val aad = LicenseServiceDurableStateAssociatedDataEncoder.encode(binding)
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad)
            val nonce = cipher.iv
            if (nonce.size != binding.profile.nonceSizeBytes) {
                return LicenseServiceDurableProtectorSealResult.Rejected(
                    LicenseServiceDurableProtectorFailure.FAILED
                )
            }
            val sealed = cipher.doFinal(plaintext)
            val tagBytes = binding.profile.authenticationTagSizeBits / 8
            if (sealed.size <= tagBytes) {
                sealed.fill(0)
                return LicenseServiceDurableProtectorSealResult.Rejected(
                    LicenseServiceDurableProtectorFailure.FAILED
                )
            }
            val ciphertext = sealed.copyOfRange(0, sealed.size - tagBytes)
            val tag = sealed.copyOfRange(sealed.size - tagBytes, sealed.size)
            sealed.fill(0)
            try {
                LicenseServiceDurableProtectorSealResult.Sealed(
                    LicenseServiceDurableStateEnvelope(
                        binding = binding,
                        nonce = nonce,
                        ciphertext = ciphertext,
                        authenticationTag = tag
                    )
                )
            } finally {
                nonce.fill(0)
                ciphertext.fill(0)
                tag.fill(0)
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            LicenseServiceDurableProtectorSealResult.Rejected(
                LicenseServiceDurableProtectorFailure.PROTECTOR_INVALIDATED
            )
        } catch (_: Throwable) {
            LicenseServiceDurableProtectorSealResult.Rejected(
                LicenseServiceDurableProtectorFailure.FAILED
            )
        } finally {
            plaintext.fill(0)
            aad.fill(0)
        }
    }

    override fun open(
        envelope: LicenseServiceDurableStateEnvelope
    ): LicenseServiceDurableProtectorOpenResult {
        ownershipFailure(envelope.binding)?.let {
            return LicenseServiceDurableProtectorOpenResult.Rejected(it)
        }

        val key = loadKey()
            ?: return LicenseServiceDurableProtectorOpenResult.Rejected(
                LicenseServiceDurableProtectorFailure.PROTECTOR_MISSING
            )
        val nonce = envelope.copyNonce()
        val ciphertext = envelope.copyCiphertext()
        val tag = envelope.copyAuthenticationTag()
        val aad = LicenseServiceDurableStateAssociatedDataEncoder.encode(envelope.binding)
        val sealed = ByteArray(ciphertext.size + tag.size)
        ciphertext.copyInto(sealed, 0)
        tag.copyInto(sealed, ciphertext.size)

        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(envelope.binding.profile.authenticationTagSizeBits, nonce)
            )
            cipher.updateAAD(aad)
            val plaintext = cipher.doFinal(sealed)
            try {
                LicenseServiceDurableProtectorOpenResult.Opened(
                    LicenseServiceDurableStatePayload.of(plaintext)
                )
            } finally {
                plaintext.fill(0)
            }
        } catch (_: AEADBadTagException) {
            LicenseServiceDurableProtectorOpenResult.Rejected(
                LicenseServiceDurableProtectorFailure.AUTHENTICATION_FAILED
            )
        } catch (_: KeyPermanentlyInvalidatedException) {
            LicenseServiceDurableProtectorOpenResult.Rejected(
                LicenseServiceDurableProtectorFailure.PROTECTOR_INVALIDATED
            )
        } catch (_: Throwable) {
            LicenseServiceDurableProtectorOpenResult.Rejected(
                LicenseServiceDurableProtectorFailure.FAILED
            )
        } finally {
            nonce.fill(0)
            ciphertext.fill(0)
            tag.fill(0)
            aad.fill(0)
            sealed.fill(0)
        }
    }

    private fun ownershipFailure(
        binding: LicenseServiceDurableStateBinding
    ): LicenseServiceDurableProtectorFailure? =
        if (binding.storeId != storeId || binding.protector != reference) {
            LicenseServiceDurableProtectorFailure.STALE_PROTECTOR_OWNERSHIP
        } else {
            null
        }

    private fun loadKey(): SecretKey? = try {
        keyStore().getKey(alias, null) as? SecretKey
    } catch (_: Throwable) {
        null
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val DEFAULT_ALIAS = "pro.liliya.license-service-state.v1.aes"
        private const val PROTECTOR_ID = "android-keystore-license-service-state-v1"

        fun create(
            context: Context,
            storeId: LicenseServiceDurableStoreId,
            alias: String = DEFAULT_ALIAS
        ): ProductionAndroidLicenseServiceDurableProtector {
            context.applicationContext
            require(alias.isNotBlank()) {
                "license-service durable protector alias must not be blank"
            }
            return ProductionAndroidLicenseServiceDurableProtector(
                storeId = storeId,
                alias = alias,
                reference = LicenseServiceDurableStateProtectorReference(
                    id = LicenseServiceDurableStateProtectorId(PROTECTOR_ID),
                    generation = LicenseServiceDurableStateProtectorGeneration(1)
                )
            )
        }
    }
}
