package pro.liliya.android.semanticprovider

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Opaque ownership of the exact app-private Offline Semantic Provider v0.1 artifact bundle.
 *
 * Physical paths are intentionally not public. Pass this object directly to
 * [AndroidOfflineSemanticProviderAssembly.load].
 */
class AndroidOfflineSemanticProvisionedBundle internal constructor(
    internal val appPrivateRoot: File,
    internal val encoderFile: File
) {
    override fun toString(): String =
        "AndroidOfflineSemanticProvisionedBundle(profile=multilingual-e5-small-v0.1, path=<redacted>)"
}

sealed interface AndroidOfflineSemanticProvisioningResult {
    data class Provisioned(
        val bundle: AndroidOfflineSemanticProvisionedBundle
    ) : AndroidOfflineSemanticProvisioningResult

    data class AlreadyProvisioned(
        val bundle: AndroidOfflineSemanticProvisionedBundle
    ) : AndroidOfflineSemanticProvisioningResult

    data object ExistingBundleRejected : AndroidOfflineSemanticProvisioningResult
    data object SourceRejected : AndroidOfflineSemanticProvisioningResult
    data class PublicationFailed(
        val exceptionClass: String
    ) : AndroidOfflineSemanticProvisioningResult {
        override fun toString(): String =
            "PublicationFailed(exceptionClass=$exceptionClass)"
    }
}

/**
 * Blocking local provisioner for the exact repository-approved semantic ONNX bundle.
 *
 * The provisioner has no network capability and accepts only caller-provided local streams.
 * Hosts must schedule provisioning away from the Android main/UI thread.
 *
 * Caller retains ownership of [InputStream] instances and is responsible for closing them.
 */
