package pro.liliya.app

import android.content.Context
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import pro.liliya.android.devicekey.AndroidCognitiveKeyProtector
import pro.liliya.android.devicekey.AndroidProtectedModelKeyProtector
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitiveKeyProtectorGeneration
import pro.liliya.core.encryption.CognitiveKeyProtectorId
import pro.liliya.core.encryption.CognitiveKeyProtectorPlatformReference
import pro.liliya.core.encryption.CognitiveKeyProtectorReference
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorGeneration
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorId
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorPlatformReference
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorReference
import pro.liliya.core.protectedmodel.ProtectedModelKeyProtectorResult

/** Test-only exact cleanup for deterministic physical-acceptance protector IDs. */
object PhysicalAcceptanceCognitiveProtectorCleanup {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    private const val COGNITIVE_METADATA_PREFERENCES =
        "pro.liliya.cognitive.protector.metadata.v1"
    private const val COGNITIVE_ALIAS_PREFIX =
        "liliya.cognitive.protector.v1."

    private const val MODEL_METADATA_PREFERENCES =
        "pro.liliya.protectedmodel.protector.metadata.v1"
    private const val MODEL_ALIAS_PREFIX =
        "liliya.protectedmodel.protector.v1."

    fun retireExactIfPresent(context: Context, id: String, generation: Long): String {
        val alias = aliasFor(COGNITIVE_ALIAS_PREFIX, id, generation)
        val metadataKey = alias + ".platformReference"
        val preferences = context.applicationContext.getSharedPreferences(
            COGNITIVE_METADATA_PREFERENCES,
            Context.MODE_PRIVATE
        )
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val aliasPresent = keyStore.containsAlias(alias)
        val storedReference = preferences.getString(metadataKey, null)

        if (!aliasPresent && storedReference == null) return "ABSENT"
        if (!aliasPresent || storedReference == null) return "INCOMPLETE_OWNERSHIP"

        val reference = CognitiveKeyProtectorReference(
            id = CognitiveKeyProtectorId(id),
            generation = CognitiveKeyProtectorGeneration(generation),
            platformReference = CognitiveKeyProtectorPlatformReference(storedReference)
        )
        val protector = AndroidCognitiveKeyProtector(context.applicationContext)
        val descriptor = when (val inspected = protector.inspect(reference)) {
            is CognitiveEncryptionResult.Success -> inspected.value
            is CognitiveEncryptionResult.Rejected ->
                return "INSPECT_REJECTED_" + inspected.category.name
            is CognitiveEncryptionResult.Failed ->
                return "INSPECT_FAILED_" + inspected.category.name
        }
        return when (val retired = protector.retire(descriptor)) {
            is CognitiveEncryptionResult.Success -> "RETIRED"
            is CognitiveEncryptionResult.Rejected ->
                "RETIRE_REJECTED_" + retired.category.name
            is CognitiveEncryptionResult.Failed ->
                "RETIRE_FAILED_" + retired.category.name
        }
    }

    fun retireProtectedModelExactIfPresent(
        context: Context,
        id: String,
        generation: Long
    ): String {
        val alias = aliasFor(MODEL_ALIAS_PREFIX, id, generation)
        val metadataKey = alias + ".platformReference"
        val preferences = context.applicationContext.getSharedPreferences(
            MODEL_METADATA_PREFERENCES,
            Context.MODE_PRIVATE
        )
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val aliasPresent = keyStore.containsAlias(alias)
        val storedReference = preferences.getString(metadataKey, null)

        if (!aliasPresent && storedReference == null) return "ABSENT"
        if (!aliasPresent || storedReference == null) return "INCOMPLETE_OWNERSHIP"

        val reference = ProtectedModelKeyProtectorReference(
            id = ProtectedModelKeyProtectorId(id),
            generation = ProtectedModelKeyProtectorGeneration(generation),
            platformReference = ProtectedModelKeyProtectorPlatformReference(storedReference)
        )
        val protector = AndroidProtectedModelKeyProtector(context.applicationContext)
        val descriptor = when (val inspected = protector.inspect(reference)) {
            is ProtectedModelKeyProtectorResult.Success -> inspected.value
            is ProtectedModelKeyProtectorResult.Rejected ->
                return "INSPECT_REJECTED_" + inspected.reason.name
            is ProtectedModelKeyProtectorResult.Failed ->
                return "INSPECT_FAILED_" + inspected.reason.name
        }
        return when (val retired = protector.retire(descriptor)) {
            is ProtectedModelKeyProtectorResult.Success -> "RETIRED"
            is ProtectedModelKeyProtectorResult.Rejected ->
                "RETIRE_REJECTED_" + retired.reason.name
            is ProtectedModelKeyProtectorResult.Failed ->
                "RETIRE_FAILED_" + retired.reason.name
        }
    }

    private fun aliasFor(prefix: String, id: String, generation: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((id + ":" + generation).encodeToByteArray())
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
