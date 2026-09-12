package pro.liliya.app

import android.net.Uri

/**
 * Explicit Application-owned bridge from one caller-selected URI into the bounded Product Auth
 * importer and dedicated encrypted store.
 *
 * URI ownership remains with the caller. No persistable permission, URI, or plaintext credential
 * is retained by this bridge.
 */
internal fun LiliyaApplication.importProductAuthCredential(
    uri: Uri
): ProductionAndroidProductAuthImportResult {
    val store = ProductionAndroidProductAuthEncryptedStore.create(this)
    return ProductionAndroidProductAuthCredentialImport.import(
        openInput = { contentResolver.openInputStream(uri) },
        provision = store::provision
    )
}
