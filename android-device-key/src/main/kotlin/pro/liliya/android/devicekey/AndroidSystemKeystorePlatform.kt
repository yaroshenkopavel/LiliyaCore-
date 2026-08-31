package pro.liliya.android.devicekey

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import pro.liliya.core.devicekey.AndroidKeystoreKeyDescriptor
import pro.liliya.core.devicekey.AndroidKeystorePlatform
import pro.liliya.core.devicekey.AndroidKeystorePlatformResult
import pro.liliya.core.devicekey.AndroidKeystoreSignatureDescriptor
import pro.liliya.core.devicekey.DeviceKeyAlgorithm
import pro.liliya.core.devicekey.DeviceKeyCapability
import pro.liliya.core.devicekey.DeviceKeyChallenge
import pro.liliya.core.devicekey.DeviceKeyCreationRequest
import pro.liliya.core.devicekey.DeviceKeyFailureCategory
import pro.liliya.core.devicekey.DeviceKeyId
import pro.liliya.core.devicekey.DeviceKeyPlatformReference
import pro.liliya.core.devicekey.DeviceKeyProofSignature
import pro.liliya.core.devicekey.DeviceKeySecurityLevel

/**
 * Android Keystore implementation for the reviewed Device Key v0.1 SPI.
 *
 * The implementation supports only EC P-256 / SHA-256 signing. Private keys never leave the
 * Android Keystore provider. App-private metadata stores only the caller-supplied creation time;
 * the platform-instance reference is derived from the public key digest and is never a permission.
 */
class AndroidSystemKeystorePlatform(context: Context) : AndroidKeystorePlatform {
    private val preferences = context.applicationContext.getSharedPreferences(
        METADATA_PREFERENCES,
        Context.MODE_PRIVATE
    )

    override fun generate(
        request: DeviceKeyCreationRequest,
        createdAt: Instant
    ): AndroidKeystorePlatformResult<AndroidKeystoreKeyDescriptor> {
        if (!supports(request)) {
            return AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.UNSUPPORTED_PROFILE)
        }

        val alias = aliasFor(request.id)
        val metadataKey = createdAtKey(alias)
        var generatedByThisCall = false

