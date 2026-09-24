package pro.liliya.app

import android.content.Context
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import pro.liliya.android.runtime.AndroidProductRuntimeLicenseTrustKey

/** Public deployment material. A profile must still supply product policy and runtime owners. */
internal object ProductionAndroidDeploymentTrustAssets {
    /** Reviewed server signing reference; distinct from the OpenBao Transit key name. */
    const val LICENSE_SIGNING_KEY_REFERENCE = "liliya-prod-license-signing-v1"
    private const val CA_SHA256 =
        "E0CC1713E01BA397AB28ED1041AE51D1B56A104178ADBE379393A534D86D9F91"
    private const val KEY_DER_SHA256 =
        "B32577A65F496A5F761F77A5C53C86821AF581E0E6C45FF7B168A4FD8ED073EF"

    fun licensingCa(context: Context): ByteArray {
        val bytes = context.assets.open("licensing/licensing-ca.crt").use { it.readBytes() }
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(bytes.inputStream()) as X509Certificate
        require(certificate.basicConstraints >= 0)
        require(sha256(certificate.encoded) == CA_SHA256)
        return bytes
    }

    fun licenseSigningKeyV1(context: Context): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(licenseSigningKeyV1Der(context))
        )

    fun licenseTrustKeyV1(context: Context):
        AndroidProductRuntimeLicenseTrustKey = AndroidProductRuntimeLicenseTrustKey(
            LICENSE_SIGNING_KEY_REFERENCE,
            licenseSigningKeyV1Der(context)
        )

    private fun licenseSigningKeyV1Der(context: Context): ByteArray {
        val pem = context.assets.open("licensing/license-signing-v1-public.pem")
            .bufferedReader(Charsets.US_ASCII).use { it.readText() }
        val der = Base64.getDecoder().decode(
            pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .filterNot(Char::isWhitespace)
        )
        require(sha256(der) == KEY_DER_SHA256)
        return der
    }

    private fun sha256(input: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(input).joinToString("") { "%02X".format(it) }
}
