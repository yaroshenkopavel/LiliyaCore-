package pro.liliya.android.semanticprovider.host

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast

/**
 * Explicit user consent to select a pre-existing GGUF from Android Documents.
 * Does not copy, move, rename, download, or stage the file.
 */
class Qwen3SourcePickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_PICK)
    }

    @Deprecated("Legacy Activity result is sufficient for an isolated test host.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK) return
        if (resultCode == RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null && uri.scheme == "content") {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_URI, uri.toString()).apply()
                    Toast.makeText(this, "Qwen3 source access granted (read-only)", Toast.LENGTH_LONG).show()
                } catch (_: SecurityException) {
                    Toast.makeText(this, "Document provider denied persistent read permission", Toast.LENGTH_LONG).show()
                }
            }
        }
        finish()
    }

    private companion object {
        const val REQUEST_PICK = 51
        const val PREFS = "qwen3-local-source-test-v1"
        const val KEY_URI = "selected-content-uri"
    }
}