        return try {
            val keyStore = keyStore()
            if (keyStore.containsAlias(alias)) {
                return AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.PLATFORM_REJECTED)
            }
            if (preferences.contains(metadataKey)) {
                return AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.MALFORMED_METADATA)
            }

            val builder = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)

            if (request.profile.requestedSecurityLevel == DeviceKeySecurityLevel.STRONGBOX) {
                builder.setIsStrongBoxBacked(true)
            }

            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE
            )
            generator.initialize(builder.build())
            generator.generateKeyPair()
            generatedByThisCall = true

            if (!preferences.edit().putLong(metadataKey, createdAt.toEpochMilli()).commit()) {
                return cleanupGenerated(
                    alias = alias,
                    metadataKey = metadataKey,
                    original = AndroidKeystorePlatformResult.Rejected(
                        DeviceKeyFailureCategory.PLATFORM_REJECTED
                    )
                )
            }

            when (val inspected = inspect(request.id)) {
                is AndroidKeystorePlatformResult.Success -> inspected
                is AndroidKeystorePlatformResult.Rejected ->
                    cleanupGenerated(alias, metadataKey, inspected)
                is AndroidKeystorePlatformResult.Failed ->
                    cleanupGenerated(alias, metadataKey, inspected)
            }
        } catch (_: StrongBoxUnavailableException) {
            AndroidKeystorePlatformResult.Rejected(
                DeviceKeyFailureCategory.REQUIRED_SECURITY_LEVEL_UNAVAILABLE
            )
        } catch (_: Throwable) {
            if (generatedByThisCall) {
                cleanupGenerated(
                    alias = alias,
                    metadataKey = metadataKey,
                    original = AndroidKeystorePlatformResult.Rejected(
                        DeviceKeyFailureCategory.PLATFORM_REJECTED
                    )
                )
            } else {
                AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.PLATFORM_REJECTED)
            }
        }
    }

    override fun inspect(
        id: DeviceKeyId
    ): AndroidKeystorePlatformResult<AndroidKeystoreKeyDescriptor> {
        val alias = aliasFor(id)
        val metadataKey = createdAtKey(alias)

        return try {
            val keyStore = keyStore()
            if (!keyStore.containsAlias(alias)) {
                return if (preferences.contains(metadataKey)) {
                    AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.MALFORMED_METADATA)
                } else {
                    AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.KEY_MISSING)
                }
            }
            if (!preferences.contains(metadataKey)) {
                return AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.MALFORMED_METADATA)
            }

            val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
                ?: return AndroidKeystorePlatformResult.Rejected(
                    DeviceKeyFailureCategory.MALFORMED_METADATA
                )
            val privateKey = entry.privateKey
            if (privateKey.algorithm != KeyProperties.KEY_ALGORITHM_EC) {
                return AndroidKeystorePlatformResult.Rejected(
                    DeviceKeyFailureCategory.MALFORMED_METADATA
                )
            }

            val info = KeyFactory.getInstance(
                privateKey.algorithm,
                ANDROID_KEYSTORE
            ).getKeySpec(privateKey, KeyInfo::class.java)

            if (
                info.keySize != EC_KEY_SIZE ||
                KeyProperties.DIGEST_SHA256 !in info.digests ||
                info.purposes and KeyProperties.PURPOSE_SIGN == 0
            ) {
                return AndroidKeystorePlatformResult.Rejected(
                    DeviceKeyFailureCategory.MALFORMED_METADATA
                )
            }

            val createdAtMillis = preferences.getLong(metadataKey, Long.MIN_VALUE)
            if (createdAtMillis == Long.MIN_VALUE) {
                return AndroidKeystorePlatformResult.Rejected(
                    DeviceKeyFailureCategory.MALFORMED_METADATA
                )
            }

            AndroidKeystorePlatformResult.Success(
                AndroidKeystoreKeyDescriptor(
                    id = id,
                    algorithm = SUPPORTED_ALGORITHM,
                    securityLevel = securityLevel(info),
                    capabilities = setOf(DeviceKeyCapability.SIGN_CHALLENGE),
                    createdAt = Instant.ofEpochMilli(createdAtMillis),
                    platformReference = platformReference(entry.certificate.publicKey.encoded)
                )
            )
        } catch (_: KeyPermanentlyInvalidatedException) {
            AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.KEY_INVALIDATED)
        } catch (_: UserNotAuthenticatedException) {
            AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.AUTHENTICATION_REQUIRED)
        } catch (_: Throwable) {
            AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.PLATFORM_REJECTED)
        }
    }

    override fun signChallenge(
        expected: AndroidKeystoreKeyDescriptor,
        challenge: DeviceKeyChallenge
    ): AndroidKeystorePlatformResult<AndroidKeystoreSignatureDescriptor> {
        val current = when (val inspected = inspect(expected.id)) {
            is AndroidKeystorePlatformResult.Success -> inspected.value
            is AndroidKeystorePlatformResult.Rejected -> return inspected
            is AndroidKeystorePlatformResult.Failed -> return inspected
        }
        if (current != expected) {
            return AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.STALE_OWNERSHIP)
        }

        return try {
            val alias = aliasFor(expected.id)
            val entry = keyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
                ?: return AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.KEY_MISSING)
            val signingReference = platformReference(entry.certificate.publicKey.encoded)
            if (signingReference != expected.platformReference) {
                return AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.STALE_OWNERSHIP)
            }

            val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
            signature.initSign(entry.privateKey)
            signature.update(challenge.copyBytes())

            AndroidKeystorePlatformResult.Success(
                AndroidKeystoreSignatureDescriptor(
                    id = expected.id,
                    platformReference = signingReference,
                    signature = DeviceKeyProofSignature(signature.sign())
                )
            )
        } catch (_: KeyPermanentlyInvalidatedException) {
            AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.KEY_INVALIDATED)
        } catch (_: UserNotAuthenticatedException) {
            AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.AUTHENTICATION_REQUIRED)
        } catch (_: Throwable) {
            AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.PLATFORM_REJECTED)
        }
    }

    override fun delete(id: DeviceKeyId): AndroidKeystorePlatformResult<Unit> {
        val alias = aliasFor(id)
        val metadataKey = createdAtKey(alias)

        return try {
            val keyStore = keyStore()
            val exists = keyStore.containsAlias(alias)
            val metadataExists = preferences.contains(metadataKey)
            if (!exists && !metadataExists) {
                return AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.KEY_MISSING)
            }

            if (exists) keyStore.deleteEntry(alias)
            val metadataRemoved = preferences.edit().remove(metadataKey).commit()
            if (!metadataRemoved) {
                AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.CLEANUP_FAILED)
            } else if (!exists) {
                AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.KEY_MISSING)
            } else {
                AndroidKeystorePlatformResult.Success(Unit)
            }
        } catch (_: Throwable) {
            AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.CLEANUP_FAILED)
        }
    }

    internal fun aliasForTesting(id: DeviceKeyId): String = aliasFor(id)

    private fun supports(request: DeviceKeyCreationRequest): Boolean =
        request.profile.algorithm == SUPPORTED_ALGORITHM &&
            request.profile.capabilities == setOf(DeviceKeyCapability.SIGN_CHALLENGE)

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun securityLevel(info: KeyInfo): DeviceKeySecurityLevel =
        if (Build.VERSION.SDK_INT >= 31) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> DeviceKeySecurityLevel.SOFTWARE
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ->
                    DeviceKeySecurityLevel.TRUSTED_ENVIRONMENT
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> DeviceKeySecurityLevel.STRONGBOX
                else -> DeviceKeySecurityLevel.UNKNOWN
            }
        } else if (info.isInsideSecureHardware) {
            // API 29-30 cannot distinguish TEE from StrongBox through KeyInfo. Under-report as TEE.
            DeviceKeySecurityLevel.TRUSTED_ENVIRONMENT
        } else {
            DeviceKeySecurityLevel.SOFTWARE
        }

    private fun platformReference(publicKeyBytes: ByteArray): DeviceKeyPlatformReference {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return DeviceKeyPlatformReference("sha256:$encoded")
    }

    private fun aliasFor(id: DeviceKeyId): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(id.value.toByteArray(Charsets.UTF_8))
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return "$ALIAS_PREFIX$encoded"
    }

    private fun createdAtKey(alias: String): String = "$alias.createdAt"

    private fun <T> cleanupGenerated(
        alias: String,
        metadataKey: String,
        original: AndroidKeystorePlatformResult<T>
    ): AndroidKeystorePlatformResult<T> = if (cleanupExact(alias, metadataKey)) {
        original
    } else {
        AndroidKeystorePlatformResult.Rejected(DeviceKeyFailureCategory.CLEANUP_FAILED)
    }

    private fun cleanupExact(alias: String, metadataKey: String): Boolean = try {
        val keyStore = keyStore()
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        preferences.edit().remove(metadataKey).commit()
    } catch (_: Throwable) {
        false
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val METADATA_PREFERENCES = "pro.liliya.devicekey.metadata.v1"
        const val ALIAS_PREFIX = "liliya.devicekey.v1."
        const val EC_CURVE = "secp256r1"
        const val EC_KEY_SIZE = 256
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        val SUPPORTED_ALGORITHM = DeviceKeyAlgorithm("EC-P256-SHA256")
    }
}
