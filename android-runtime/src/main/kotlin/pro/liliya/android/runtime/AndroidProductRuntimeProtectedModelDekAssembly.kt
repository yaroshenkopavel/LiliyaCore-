package pro.liliya.android.runtime

import android.content.Context
import pro.liliya.android.devicekey.AndroidProtectedModelKeyProtector
import pro.liliya.android.persistence.AndroidDurablePersistentRecordBackend
import pro.liliya.core.foundation.FoundationComposition
import pro.liliya.core.protectedmodel.PersistentProtectedModelDekOpenResult
import pro.liliya.core.protectedmodel.PersistentProtectedModelDekStore

sealed interface AndroidProductRuntimeProtectedModelDekOpenResult {
    data class Ready(
        val assembly: AndroidProductRuntimeProtectedModelDekAssembly
    ) : AndroidProductRuntimeProtectedModelDekOpenResult

    data object Corrupt : AndroidProductRuntimeProtectedModelDekOpenResult

    data class Incompatible(
        val reason: String
    ) : AndroidProductRuntimeProtectedModelDekOpenResult

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : AndroidProductRuntimeProtectedModelDekOpenResult
}

/**
 * Production Android ownership for protected-model wrapped DEKs.
 *
 * The assembly owns only app-private durable storage and the dedicated Android Keystore protector.
 * Exact DEK material must come from an external trusted provisioning/key-release boundary and is
 * registered explicitly through [PersistentProtectedModelDekStore.registerExact].
 *
 * This assembly does not generate model DEKs, select a model, choose a protector security level,
 * grant License/Authority, rotate keys, or publish an engine handle.
 */
class AndroidProductRuntimeProtectedModelDekAssembly private constructor(
    val keyProtector: AndroidProtectedModelKeyProtector,
    val store: PersistentProtectedModelDekStore
) {
    companion object {
        private const val DEFAULT_DIRECTORY = "liliya-protected-model-dek-v1"

        fun open(
            context: Context,
            foundation: FoundationComposition,
            directoryName: String = DEFAULT_DIRECTORY
        ): AndroidProductRuntimeProtectedModelDekOpenResult {
            val backend = try {
                AndroidDurablePersistentRecordBackend.create(
                    context = context.applicationContext,
                    directoryName = directoryName
                )
            } catch (throwable: Throwable) {
                return AndroidProductRuntimeProtectedModelDekOpenResult.Failed(
                    reason = "protected-model DEK durable backend open failed",
                    throwable = throwable
                )
            }

            val protector = try {
                AndroidProtectedModelKeyProtector(context.applicationContext)
            } catch (throwable: Throwable) {
                return AndroidProductRuntimeProtectedModelDekOpenResult.Failed(
                    reason = "protected-model key protector open failed",
                    throwable = throwable
                )
            }

            return when (
                val opened = PersistentProtectedModelDekStore.open(
                    foundation = foundation,
                    backend = backend,
                    protector = protector
                )
            ) {
                is PersistentProtectedModelDekOpenResult.Opened ->
                    AndroidProductRuntimeProtectedModelDekOpenResult.Ready(
                        AndroidProductRuntimeProtectedModelDekAssembly(
                            keyProtector = protector,
                            store = opened.store
                        )
                    )

                PersistentProtectedModelDekOpenResult.Corrupt ->
                    AndroidProductRuntimeProtectedModelDekOpenResult.Corrupt

                is PersistentProtectedModelDekOpenResult.Incompatible ->
                    AndroidProductRuntimeProtectedModelDekOpenResult.Incompatible(opened.reason)

                is PersistentProtectedModelDekOpenResult.Failed ->
                    AndroidProductRuntimeProtectedModelDekOpenResult.Failed(
                        reason = opened.reason,
                        throwable = opened.throwable
                    )
            }
        }
    }
}
