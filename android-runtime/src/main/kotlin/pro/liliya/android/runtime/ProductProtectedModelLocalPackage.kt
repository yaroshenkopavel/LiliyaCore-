package pro.liliya.android.runtime

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import pro.liliya.core.protectedmodel.LargeProtectedModelCanonicalDecodeFailure
import pro.liliya.core.protectedmodel.LargeProtectedModelEncryptedSegment
import pro.liliya.core.protectedmodel.LargeProtectedModelEncryptedSegmentSource
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelPackageEnvelope
import pro.liliya.core.protectedmodel.LargeProtectedModelResourceBudgets
import pro.liliya.core.protectedmodel.LargeProtectedModelSegmentReadResult
import pro.liliya.core.protectedmodel.LargeProtectedModelSegmentSourceFailure
import pro.liliya.core.protectedmodel.LargeProtectedModelSignedManifestCanonicalDecodeResult
import pro.liliya.core.protectedmodel.LargeProtectedModelSignedManifestCanonicalDecoder
import pro.liliya.core.protectedmodel.SEGMENT_AUTHENTICATION_TAG_SIZE_BYTES

data class ProductProtectedModelLocalPackageBudgets(
    val maxContainerBytes: Long,
    val maxSignatureBytes: Int
) {
    init {
        require(maxContainerBytes > 0L) { "local protected-model container budget must be positive" }
        require(maxSignatureBytes > 0) { "local protected-model signature budget must be positive" }
    }
}

enum class ProductProtectedModelLocalPackageFailure {
    SOURCE_REJECTED,
    RESOURCE_LIMIT_REJECTED,
    UNSUPPORTED_VERSION,
    MANIFEST_REJECTED,
    SEGMENT_STRUCTURE_REJECTED,
    TRAILING_DATA_REJECTED,
    SOURCE_CHANGED,
    IO_FAILED,
    INTERNAL_FAILURE
}

sealed interface ProductProtectedModelLocalPackageOpenResult {
    data class Opened(
        val envelope: LargeProtectedModelPackageEnvelope,
        val source: LargeProtectedModelEncryptedSegmentSource
    ) : ProductProtectedModelLocalPackageOpenResult

    data class Rejected(
        val reason: ProductProtectedModelLocalPackageFailure
    ) : ProductProtectedModelLocalPackageOpenResult
}

/**
 * Opens one explicit local segmented protected-model container without granting trust.
 *
 * Import != Download. Parsed != Verified. Import != License/Authority/DEK resolution/Execution.
 */
