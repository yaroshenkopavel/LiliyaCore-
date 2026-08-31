package pro.liliya.android.devicekey

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import pro.liliya.core.encryption.CognitiveDekMaterial
import pro.liliya.core.encryption.CognitiveDekReference
import pro.liliya.core.encryption.CognitiveDekWrappingAlgorithm
import pro.liliya.core.encryption.CognitiveEncryptionFailureCategory
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitiveEnvelopeVersion
import pro.liliya.core.encryption.CognitiveKeyProtector
import pro.liliya.core.encryption.CognitiveKeyProtectorCreationRequest
import pro.liliya.core.encryption.CognitiveKeyProtectorDescriptor
import pro.liliya.core.encryption.CognitiveKeyProtectorPlatformReference
import pro.liliya.core.encryption.CognitiveKeyProtectorReference
import pro.liliya.core.encryption.CognitiveKeyProtectorSecurityLevel
import pro.liliya.core.encryption.CognitiveKeyPurpose
import pro.liliya.core.encryption.WrappedCognitiveDekEnvelope

/** Dedicated Android Keystore AES protector for cognitive DEKs. It is separate from Device Key. */
class AndroidCognitiveKeyProtector(context: Context) : CognitiveKeyProtector {
    private val preferences = context.applicationContext.getSharedPreferences(
        METADATA_PREFERENCES,
        Context.MODE_PRIVATE
    )
    private val secureRandom = SecureRandom()

