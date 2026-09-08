package pro.liliya.android.runtime

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import pro.liliya.core.protectedmodel.LargeProtectedModelCanonicalDecodeFailure
import pro.liliya.core.protectedmodel.LargeProtectedModelCanonicalDecodeResult
import pro.liliya.core.protectedmodel.LargeProtectedModelCanonicalDecoder
import pro.liliya.core.protectedmodel.LargeProtectedModelEncryptedSegment
import pro.liliya.core.protectedmodel.LargeProtectedModelEncryptedSegmentSource
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageEnvelope
import pro.liliya.core.protectedmodel.LargeProtectedModelResourceBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelSegmentReadResult
import pro.liliya.core.protectedmodel.LargeProtectedModelSegmentSourceFailure
import pro.liliya.core.protectedmodel.LargeProtectedModelSignedManifest

data class ProductProtectedModelLocalImportBudgets(
    val maxContainerBytes: Long,
    val maxSignatureBytes: Int
) {
    init {
        require(maxContainerBytes > 0L) { "max container bytes must be positive" }
        require(maxSignatureBytes > 0) { "max signature bytes must be positive" }
    }
}

enum class ProductProtectedModelLocalImportFailure {
    FILE_REJECTED,
    CONTAINER_UNSUPPORTED,
    RESOURCE_LIMIT_REJECTED,
    MANIFEST_REJECTED,
    SIGNATURE_REJECTED,
    SEGMENT_LAYOUT_MISMATCH,
    TRAILING_DATA,
    INTERNAL_FAILURE
}

sealed interface ProductProtectedModelLocalImportResult {
    data class Ready(
        val envelope: LargeProtectedModelPackageEnvelope,
        val source: LargeProtectedModelEncryptedSegmentSource
    ) : ProductProtectedModelLocalImportResult

    data class Rejected(
        val reason: ProductProtectedModelLocalImportFailure,
        val manifestFailure: LargeProtectedModelCanonicalDecodeFailure? = null
    ) : ProductProtectedModelLocalImportResult
}

internal fun interface ProductProtectedModelSignedManifestDecodePort {
    fun decode(bytes: ByteArray): LargeProtectedModelCanonicalDecodeResult
}

/**
 * Bounded local-file acquisition bridge for one versioned segmented protected-model container.
 *
 * Import != Download.
 * Import != Verification.
 * Import != License.
 * Import != Authority.
 * Import != Model-DEK Resolution.
 * Import != Execution.
 *
 * Successful parsing only reconstructs the exact signed envelope plus an untrusted segment source.
 * Authenticity and decryption remain owned by the existing protected-model pipeline.
 */