class AndroidOfflineSemanticArtifactProvisioner internal constructor(
    private val root: File,
    private val spec: SemanticArtifactProvisioningSpec = productionProvisioningSpec(),
    private val requireProductionValidator: Boolean = true
) {
    fun provision(
        encoder: InputStream,
        tokenizer: InputStream
    ): AndroidOfflineSemanticProvisioningResult = synchronized(PROCESS_LOCK) {
        prepareRoot()?.let { return@synchronized it }

        val encoderTarget = File(root, spec.encoderFileName)
        val tokenizerTarget = File(root, spec.tokenizerFileName)

        val existing = inspectExisting(encoderTarget, tokenizerTarget)
        if (existing != null) return@synchronized existing

        val encoderTemp = File(root, ENCODER_TEMP_NAME)
        val tokenizerTemp = File(root, TOKENIZER_TEMP_NAME)
        encoderTemp.delete()
        tokenizerTemp.delete()

        val encoderPrepared = writeVerifiedTemp(
            input = encoder,
            target = encoderTemp,
            expectedBytes = spec.encoderBytes,
            expectedSha256 = spec.encoderSha256
        )
        if (!encoderPrepared) {
            encoderTemp.delete()
            tokenizerTemp.delete()
            return@synchronized AndroidOfflineSemanticProvisioningResult.SourceRejected
        }

        val tokenizerPrepared = writeVerifiedTemp(
            input = tokenizer,
            target = tokenizerTemp,
            expectedBytes = spec.tokenizerBytes,
            expectedSha256 = spec.tokenizerSha256
        )
        if (!tokenizerPrepared) {
            encoderTemp.delete()
            tokenizerTemp.delete()
            return@synchronized AndroidOfflineSemanticProvisioningResult.SourceRejected
        }

        var tokenizerPublished = false
        var encoderPublished = false
        try {
            atomicPublish(tokenizerTemp, tokenizerTarget)
            tokenizerPublished = true
            atomicPublish(encoderTemp, encoderTarget)
            encoderPublished = true
            syncDirectoryBestEffort()

            if (requireProductionValidator && !productionBundleValid(encoderTarget)) {
                if (encoderPublished) encoderTarget.delete()
                if (tokenizerPublished) tokenizerTarget.delete()
                return@synchronized AndroidOfflineSemanticProvisioningResult.ExistingBundleRejected
            }

            AndroidOfflineSemanticProvisioningResult.Provisioned(
                AndroidOfflineSemanticProvisionedBundle(
                    appPrivateRoot = root,
                    encoderFile = encoderTarget
                )
            )
        } catch (failure: Throwable) {
            encoderTemp.delete()
            tokenizerTemp.delete()
            if (encoderPublished) encoderTarget.delete()
            if (tokenizerPublished) tokenizerTarget.delete()
            AndroidOfflineSemanticProvisioningResult.PublicationFailed(
                failure::class.qualifiedName
                    ?: failure::class.simpleName
                    ?: "Throwable"
            )
        }
    }

    private fun prepareRoot(): AndroidOfflineSemanticProvisioningResult? {
        return try {
            if (!root.exists() && !root.mkdirs()) {
                AndroidOfflineSemanticProvisioningResult.PublicationFailed(
                    "java.io.IOException"
                )
            } else if (!root.isDirectory) {
                AndroidOfflineSemanticProvisioningResult.ExistingBundleRejected
            } else {
                null
            }
        } catch (failure: Throwable) {
            AndroidOfflineSemanticProvisioningResult.PublicationFailed(
                failure::class.qualifiedName
                    ?: failure::class.simpleName
                    ?: "Throwable"
            )
        }
    }

    private fun inspectExisting(
        encoderTarget: File,
        tokenizerTarget: File
    ): AndroidOfflineSemanticProvisioningResult? {
        val encoderExists = encoderTarget.exists()
        val tokenizerExists = tokenizerTarget.exists()
        if (!encoderExists && !tokenizerExists) return null
        if (!encoderExists || !tokenizerExists) {
            return AndroidOfflineSemanticProvisioningResult.ExistingBundleRejected
        }

        val valid = if (requireProductionValidator) {
            productionBundleValid(encoderTarget)
        } else {
            exactFileValid(
                encoderTarget,
                spec.encoderBytes,
                spec.encoderSha256
            ) && exactFileValid(
                tokenizerTarget,
                spec.tokenizerBytes,
                spec.tokenizerSha256
            )
        }
        return if (valid) {
            AndroidOfflineSemanticProvisioningResult.AlreadyProvisioned(
                AndroidOfflineSemanticProvisionedBundle(
                    appPrivateRoot = root,
                    encoderFile = encoderTarget
                )
            )
        } else {
            AndroidOfflineSemanticProvisioningResult.ExistingBundleRejected
        }
    }

    private fun productionBundleValid(
        encoderTarget: File
    ): Boolean = when (
        SemanticModelArtifactValidator(
            appPrivateRoot = root,
            trustedIdentity = productionSemanticModelIdentity()
        ).validate(
            candidate = encoderTarget,
            spec = SemanticModelArtifactSpec(productionSemanticModelIdentity())
        )
    ) {
        is SemanticModelArtifactValidationResult.Validated -> true
        else -> false
    }

    private fun writeVerifiedTemp(
        input: InputStream,
        target: File,
        expectedBytes: Long,
        expectedSha256: String
    ): Boolean {
        if (expectedBytes <= 0L || expectedBytes > SemanticModelProfileV01.MAX_ARTIFACT_BYTES) {
            return false
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        return try {
            FileOutputStream(target, false).use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read.toLong()
                    if (total > expectedBytes) {
                        return false
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                output.flush()
                output.fd.sync()
            }

            if (total != expectedBytes) return false
            val actualSha = digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
            actualSha == expectedSha256
        } catch (_: Throwable) {
            false
        } finally {
            if (total != expectedBytes) target.delete()
        }
    }

    private fun exactFileValid(
        file: File,
        expectedBytes: Long,
        expectedSha256: String
    ): Boolean {
        if (!file.isFile || file.length() != expectedBytes) return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            } == expectedSha256
        } catch (_: Throwable) {
            false
        }
    }

    private fun atomicPublish(
        source: File,
        target: File
    ) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        } catch (failure: AtomicMoveNotSupportedException) {
            throw IOException(
                "atomic semantic artifact publication unavailable",
                failure
            )
        }
    }

    private fun syncDirectoryBestEffort() {
        try {
            FileChannel.open(root.toPath(), StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
        } catch (_: IOException) {
            // File contents were already fsynced and publication was atomic.
        } catch (_: UnsupportedOperationException) {
            // Directory fsync is not exposed on every Android/filesystem combination.
        }
    }

    companion object {
        private const val DIRECTORY_NAME = "offline-semantic-provider-v0.1"
        private const val ENCODER_TEMP_NAME = ".encoder.importing"
        private const val TOKENIZER_TEMP_NAME = ".tokenizer.importing"
        private const val BUFFER_BYTES = 64 * 1024
        private val PROCESS_LOCK = Any()

        fun create(
            context: Context
        ): AndroidOfflineSemanticArtifactProvisioner {
            val appFiles = context.applicationContext.filesDir.canonicalFile
            val root = File(appFiles, DIRECTORY_NAME).canonicalFile
            require(root.parentFile == appFiles) {
                "semantic artifact root must be directly inside app-private files"
            }
            return AndroidOfflineSemanticArtifactProvisioner(root)
        }
    }
}

internal data class SemanticArtifactProvisioningSpec(
    val encoderFileName: String,
    val encoderBytes: Long,
    val encoderSha256: String,
    val tokenizerFileName: String,
    val tokenizerBytes: Long,
    val tokenizerSha256: String
)

internal fun productionProvisioningSpec(): SemanticArtifactProvisioningSpec =
    SemanticArtifactProvisioningSpec(
        encoderFileName = SemanticModelProfileV01.ONNX_FILE_NAME,
        encoderBytes = SemanticModelProfileV01.ONNX_SIZE_BYTES,
        encoderSha256 = SemanticModelProfileV01.ONNX_SHA256,
        tokenizerFileName = SemanticModelProfileV01.TOKENIZER_ONNX_FILE_NAME,
        tokenizerBytes = SemanticModelProfileV01.TOKENIZER_ONNX_SIZE_BYTES,
        tokenizerSha256 = SemanticModelProfileV01.TOKENIZER_ONNX_SHA256
    )