object ProductProtectedModelLocalPackage {
    fun open(
        file: File,
        manifestBudgets: LargeProtectedModelResourceBudgets,
        packageBudgets: LargeProtectedModelPackageBudgets,
        containerBudgets: ProductProtectedModelLocalPackageBudgets
    ): ProductProtectedModelLocalPackageOpenResult {
        val canonical = try {
            file.canonicalFile
        } catch (_: IOException) {
            return rejected(ProductProtectedModelLocalPackageFailure.SOURCE_REJECTED)
        } catch (_: SecurityException) {
            return rejected(ProductProtectedModelLocalPackageFailure.SOURCE_REJECTED)
        }
        val initialState = try {
            Triple(canonical.isFile, canonical.length(), canonical.lastModified())
        } catch (_: SecurityException) {
            return rejected(ProductProtectedModelLocalPackageFailure.SOURCE_REJECTED)
        }
        if (!initialState.first) {
            return rejected(ProductProtectedModelLocalPackageFailure.SOURCE_REJECTED)
        }
        val initialLength = initialState.second
        if (initialLength <= 0L || initialLength > containerBudgets.maxContainerBytes) {
            return rejected(ProductProtectedModelLocalPackageFailure.RESOURCE_LIMIT_REJECTED)
        }
        val initialModified = initialState.third

        return try {
            RandomAccessFile(canonical, "r").use { input ->
                if (input.readInt() != CONTAINER_MAGIC) {
                    return rejected(ProductProtectedModelLocalPackageFailure.SOURCE_REJECTED)
                }
                if (input.readInt() != CONTAINER_VERSION) {
                    return rejected(ProductProtectedModelLocalPackageFailure.UNSUPPORTED_VERSION)
                }

                val manifestBytes = readBoundedBytes(
                    input,
                    packageBudgets.maxCanonicalSignedManifestBytes
                ) ?: return rejected(ProductProtectedModelLocalPackageFailure.RESOURCE_LIMIT_REJECTED)
                val decoded = try {
                    LargeProtectedModelSignedManifestCanonicalDecoder.decode(
                        manifestBytes,
                        manifestBudgets,
                        packageBudgets
                    )
                } finally {
                    manifestBytes.fill(0)
                }
                val manifest = when (decoded) {
                    is LargeProtectedModelSignedManifestCanonicalDecodeResult.Decoded ->
                        decoded.manifest
                    is LargeProtectedModelSignedManifestCanonicalDecodeResult.Rejected ->
                        return rejected(mapManifestFailure(decoded.reason))
                }

                val signature = readBoundedBytes(input, containerBudgets.maxSignatureBytes.toLong())
                    ?: return rejected(ProductProtectedModelLocalPackageFailure.RESOURCE_LIMIT_REJECTED)
                if (signature.isEmpty()) {
                    signature.fill(0)
                    return rejected(ProductProtectedModelLocalPackageFailure.SOURCE_REJECTED)
                }
                val envelope = try {
                    LargeProtectedModelPackageEnvelope(manifest, signature)
                } catch (_: IllegalArgumentException) {
                    signature.fill(0)
                    return rejected(ProductProtectedModelLocalPackageFailure.SOURCE_REJECTED)
                } finally {
                    signature.fill(0)
                }

                val declaredCount = input.readInt()
                if (declaredCount != manifest.payload.segmentCount) {
                    return rejected(ProductProtectedModelLocalPackageFailure.SEGMENT_STRUCTURE_REJECTED)
                }

                val offsets = ArrayList<SegmentOffset>(declaredCount)
                val signed = manifest.payload.segments()
                repeat(declaredCount) { position ->
                    val expected = signed[position]
                    val index = input.readInt()
                    val ciphertextBodyBytes = input.readLong()
                    val tagBytes = input.readInt()
                    if (
                        index != expected.index ||
                        ciphertextBodyBytes != expected.ciphertextBodySizeBytes ||
                        ciphertextBodyBytes <= 0L ||
                        ciphertextBodyBytes > manifestBudgets.maxSegmentCiphertextBodyBytes ||
                        ciphertextBodyBytes > Int.MAX_VALUE.toLong() ||
                        tagBytes != SEGMENT_AUTHENTICATION_TAG_SIZE_BYTES
                    ) {
                        return rejected(
                            ProductProtectedModelLocalPackageFailure.SEGMENT_STRUCTURE_REJECTED
                        )
                    }
                    val bodyOffset = input.filePointer
                    val tagOffset = try {
                        Math.addExact(bodyOffset, ciphertextBodyBytes)
                    } catch (_: ArithmeticException) {
                        return rejected(
                            ProductProtectedModelLocalPackageFailure.RESOURCE_LIMIT_REJECTED
                        )
                    }
                    val nextOffset = try {
                        Math.addExact(tagOffset, tagBytes.toLong())
                    } catch (_: ArithmeticException) {
                        return rejected(
                            ProductProtectedModelLocalPackageFailure.RESOURCE_LIMIT_REJECTED
                        )
                    }
                    if (nextOffset > initialLength) {
                        return rejected(ProductProtectedModelLocalPackageFailure.SOURCE_REJECTED)
                    }
                    offsets += SegmentOffset(index, bodyOffset, ciphertextBodyBytes.toInt(), tagOffset)
                    input.seek(nextOffset)
                }

                if (input.filePointer != initialLength) {
                    return rejected(ProductProtectedModelLocalPackageFailure.TRAILING_DATA_REJECTED)
                }
                if (!stableFile(canonical, initialLength, initialModified)) {
                    return rejected(ProductProtectedModelLocalPackageFailure.SOURCE_CHANGED)
                }

                ProductProtectedModelLocalPackageOpenResult.Opened(
                    envelope = envelope,
                    source = FileSegmentSource(
                        file = canonical,
                        expectedLength = initialLength,
                        expectedModified = initialModified,
                        offsets = offsets
                    )
                )
            }
        } catch (_: EOFException) {
            rejected(ProductProtectedModelLocalPackageFailure.SOURCE_REJECTED)
        } catch (_: IOException) {
            rejected(ProductProtectedModelLocalPackageFailure.IO_FAILED)
        } catch (_: Exception) {
            rejected(ProductProtectedModelLocalPackageFailure.INTERNAL_FAILURE)
        }
    }