class ProductProtectedModelLocalImport internal constructor(
    private val decodePort: ProductProtectedModelSignedManifestDecodePort,
    private val budgets: ProductProtectedModelLocalImportBudgets
) {
    constructor(
        resourceBudgets: LargeProtectedModelResourceBudgets,
        packageBudgets: LargeProtectedModelPackageBudgets,
        budgets: ProductProtectedModelLocalImportBudgets
    ) : this(
        decodePort = ProductProtectedModelSignedManifestDecodePort { bytes ->
            LargeProtectedModelCanonicalDecoder.decodeSignedManifest(
                bytes = bytes,
                resourceBudgets = resourceBudgets,
                packageBudgets = packageBudgets
            )
        },
        budgets = budgets
    )

    fun open(file: File): ProductProtectedModelLocalImportResult {
        val canonical = try {
            file.canonicalFile
        } catch (_: IOException) {
            return rejected(ProductProtectedModelLocalImportFailure.FILE_REJECTED)
        }
        val initialLength = try {
            canonical.length()
        } catch (_: SecurityException) {
            return rejected(ProductProtectedModelLocalImportFailure.FILE_REJECTED)
        }
        if (!canonical.isFile || initialLength <= 0L || initialLength > budgets.maxContainerBytes) {
            return rejected(ProductProtectedModelLocalImportFailure.FILE_REJECTED)
        }
        val initialModified = canonical.lastModified()

        return try {
            RandomAccessFile(canonical, "r").use { input ->
                if (input.readInt() != CONTAINER_MAGIC || input.readInt() != CONTAINER_VERSION) {
                    return rejected(ProductProtectedModelLocalImportFailure.CONTAINER_UNSUPPORTED)
                }

                val manifestBytes = readBoundedBytes(
                    input = input,
                    maxBytes = minOf(budgets.maxContainerBytes, Int.MAX_VALUE.toLong())
                ) ?: return rejected(
                    ProductProtectedModelLocalImportFailure.RESOURCE_LIMIT_REJECTED
                )
                val manifest = try {
                    when (val decoded = decodePort.decode(manifestBytes)) {
                        is LargeProtectedModelCanonicalDecodeResult.Decoded -> decoded.manifest
                        is LargeProtectedModelCanonicalDecodeResult.Rejected ->
                            return ProductProtectedModelLocalImportResult.Rejected(
                                reason = ProductProtectedModelLocalImportFailure.MANIFEST_REJECTED,
                                manifestFailure = decoded.reason
                            )
                    }
                } finally {
                    manifestBytes.fill(0)
                }

                val signature = readBoundedBytes(
                    input = input,
                    maxBytes = budgets.maxSignatureBytes.toLong()
                ) ?: return rejected(ProductProtectedModelLocalImportFailure.SIGNATURE_REJECTED)
                if (signature.isEmpty()) {
                    signature.fill(0)
                    return rejected(ProductProtectedModelLocalImportFailure.SIGNATURE_REJECTED)
                }
                val envelope = try {
                    LargeProtectedModelPackageEnvelope(manifest, signature)
                } catch (_: IllegalArgumentException) {
                    return rejected(ProductProtectedModelLocalImportFailure.SIGNATURE_REJECTED)
                } finally {
                    signature.fill(0)
                }

                val declaredSegmentCount = input.readInt()
                if (declaredSegmentCount != manifest.payload.segmentCount) {
                    return rejected(
                        ProductProtectedModelLocalImportFailure.SEGMENT_LAYOUT_MISMATCH
                    )
                }

                val signedSegments = manifest.payload.segments()
                val locations = ArrayList<SegmentLocation>(declaredSegmentCount)
                repeat(declaredSegmentCount) { expectedIndex ->
                    val index = input.readInt()
                    val bodyLength = input.readInt()
                    val signed = signedSegments[expectedIndex]
                    if (
                        index != expectedIndex ||
                        signed.index != index ||
                        bodyLength <= 0 ||
                        bodyLength.toLong() != signed.ciphertextBodySizeBytes
                    ) {
                        return rejected(
                            ProductProtectedModelLocalImportFailure.SEGMENT_LAYOUT_MISMATCH
                        )
                    }

                    val bodyOffset = input.filePointer
                    if (!skipExact(input, bodyLength.toLong(), initialLength)) {
                        return rejected(
                            ProductProtectedModelLocalImportFailure.SEGMENT_LAYOUT_MISMATCH
                        )
                    }

                    val tagLength = input.readInt()
                    if (tagLength != AUTH_TAG_BYTES) {
                        return rejected(
                            ProductProtectedModelLocalImportFailure.SEGMENT_LAYOUT_MISMATCH
                        )
                    }
                    val tagOffset = input.filePointer
                    if (!skipExact(input, tagLength.toLong(), initialLength)) {
                        return rejected(
                            ProductProtectedModelLocalImportFailure.SEGMENT_LAYOUT_MISMATCH
                        )
                    }

                    locations += SegmentLocation(
                        index = index,
                        bodyOffset = bodyOffset,
                        bodyLength = bodyLength,
                        tagOffset = tagOffset,
                        tagLength = tagLength
                    )
                }

                if (input.filePointer != initialLength) {
                    return rejected(ProductProtectedModelLocalImportFailure.TRAILING_DATA)
                }

                ProductProtectedModelLocalImportResult.Ready(
                    envelope = envelope,
                    source = LocalFileSegmentSource(
                        file = canonical,
                        expectedLength = initialLength,
                        expectedModified = initialModified,
                        locations = locations
                    )
                )
            }
        } catch (_: EOFException) {
            rejected(ProductProtectedModelLocalImportFailure.SEGMENT_LAYOUT_MISMATCH)
        } catch (_: IOException) {
            rejected(ProductProtectedModelLocalImportFailure.INTERNAL_FAILURE)
        } catch (_: SecurityException) {
            rejected(ProductProtectedModelLocalImportFailure.FILE_REJECTED)
        } catch (_: IllegalArgumentException) {
            rejected(ProductProtectedModelLocalImportFailure.SEGMENT_LAYOUT_MISMATCH)
        }
    }

    private fun readBoundedBytes(
        input: RandomAccessFile,
        maxBytes: Long
    ): ByteArray? {
        val length = input.readInt()
        if (length < 0 || length.toLong() > maxBytes) return null
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return bytes
    }

    private fun skipExact(
        input: RandomAccessFile,
        bytes: Long,
        expectedFileLength: Long
    ): Boolean {
        if (bytes < 0L) return false
        val current = input.filePointer
        val target = try {
            Math.addExact(current, bytes)
        } catch (_: ArithmeticException) {
            return false
        }
        if (target > expectedFileLength) return false
        input.seek(target)
        return true
    }

    private fun rejected(
        reason: ProductProtectedModelLocalImportFailure
    ) = ProductProtectedModelLocalImportResult.Rejected(reason)

    private data class SegmentLocation(
        val index: Int,
        val bodyOffset: Long,
        val bodyLength: Int,
        val tagOffset: Long,
        val tagLength: Int
    )

    private class LocalFileSegmentSource(
        private val file: File,
        private val expectedLength: Long,
        private val expectedModified: Long,
        private val locations: List<SegmentLocation>
    ) : LargeProtectedModelEncryptedSegmentSource {
        override val segmentCount: Int
            get() = locations.size

        override fun read(index: Int): LargeProtectedModelSegmentReadResult {
            if (index !in locations.indices) {
                return LargeProtectedModelSegmentReadResult.Missing
            }
            if (!stableFile()) {
                return LargeProtectedModelSegmentReadResult.Rejected(
                    LargeProtectedModelSegmentSourceFailure.REJECTED
                )
            }

            val location = locations[index]
            if (location.index != index) {
                return LargeProtectedModelSegmentReadResult.Rejected(
                    LargeProtectedModelSegmentSourceFailure.REJECTED
                )
            }

            var body: ByteArray? = null
            var tag: ByteArray? = null
            return try {
                RandomAccessFile(file, "r").use { input ->
                    if (input.length() != expectedLength || !stableFile()) {
                        return LargeProtectedModelSegmentReadResult.Rejected(
                            LargeProtectedModelSegmentSourceFailure.REJECTED
                        )
                    }
                    body = ByteArray(location.bodyLength)
                    input.seek(location.bodyOffset)
                    input.readFully(body)
                    tag = ByteArray(location.tagLength)
                    input.seek(location.tagOffset)
                    input.readFully(tag)
                }

                LargeProtectedModelSegmentReadResult.Segment(
                    LargeProtectedModelEncryptedSegment(
                        index = index,
                        ciphertextBody = body!!,
                        authenticationTag = tag!!
                    )
                )
            } catch (_: IOException) {
                LargeProtectedModelSegmentReadResult.Failed(
                    LargeProtectedModelSegmentSourceFailure.PROVIDER_FAILED
                )
            } catch (_: SecurityException) {
                LargeProtectedModelSegmentReadResult.Failed(
                    LargeProtectedModelSegmentSourceFailure.PROVIDER_FAILED
                )
            } finally {
                body?.fill(0)
                tag?.fill(0)
            }
        }

        private fun stableFile(): Boolean =
            try {
                file.isFile &&
                    file.length() == expectedLength &&
                    file.lastModified() == expectedModified
            } catch (_: SecurityException) {
                false
            }

        override fun toString(): String =
            "ProductProtectedModelLocalFileSource(segmentCount=$segmentCount, file=<redacted>)"
    }

    private companion object {
        const val CONTAINER_MAGIC = 0x4C504D31 // LPM1
        const val CONTAINER_VERSION = 1
        const val AUTH_TAG_BYTES = 16
    }
}
