package pro.liliya.core.protectedmodel

/**
 * Narrow public packaging facade over the frozen protected-model canonical encodings.
 *
 * This exists so an offline packaging tool can reuse the exact same bytes consumed/verified by
 * Core without copying internal codecs into another module.
 *
 * Packaging Primitives != Package Verification.
 * Packaging Primitives != Signing Key Ownership.
 * Packaging Primitives != Model-DEK Provisioning.
 */
object LargeProtectedModelPackagingPrimitives {
    fun canonicalSignedManifest(
        manifest: LargeProtectedModelSignedManifest
    ): ByteArray =
        LargeProtectedModelPackageCanonicalCodec.encode(manifest)

    fun signatureInput(
        manifest: LargeProtectedModelSignedManifest
    ): ByteArray =
        LargeProtectedModelPackageCanonicalCodec.signatureInput(manifest)

    fun segmentAad(
        manifest: LargeProtectedModelSignedManifest,
        segment: LargeProtectedModelSegment
    ): ByteArray =
        LargeProtectedModelSegmentAadCodec.encode(manifest, segment)
}