    override fun create(
        request: CognitiveKeyProtectorCreationRequest
    ): CognitiveEncryptionResult<CognitiveKeyProtectorDescriptor> {
        val alias = aliasFor(request.id.value, request.generation.value)
        val metadataKey = platformReferenceKey(alias)
        var generated = false
        return try {
            val keyStore = keyStore()
            if (keyStore.containsAlias(alias) || preferences.contains(metadataKey)) {
                return CognitiveEncryptionResult.Rejected(
                    CognitiveEncryptionFailureCategory.STALE_PROTECTOR_OWNERSHIP
                )
            }

            val builder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)

            if (request.requestedSecurityLevel == CognitiveKeyProtectorSecurityLevel.STRONGBOX) {
                builder.setIsStrongBoxBacked(true)
            }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(builder.build())
            generator.generateKey()
            generated = true

            val platformReference = randomPlatformReference()
            if (!preferences.edit().putString(metadataKey, platformReference.value).commit()) {
                return cleanupAndReject(alias, metadataKey, CognitiveEncryptionFailureCategory.CLEANUP_FAILED)
            }

            val reference = CognitiveKeyProtectorReference(
                id = request.id,
                generation = request.generation,
                platformReference = platformReference
            )
            when (val inspected = inspect(reference)) {
                is CognitiveEncryptionResult.Success -> {
                    if (meetsRequestedSecurity(
                            request.requestedSecurityLevel,
                            inspected.value.securityLevel
                        )) {
                        inspected
                    } else {
                        cleanupAndReject(
                            alias,
                            metadataKey,
                            CognitiveEncryptionFailureCategory.REQUIRED_SECURITY_LEVEL_UNAVAILABLE
                        )
                    }
                }
                is CognitiveEncryptionResult.Rejected -> {
                    cleanupExact(alias, metadataKey)
                    inspected
                }
                is CognitiveEncryptionResult.Failed -> {
                    cleanupExact(alias, metadataKey)
                    inspected
                }
            }
        } catch (_: StrongBoxUnavailableException) {
            CognitiveEncryptionResult.Rejected(
                CognitiveEncryptionFailureCategory.REQUIRED_SECURITY_LEVEL_UNAVAILABLE
            )
        } catch (_: Throwable) {
            if (generated) cleanupAndReject(
                alias,
                metadataKey,
                CognitiveEncryptionFailureCategory.PROVIDER_FAILED
            ) else CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.PROVIDER_FAILED)
        }
    }

    override fun inspect(
        reference: CognitiveKeyProtectorReference
    ): CognitiveEncryptionResult<CognitiveKeyProtectorDescriptor> {
        val platformReference = reference.platformReference
            ?: return CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.INVALID_REQUEST)
        val alias = aliasFor(reference.id.value, reference.generation.value)
        val metadataKey = platformReferenceKey(alias)
        return try {
            val keyStore = keyStore()
            if (!keyStore.containsAlias(alias)) {
                return CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.PROTECTOR_MISSING)
            }
            val storedReference = preferences.getString(metadataKey, null)
                ?: return CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.MALFORMED_ENVELOPE)
            if (storedReference != platformReference.value) {
                return CognitiveEncryptionResult.Rejected(
                    CognitiveEncryptionFailureCategory.STALE_PROTECTOR_OWNERSHIP
                )
            }
            val key = keyStore.getKey(alias, null) as? SecretKey
                ?: return CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.PROTECTOR_MISSING)
            val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (info.keySize != KEY_SIZE_BITS ||
                info.purposes and KeyProperties.PURPOSE_ENCRYPT == 0 ||
                info.purposes and KeyProperties.PURPOSE_DECRYPT == 0 ||
                KeyProperties.BLOCK_MODE_GCM !in info.blockModes) {
                return CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.MALFORMED_ENVELOPE)
            }
            CognitiveEncryptionResult.Success(
                CognitiveKeyProtectorDescriptor(
                    reference = reference,
                    securityLevel = securityLevel(info),
                    purpose = CognitiveKeyPurpose.COGNITIVE_STORAGE
                )
            )
        } catch (_: KeyPermanentlyInvalidatedException) {
            CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.PROTECTOR_INVALIDATED)
        } catch (_: UserNotAuthenticatedException) {
            CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.UNWRAP_REJECTED)
        } catch (_: Throwable) {
            CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.PROVIDER_FAILED)
        }
    }

    override fun wrap(
        expected: CognitiveKeyProtectorDescriptor,
        dek: CognitiveDekReference,
        material: CognitiveDekMaterial
    ): CognitiveEncryptionResult<WrappedCognitiveDekEnvelope> {
        val current = exact(expected) ?: return CognitiveEncryptionResult.Rejected(
            CognitiveEncryptionFailureCategory.STALE_PROTECTOR_OWNERSHIP
        )
        return try {
            val key = keyFor(current.reference)
                ?: return CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.PROTECTOR_MISSING)
            val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(wrappingAssociatedData(dek, current.reference))
            val rawMaterial = material.copyBytes()
            val output = try {
                cipher.doFinal(rawMaterial)
            } finally {
                rawMaterial.fill(0)
            }
            val ciphertextSize = output.size - TAG_BYTES
            if (ciphertextSize <= 0) {
                return CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.WRAP_FAILED)
            }
            CognitiveEncryptionResult.Success(
                WrappedCognitiveDekEnvelope(
                    version = CognitiveEnvelopeVersion(1),
                    dek = dek,
                    protector = current.reference,
                    wrappingAlgorithm = CognitiveDekWrappingAlgorithm.AES_256_GCM,
                    purpose = CognitiveKeyPurpose.COGNITIVE_STORAGE,
                    wrappedDek = output.copyOfRange(0, ciphertextSize),
                    nonce = nonce,
                    authenticationTag = output.copyOfRange(ciphertextSize, output.size)
                )
            )
        } catch (_: Throwable) {
            CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.WRAP_FAILED)
        }
    }

    override fun unwrap(
        expected: CognitiveKeyProtectorDescriptor,
        envelope: WrappedCognitiveDekEnvelope
    ): CognitiveEncryptionResult<CognitiveDekMaterial> {
        val current = exact(expected) ?: return CognitiveEncryptionResult.Rejected(
            CognitiveEncryptionFailureCategory.STALE_PROTECTOR_OWNERSHIP
        )
        if (envelope.protector != current.reference ||
            envelope.purpose != CognitiveKeyPurpose.COGNITIVE_STORAGE ||
            envelope.wrappingAlgorithm != CognitiveDekWrappingAlgorithm.AES_256_GCM) {
            return CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.UNWRAP_REJECTED)
        }
        return try {
            val key = keyFor(current.reference)
                ?: return CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.PROTECTOR_MISSING)
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, envelope.copyNonce()))
            cipher.updateAAD(wrappingAssociatedData(envelope.dek, current.reference))
            val input = envelope.copyWrappedDek() + envelope.copyAuthenticationTag()
            val plaintext = try {
                cipher.doFinal(input)
            } finally {
                input.fill(0)
            }
            try {
                CognitiveEncryptionResult.Success(CognitiveDekMaterial(plaintext))
            } finally {
                plaintext.fill(0)
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.PROTECTOR_INVALIDATED)
        } catch (_: UserNotAuthenticatedException) {
            CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.UNWRAP_REJECTED)
        } catch (_: Throwable) {
            CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.UNWRAP_FAILED)
        }
    }

    override fun retire(
        expected: CognitiveKeyProtectorDescriptor
    ): CognitiveEncryptionResult<Unit> {
        val current = exact(expected) ?: return CognitiveEncryptionResult.Rejected(
            CognitiveEncryptionFailureCategory.STALE_PROTECTOR_OWNERSHIP
        )
        val alias = aliasFor(current.reference.id.value, current.reference.generation.value)
        val metadataKey = platformReferenceKey(alias)
        return try {
            val keyStore = keyStore()
            keyStore.deleteEntry(alias)
            if (!preferences.edit().remove(metadataKey).commit()) {
                CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.CLEANUP_FAILED)
            } else {
                CognitiveEncryptionResult.Success(Unit)
            }
        } catch (_: Throwable) {
            CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.CLEANUP_FAILED)
        }
    }

    internal fun aliasForTesting(reference: CognitiveKeyProtectorReference): String =
        aliasFor(reference.id.value, reference.generation.value)

    private fun exact(expected: CognitiveKeyProtectorDescriptor): CognitiveKeyProtectorDescriptor? =
        when (val inspected = inspect(expected.reference)) {
            is CognitiveEncryptionResult.Success -> inspected.value.takeIf { it == expected }
            else -> null
        }

    private fun keyFor(reference: CognitiveKeyProtectorReference): SecretKey? {
        val alias = aliasFor(reference.id.value, reference.generation.value)
        return keyStore().getKey(alias, null) as? SecretKey
    }

    private fun meetsRequestedSecurity(
        requested: CognitiveKeyProtectorSecurityLevel,
        actual: CognitiveKeyProtectorSecurityLevel
    ): Boolean = when (requested) {
        CognitiveKeyProtectorSecurityLevel.STRONGBOX -> actual == CognitiveKeyProtectorSecurityLevel.STRONGBOX
        CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT ->
            actual == CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT ||
                actual == CognitiveKeyProtectorSecurityLevel.STRONGBOX
        CognitiveKeyProtectorSecurityLevel.SOFTWARE -> actual != CognitiveKeyProtectorSecurityLevel.UNKNOWN
        CognitiveKeyProtectorSecurityLevel.UNKNOWN -> false
    }

    private fun cleanupAndReject(
        alias: String,
        metadataKey: String,
        category: CognitiveEncryptionFailureCategory
    ): CognitiveEncryptionResult<Nothing> = if (cleanupExact(alias, metadataKey)) {
        CognitiveEncryptionResult.Rejected(category)
    } else {
        CognitiveEncryptionResult.Rejected(CognitiveEncryptionFailureCategory.CLEANUP_FAILED)
    }

    private fun cleanupExact(alias: String, metadataKey: String): Boolean = try {
        val keyStore = keyStore()
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        preferences.edit().remove(metadataKey).commit()
    } catch (_: Throwable) {
        false
    }

    private fun wrappingAssociatedData(
        dek: CognitiveDekReference,
        protector: CognitiveKeyProtectorReference
    ): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(1)
            writeString(out, dek.id.value)
            out.writeLong(dek.generation.value)
            writeString(out, protector.id.value)
            out.writeLong(protector.generation.value)
            writeString(out, requireNotNull(protector.platformReference).value)
            writeString(out, CognitiveDekWrappingAlgorithm.AES_256_GCM.name)
            writeString(out, CognitiveKeyPurpose.COGNITIVE_STORAGE.name)
        }
        return buffer.toByteArray()
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.encodeToByteArray()
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun securityLevel(info: KeyInfo): CognitiveKeyProtectorSecurityLevel =
        if (Build.VERSION.SDK_INT >= 31) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> CognitiveKeyProtectorSecurityLevel.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> CognitiveKeyProtectorSecurityLevel.SOFTWARE
                else -> CognitiveKeyProtectorSecurityLevel.UNKNOWN
            }
        } else if (info.isInsideSecureHardware) {
            CognitiveKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT
        } else {
            CognitiveKeyProtectorSecurityLevel.SOFTWARE
        }

    private fun randomPlatformReference(): CognitiveKeyProtectorPlatformReference {
        val bytes = ByteArray(32).also(secureRandom::nextBytes)
        return CognitiveKeyProtectorPlatformReference(
            "random:" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        )
    }

    private fun aliasFor(id: String, generation: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$id:$generation".encodeToByteArray())
        return ALIAS_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun platformReferenceKey(alias: String): String = "$alias.platformReference"
    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val METADATA_PREFERENCES = "pro.liliya.cognitive.protector.metadata.v1"
        const val ALIAS_PREFIX = "liliya.cognitive.protector.v1."
        const val CIPHER = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = 16
    }
}
