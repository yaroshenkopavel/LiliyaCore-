package pro.liliya.app

import android.net.Uri

/**
 * Process/Application-scope owner for the single Product Auth import task.
 *
 * The owner retains only typed task state. URI ownership remains with the caller and the
 * Application bridge opens the URI only inside the bounded import operation.
 */
internal object ProductionAndroidProductAuthImportTaskOwner {
    @Volatile
    private var task = ProductionAndroidProductAuthImportTask()

    fun request(
        application: LiliyaApplication,
        uri: Uri,
        listener: (ProductionAndroidProductAuthImportTaskSnapshot.Completed) -> Unit
    ): ProductionAndroidProductAuthImportTaskRequestResult =
        task.request(
            importCredential = { application.importProductAuthCredential(uri) },
            listener = listener
        )

    fun observe(
        listener: (ProductionAndroidProductAuthImportTaskSnapshot.Completed) -> Unit
    ): ProductionAndroidProductAuthImportTaskSnapshot = task.observe(listener)

    fun consume(requestId: Long): Boolean = task.consume(requestId)

    @Synchronized
    internal fun replaceForTests(
        replacement: ProductionAndroidProductAuthImportTask
    ): ProductionAndroidProductAuthImportTask {
        val previous = task
        task = replacement
        return previous
    }
}

internal fun LiliyaApplication.requestProductAuthCredentialImport(
    uri: Uri,
    listener: (ProductionAndroidProductAuthImportTaskSnapshot.Completed) -> Unit
): ProductionAndroidProductAuthImportTaskRequestResult =
    ProductionAndroidProductAuthImportTaskOwner.request(this, uri, listener)

internal fun LiliyaApplication.observeProductAuthCredentialImport(
    listener: (ProductionAndroidProductAuthImportTaskSnapshot.Completed) -> Unit
): ProductionAndroidProductAuthImportTaskSnapshot =
    ProductionAndroidProductAuthImportTaskOwner.observe(listener)

internal fun LiliyaApplication.consumeProductAuthCredentialImport(requestId: Long): Boolean =
    ProductionAndroidProductAuthImportTaskOwner.consume(requestId)

internal fun LiliyaApplication.hasProductAuthCredential(): Boolean {
    val secret = try {
        ProductionAndroidProductAuthEncryptedStore.create(this).openSecret()
    } catch (_: IllegalStateException) {
        return false
    }
    return try {
        true
    } finally {
        secret.fill(0)
    }
}

internal fun LiliyaApplication.replaceProductAuthImportTaskForTests(
    replacement: ProductionAndroidProductAuthImportTask
): ProductionAndroidProductAuthImportTask =
    ProductionAndroidProductAuthImportTaskOwner.replaceForTests(replacement)
