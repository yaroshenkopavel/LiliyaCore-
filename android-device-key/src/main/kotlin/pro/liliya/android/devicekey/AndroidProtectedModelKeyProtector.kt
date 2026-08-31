package pro.liliya.android.devicekey

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
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
import pro.liliya.core.protectedmodel.*

/** Dedicated Android Keystore AES protector for protected-model DEKs. Separate from Device Key and cognitive storage. */
class AndroidProtectedModelKeyProtector(context: Context) : ProtectedModelKeyProtector {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    override fun create(request: ProtectedModelKeyProtectorCreationRequest): ProtectedModelKeyProtectorResult<ProtectedModelKeyProtectorDescriptor> {
        val alias = aliasFor(request.id.value, request.generation.value)
        val metadataKey = "$alias.platformReference"
        var generated = false
        return try {
            val store = keyStore()
            if (store.containsAlias(alias) || preferences.contains(metadataKey)) {
                return ProtectedModelKeyProtectorResult.Rejected(ProtectedModelKeyProtectorFailure.STALE_PROTECTOR_OWNERSHIP)
            }
            val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
            if (request.requestedSecurityLevel == ProtectedModelKeyProtectorSecurityLevel.STRONGBOX) {
                builder.setIsStrongBoxBacked(true)
            }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
                init(builder.build())
                generateKey()
            }
            generated = true
            val platform = randomPlatformReference()
            if (!preferences.edit().putString(metadataKey, platform.value).commit()) {
                cleanup(alias, metadataKey)
                return ProtectedModelKeyProtectorResult.Rejected(ProtectedModelKeyProtectorFailure.CLEANUP_FAILED)
            }
            val reference = ProtectedModelKeyProtectorReference(request.id, request.generation, platform)
            when (val inspected = inspect(reference)) {
                is ProtectedModelKeyProtectorResult.Success -> {
                    if (meets(request.requestedSecurityLevel, inspected.value.securityLevel)) {
                        inspected
                    } else if (cleanup(alias, metadataKey)) {
                        ProtectedModelKeyProtectorResult.Rejected(
                            ProtectedModelKeyProtectorFailure.REQUIRED_SECURITY_LEVEL_UNAVAILABLE
                        )
                    } else {
                        ProtectedModelKeyProtectorResult.Rejected(
                            ProtectedModelKeyProtectorFailure.CLEANUP_FAILED
                        )
                    }
                }
                is ProtectedModelKeyProtectorResult.Rejected -> {
                    if (cleanup(alias, metadataKey)) inspected
                    else ProtectedModelKeyProtectorResult.Rejected(
                        ProtectedModelKeyProtectorFailure.CLEANUP_FAILED
                    )
                }
                is ProtectedModelKeyProtectorResult.Failed -> {
                    if (cleanup(alias, metadataKey)) inspected
                    else ProtectedModelKeyProtectorResult.Rejected(
                        ProtectedModelKeyProtectorFailure.CLEANUP_FAILED
                    )
                }
            }
        } catch (_: StrongBoxUnavailableException) {
            if (generated && !cleanup(alias, metadataKey)) {
                ProtectedModelKeyProtectorResult.Rejected(ProtectedModelKeyProtectorFailure.CLEANUP_FAILED)
            } else {
                ProtectedModelKeyProtectorResult.Rejected(
                    ProtectedModelKeyProtectorFailure.REQUIRED_SECURITY_LEVEL_UNAVAILABLE
                )
            }
        } catch (throwable: Throwable) {
            if (generated && !cleanup(alias, metadataKey)) {
                ProtectedModelKeyProtectorResult.Rejected(ProtectedModelKeyProtectorFailure.CLEANUP_FAILED)
            } else {
                ProtectedModelKeyProtectorResult.Failed(
                    ProtectedModelKeyProtectorFailure.PROVIDER_FAILED,
                    throwable
                )
            }
        }
    }

    override fun inspect(reference: ProtectedModelKeyProtectorReference): ProtectedModelKeyProtectorResult<ProtectedModelKeyProtectorDescriptor> = try {
        val alias = aliasFor(reference.id.value, reference.generation.value)
        val metadataKey = "$alias.platformReference"
        val store = keyStore()
        if (!store.containsAlias(alias)) return ProtectedModelKeyProtectorResult.Rejected(ProtectedModelKeyProtectorFailure.PROTECTOR_MISSING)
        if (preferences.getString(metadataKey, null) != reference.platformReference.value) {
            return ProtectedModelKeyProtectorResult.Rejected(ProtectedModelKeyProtectorFailure.STALE_PROTECTOR_OWNERSHIP)
        }
        val key = store.getKey(alias, null) as? SecretKey
            ?: return ProtectedModelKeyProtectorResult.Rejected(ProtectedModelKeyProtectorFailure.PROTECTOR_MISSING)
        val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        if (info.keySize != 256 || info.purposes and KeyProperties.PURPOSE_ENCRYPT == 0 || info.purposes and KeyProperties.PURPOSE_DECRYPT == 0 || KeyProperties.BLOCK_MODE_GCM !in info.blockModes) {
            return ProtectedModelKeyProtectorResult.Rejected(ProtectedModelKeyProtectorFailure.INVALID_REQUEST)
        }
        ProtectedModelKeyProtectorResult.Success(ProtectedModelKeyProtectorDescriptor(reference, securityLevel(info)))
    } catch (_: KeyPermanentlyInvalidatedException) {
        ProtectedModelKeyProtectorResult.Rejected(ProtectedModelKeyProtectorFailure.PROTECTOR_INVALIDATED)
    } catch (throwable: Throwable) {
        ProtectedModelKeyProtectorResult.Failed(ProtectedModelKeyProtectorFailure.PROVIDER_FAILED, throwable)
    }

    override fun wrap(expected: ProtectedModelKeyProtectorDescriptor, dek: ModelDekReference, material: ProtectedModelDekMaterial): ProtectedModelKeyProtectorResult<WrappedProtectedModelDek> {
        val current = when (val checked = exact(expected)) {
            is ProtectedModelKeyProtectorResult.Success -> checked.value
            is ProtectedModelKeyProtectorResult.Rejected -> return checked
            is ProtectedModelKeyProtectorResult.Failed -> return checked
        }
        return try {
            val key = keyFor(current.reference) ?: return ProtectedModelKeyProtectorResult.Rejected(ProtectedModelKeyProtectorFailure.PROTECTOR_MISSING)
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val nonce = cipher.iv ?: return ProtectedModelKeyProtectorResult.Rejected(ProtectedModelKeyProtectorFailure.WRAP_FAILED)
            if (nonce.size != 12) return ProtectedModelKeyProtectorResult.Rejected(ProtectedModelKeyProtectorFailure.WRAP_FAILED)
            val aad = associatedData(dek, current.reference)
            val raw = material.copyBytes()
            val output = try {
                cipher.updateAAD(aad)
                cipher.doFinal(raw)
            } finally {
                raw.fill(0); aad.fill(0)
            }
            try {
                val split = output.size - 16
                if (split <= 0) return ProtectedModelKeyProtectorResult.Rejected(ProtectedModelKeyProtectorFailure.WRAP_FAILED)
                ProtectedModelKeyProtectorResult.Success(WrappedProtectedModelDek(dek, current.reference, output.copyOfRange(0, split), nonce, output.copyOfRange(split, output.size)))
            } finally {
                output.fill(0); nonce.fill(0)
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            ProtectedModelKeyProtectorResult.Rejected(ProtectedModelKeyProtectorFailure.PROTECTOR_INVALIDATED)
        } catch (throwable: Throwable) {
            ProtectedModelKeyProtectorResult.Failed(ProtectedModelKeyProtectorFailure.WRAP_FAILED, throwable)
        }
    }

    override fun unwrap(expected: ProtectedModelKeyProtectorDescriptor, envelope: WrappedProtectedModelDek): ProtectedModelKeyProtectorResult<ProtectedModelDekMaterial> {
        val current = when (val checked = exact(expected)) {
            is ProtectedModelKeyProtectorResult.Success -> checked.value
            is ProtectedModelKeyProtectorResult.Rejected -> return checked
            is ProtectedModelKeyProtectorResult.Failed -> return checked
        }
        if (envelope.protector != current.reference) return ProtectedModelKeyProtectorResult.Rejected(ProtectedModelKeyProtectorFailure.STALE_PROTECTOR_OWNERSHIP)
        return try {
            val key = keyFor(current.reference) ?: return ProtectedModelKeyProtectorResult.Rejected(ProtectedModelKeyProtectorFailure.PROTECTOR_MISSING)
            val nonce = envelope.copyNonce()
            val tag = envelope.copyAuthenticationTag()
            val wrapped = envelope.copyWrapped()
            val input = wrapped + tag
            val aad = associatedData(envelope.dek, current.reference)
            val plaintext = try {
                val cipher = Cipher.getInstance(CIPHER)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
                cipher.updateAAD(aad)
                cipher.doFinal(input)
            } finally {
                nonce.fill(0); tag.fill(0); wrapped.fill(0); input.fill(0); aad.fill(0)
            }
            try {
                ProtectedModelKeyProtectorResult.Success(ProtectedModelDekMaterial(plaintext))
            } finally {
                plaintext.fill(0)
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            ProtectedModelKeyProtectorResult.Rejected(ProtectedModelKeyProtectorFailure.PROTECTOR_INVALIDATED)
        } catch (throwable: Throwable) {
            ProtectedModelKeyProtectorResult.Failed(ProtectedModelKeyProtectorFailure.UNWRAP_FAILED, throwable)
        }
    }

    override fun retire(expected: ProtectedModelKeyProtectorDescriptor): ProtectedModelKeyProtectorResult<Unit> {
        val current = when (val checked = exact(expected)) {
            is ProtectedModelKeyProtectorResult.Success -> checked.value
            is ProtectedModelKeyProtectorResult.Rejected -> return checked
            is ProtectedModelKeyProtectorResult.Failed -> return checked
        }
        val alias = aliasFor(current.reference.id.value, current.reference.generation.value)
        val metadataKey = "$alias.platformReference"
        return if (cleanup(alias, metadataKey)) ProtectedModelKeyProtectorResult.Success(Unit)
        else ProtectedModelKeyProtectorResult.Rejected(ProtectedModelKeyProtectorFailure.CLEANUP_FAILED)
    }

    internal fun aliasForTesting(reference: ProtectedModelKeyProtectorReference): String = aliasFor(reference.id.value, reference.generation.value)

    private fun exact(expected: ProtectedModelKeyProtectorDescriptor): ProtectedModelKeyProtectorResult<ProtectedModelKeyProtectorDescriptor> = when (val inspected = inspect(expected.reference)) {
        is ProtectedModelKeyProtectorResult.Success -> if (inspected.value == expected) inspected else ProtectedModelKeyProtectorResult.Rejected(ProtectedModelKeyProtectorFailure.STALE_PROTECTOR_OWNERSHIP)
        is ProtectedModelKeyProtectorResult.Rejected -> inspected
        is ProtectedModelKeyProtectorResult.Failed -> inspected
    }

    private fun keyFor(reference: ProtectedModelKeyProtectorReference): SecretKey? = keyStore().getKey(aliasFor(reference.id.value, reference.generation.value), null) as? SecretKey

    private fun associatedData(dek: ModelDekReference, protector: ProtectedModelKeyProtectorReference): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(1)
            writeString(out, "PROTECTED_MODEL")
            writeString(out, dek.id.value)
            out.writeLong(dek.generation.value)
            writeString(out, protector.id.value)
            out.writeLong(protector.generation.value)
            writeString(out, protector.platformReference.value)
            writeString(out, "AES_256_GCM")
        }
    }.toByteArray()

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.encodeToByteArray(); out.writeInt(bytes.size); out.write(bytes)
    }

    private fun meets(requested: ProtectedModelKeyProtectorSecurityLevel, actual: ProtectedModelKeyProtectorSecurityLevel): Boolean = when (requested) {
        ProtectedModelKeyProtectorSecurityLevel.STRONGBOX -> actual == ProtectedModelKeyProtectorSecurityLevel.STRONGBOX
        ProtectedModelKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT -> actual == ProtectedModelKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT || actual == ProtectedModelKeyProtectorSecurityLevel.STRONGBOX
        ProtectedModelKeyProtectorSecurityLevel.SOFTWARE -> actual != ProtectedModelKeyProtectorSecurityLevel.UNKNOWN
        ProtectedModelKeyProtectorSecurityLevel.UNKNOWN -> false
    }

    private fun securityLevel(info: KeyInfo): ProtectedModelKeyProtectorSecurityLevel = if (Build.VERSION.SDK_INT >= 31) when (info.securityLevel) {
        KeyProperties.SECURITY_LEVEL_STRONGBOX -> ProtectedModelKeyProtectorSecurityLevel.STRONGBOX
        KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> ProtectedModelKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT
        KeyProperties.SECURITY_LEVEL_SOFTWARE -> ProtectedModelKeyProtectorSecurityLevel.SOFTWARE
        else -> ProtectedModelKeyProtectorSecurityLevel.UNKNOWN
    } else if (info.isInsideSecureHardware) ProtectedModelKeyProtectorSecurityLevel.TRUSTED_ENVIRONMENT else ProtectedModelKeyProtectorSecurityLevel.SOFTWARE

    private fun randomPlatformReference(): ProtectedModelKeyProtectorPlatformReference {
        val bytes = ByteArray(32).also(secureRandom::nextBytes)
        return try { ProtectedModelKeyProtectorPlatformReference("random:" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)) } finally { bytes.fill(0) }
    }

    private fun aliasFor(id: String, generation: Long): String {
        val source = "$id:$generation".encodeToByteArray()
        val digest = try { MessageDigest.getInstance("SHA-256").digest(source) } finally { source.fill(0) }
        return try { ALIAS_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest) } finally { digest.fill(0) }
    }

    private fun cleanup(alias: String, metadataKey: String): Boolean = try {
        val store = keyStore(); if (store.containsAlias(alias)) store.deleteEntry(alias); preferences.edit().remove(metadataKey).commit()
    } catch (_: Throwable) { false }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val PREFERENCES = "pro.liliya.protectedmodel.protector.metadata.v1"
        const val ALIAS_PREFIX = "liliya.protectedmodel.protector.v1."
        const val CIPHER = "AES/GCM/NoPadding"
    }
}
