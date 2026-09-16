package pro.liliya.app

import android.content.Context
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import pro.liliya.android.devicekey.AndroidCognitiveKeyProtector
import pro.liliya.core.encryption.CognitiveEncryptionResult
import pro.liliya.core.encryption.CognitiveKeyProtectorGeneration
import pro.liliya.core.encryption.CognitiveKeyProtectorId
import pro.liliya.core.encryption.CognitiveKeyProtectorPlatformReference
import pro.liliya.core.encryption.CognitiveKeyProtectorReference

/**
 * Test-only exact cleanup for the deterministic cognitive protector IDs used by physical
 * HostBootstrap acceptance overlays. This never enumerates or removes unrelated keystore aliases.
 */
object AcceptanceCognitiveProtectorCleanup {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val METADATA_PREFERENCES = "pro.liliya.cognitive.protector.metadata.v1"
    private const val ALIAS_PREFIX = "liliya.cognitive.protector.v1."

    fun retireExactIfPresent(
        context: Context,
        id: String,
        generation: Long
    ): String {
        val alias = aliasFor(id, generation)
        val metadataKey = "$alias.platformReference"
        val preferences = context.applicationContext.getSharedPreferences(
            METADATA_PREFERENCES,
            Context.MODE_PRIVATE
        )
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val aliasPresent = keyStore.containsAlias(alias)
        val storedReference = preferences.getString(metadataKey, null)

        if (!aliasPresent && storedReference == null) return "ABSENT"
        if (!aliasPresent || storedReference == null) {
            return "INCOMPLETE_OWNERSHIP"
        }

        val reference = CognitiveKeyProtectorReference(
            id = CognitiveKeyProtectorId(id),
            generation = CognitiveKeyProtectorGeneration(generation),
            platformReference = CognitiveKeyProtectorPlatformReference(storedReference)
        )
        val protector = AndroidCognitiveKeyProtector(context.applicationContext)
        val inspected = protector.inspect(reference)
        val descriptor = when (inspected) {
            is CognitiveEncryptionResult.Success -> inspected.value
            is CognitiveEncryptionResult.Rejected ->
                return "INSPECT_REJECTED_${inspected.category.name}"
            is CognitiveEncryptionResult.Failed ->
                return "INSPECT_FAILED_${inspected.category.name}"
        }
        return when (val retired = protector.retire(descriptor)) {
            is CognitiveEncryptionResult.Success -> "RETIRED"
            is CognitiveEncryptionResult.Rejected -> "RETIRE_REJECTED_${retired.category.name}"
            is CognitiveEncryptionResult.Failed -> "RETIRE_FAILED_${retired.category.name}"
        }
    }

    private fun aliasFor(id: String, generation: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$id:$generation".encodeToByteArray())
        return ALIAS_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