    private fun mapManifestFailure(
        reason: LargeProtectedModelCanonicalDecodeFailure
    ): ProductProtectedModelLocalPackageFailure = when (reason) {
        LargeProtectedModelCanonicalDecodeFailure.UNSUPPORTED_VERSION ->
            ProductProtectedModelLocalPackageFailure.UNSUPPORTED_VERSION
        LargeProtectedModelCanonicalDecodeFailure.RESOURCE_LIMIT_REJECTED ->
            ProductProtectedModelLocalPackageFailure.RESOURCE_LIMIT_REJECTED
        LargeProtectedModelCanonicalDecodeFailure.TRAILING_DATA ->
            ProductProtectedModelLocalPackageFailure.TRAILING_DATA_REJECTED
        LargeProtectedModelCanonicalDecodeFailure.MALFORMED,
        LargeProtectedModelCanonicalDecodeFailure.STRUCTURAL_REJECTED ->
            ProductProtectedModelLocalPackageFailure.MANIFEST_REJECTED
    }

    private fun readBoundedBytes(input: RandomAccessFile, maxBytes: Long): ByteArray? {
        val size = input.readInt()
        if (size < 0 || size.toLong() > maxBytes) return null
        return ByteArray(size).also { input.readFully(it) }
    }

    private fun stableFile(file: File, expectedLength: Long, expectedModified: Long): Boolean =
        try {
            file.isFile &&
                file.length() == expectedLength &&
                file.lastModified() == expectedModified
        } catch (_: SecurityException) {
            false
        }

    private fun rejected(reason: ProductProtectedModelLocalPackageFailure) =
        ProductProtectedModelLocalPackageOpenResult.Rejected(reason)

    private data class SegmentOffset(
        val index: Int,
        val bodyOffset: Long,
        val bodyBytes: Int,
        val tagOffset: Long
    )

    private class FileSegmentSource(
        private val file: File,
        private val expectedLength: Long,
        private val expectedModified: Long,
        private val offsets: List<SegmentOffset>
    ) : LargeProtectedModelEncryptedSegmentSource {
        override val segmentCount: Int
            get() = offsets.size

        override fun read(index: Int): LargeProtectedModelSegmentReadResult {
            val offset = offsets.getOrNull(index)
                ?: return LargeProtectedModelSegmentReadResult.Missing
            if (offset.index != index) {
                return LargeProtectedModelSegmentReadResult.Rejected(
                    LargeProtectedModelSegmentSourceFailure.REJECTED
                )
            }
            if (!stableFile(file, expectedLength, expectedModified)) {
                return LargeProtectedModelSegmentReadResult.Rejected(
                    LargeProtectedModelSegmentSourceFailure.REJECTED
                )
            }

            val body = ByteArray(offset.bodyBytes)
            val tag = ByteArray(SEGMENT_AUTHENTICATION_TAG_SIZE_BYTES)
            return try {
                RandomAccessFile(file, "r").use { input ->
                    if (input.length() != expectedLength) {
                        return LargeProtectedModelSegmentReadResult.Rejected(
                            LargeProtectedModelSegmentSourceFailure.REJECTED
                        )
                    }
                    input.seek(offset.bodyOffset)
                    input.readFully(body)
                    input.seek(offset.tagOffset)
                    input.readFully(tag)
                }
                if (!stableFile(file, expectedLength, expectedModified)) {
                    return LargeProtectedModelSegmentReadResult.Rejected(
                        LargeProtectedModelSegmentSourceFailure.REJECTED
                    )
                }
                LargeProtectedModelSegmentReadResult.Segment(
                    LargeProtectedModelEncryptedSegment(index, body, tag)
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
                body.fill(0)
                tag.fill(0)
            }
        }

        override fun toString(): String =
            "ProductProtectedModelLocalPackageSource(file=<redacted>, segmentCount=$segmentCount)"
    }

    private const val CONTAINER_MAGIC = 0x4C504D31 // LPM1
    private const val CONTAINER_VERSION = 1
}
