package pro.liliya.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.URL
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingProvider
import pro.liliya.android.devicekey.AndroidActivationDeviceBindingResult
import pro.liliya.core.license.JcaEcdsaP256LicenseSignatureVerifier
import pro.liliya.core.license.LicenseAlgorithm
import pro.liliya.core.license.LicenseCanonicalPayload
import pro.liliya.core.license.LicenseDeviceBindingReferenceFactory
import pro.liliya.core.license.LicenseKeyId
import pro.liliya.core.license.LicenseSignature
import pro.liliya.core.license.LicenseSignedEnvelope
import pro.liliya.core.license.LicenseTrustedKeyResolver
import pro.liliya.core.license.LicenseTrustedVerificationKey
import pro.liliya.core.license.LicenseVerificationResult
import pro.liliya.core.license.LicenseVerifier
import pro.liliya.core.license.LicenseVersion
import pro.liliya.core.licensetransport.LicenseHttpEngineRequest
import pro.liliya.core.licensetransport.LicenseHttpEngineResult
import pro.liliya.core.licensetransport.LicenseHttpTlsTrust
import pro.liliya.core.licensetransport.LicenseTransportCancellation
import pro.liliya.core.licensetransport.UrlConnectionLicenseHttpEngine

@RunWith(AndroidJUnit4::class)
class PhysicalLaptopDeviceRebindWireInstrumentedTest {
    @Test
    fun raw_rebind_response_is_valid_and_license_verifies() {
        val args = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext
        val endpoint = URL(required(args.getString(ARG_ENDPOINT)))
        val caBytes = Base64.getDecoder().decode(required(args.getString(ARG_CA_BASE64)))
        val tlsTrust = try { LicenseHttpTlsTrust.ofCertificate(caBytes) }
        finally { caBytes.fill(0) }

        val binding = assertIs<AndroidActivationDeviceBindingResult.Ready>(
            AndroidActivationDeviceBindingProvider(context).loadOrCreate()
        ).binding
        val expectedBinding = LicenseDeviceBindingReferenceFactory.create(
            installationId = binding.installationId,
            deviceKeyFingerprint = binding.deviceKeyFingerprint
        )

        val codeFile = File(context.filesDir, REBIND_CODE_FILE)
        assertTrue(codeFile.isFile)
        val rebindCode = codeFile.readText(Charsets.UTF_8).trim()
        assertTrue(rebindCode.isNotBlank())
        assertTrue(codeFile.delete())

        val attemptId = ProductionAndroidDeviceRebindAttemptIdentity.loadOrCreate(context)
        val requestJson =
            "{" +
                "\"wireVersion\":1," +
                "\"rebindCode\":\"" + jsonEscape(rebindCode) + "\"," +
                "\"attemptId\":\"" + jsonEscape(attemptId) + "\"," +
                "\"installationId\":\"" + jsonEscape(binding.installationId) + "\"," +
                "\"deviceKeyFingerprint\":\"" + jsonEscape(binding.deviceKeyFingerprint) + "\"" +
                "}"

        val response = assertIs<LicenseHttpEngineResult.Response>(
            UrlConnectionLicenseHttpEngine().execute(
                LicenseHttpEngineRequest(
                    endpoint = URL(
                        endpoint.protocol,
                        endpoint.host,
                        endpoint.port,
                        "/v1/activation/rebind"
                    ),
                    connectTimeoutMillis = 10_000,
                    readTimeoutMillis = 60_000,
                    body = requestJson.encodeToByteArray(),
                    authorizationBearer = null,
                    tlsTrust = tlsTrust
                ),
                LicenseTransportCancellation()
            )
        ).response

        val responseJson = response.body.toString(Charsets.UTF_8)
        val wireVersion = jsonLong(responseJson, "wireVersion")
        val kind = jsonString(responseJson, "kind")
        val subject = jsonString(responseJson, "subject")
        val schemaVersion = jsonLong(responseJson, "schemaVersion")
        val algorithm = jsonString(responseJson, "algorithm")
        val keyReference = jsonString(responseJson, "keyReference")
        val payloadBase64 = jsonString(responseJson, "payloadBase64")
        val signatureBase64 = jsonString(responseJson, "signatureBase64")
        val reason = jsonString(responseJson, "reason")

        println(
            "LILIYA_REBIND_WIRE_META=" +
                "{\"status\":" + response.status +
                ",\"wireVersion\":" + wireVersion +
                ",\"kind\":\"" + kind + "\"" +
                ",\"subjectPresent\":" + subject.isNotBlank() +
                ",\"schemaVersion\":" + schemaVersion +
                ",\"algorithmPresent\":" + algorithm.isNotBlank() +
                ",\"keyReferencePresent\":" + keyReference.isNotBlank() +
                ",\"payloadPresent\":" + payloadBase64.isNotBlank() +
                ",\"signaturePresent\":" + signatureBase64.isNotBlank() +
                ",\"reason\":\"" + reason + "\"}"
        )

        assertTrue(response.status in 200..299)
        assertEquals(1L, wireVersion)
        assertEquals("rebound", kind)
        assertTrue(subject.isNotBlank())

        val envelope = LicenseSignedEnvelope(
            schemaVersion = LicenseVersion(schemaVersion),
            algorithm = LicenseAlgorithm(algorithm),
            signingKeyId = LicenseKeyId(keyReference),
            payload = LicenseCanonicalPayload.of(
                Base64.getDecoder().decode(payloadBase64)
            ),
            signature = LicenseSignature.of(
                Base64.getDecoder().decode(signatureBase64)
            )
        )

        val publicKey = Base64.getDecoder().decode(
            required(args.getString(ARG_ENTITLEMENT_KEY_DER_BASE64))
        )
        val trusted = try {
            LicenseTrustedVerificationKey.of(
                keyId = envelope.signingKeyId,
                algorithm = LicenseAlgorithm(ALGORITHM),
                material = publicKey
            )
        } finally { publicKey.fill(0) }

        val verified = assertIs<LicenseVerificationResult.Verified>(
            LicenseVerifier(
                supportedSchemaVersion = LicenseVersion(1),
                supportedAlgorithms = setOf(LicenseAlgorithm(ALGORITHM)),
                trustedKeys = LicenseTrustedKeyResolver { requested: LicenseKeyId ->
                    trusted.takeIf { it.keyId == requested }
                },
                signatureVerifier = JcaEcdsaP256LicenseSignatureVerifier
            ).verify(envelope)
        )
        assertEquals(expectedBinding, assertNotNull(verified.entitlement.deviceBindingReference))
        assertNull(verified.entitlement.expiresAt)
        assertNull(verified.entitlement.offlineLeaseUntil)
    }

    private fun jsonEscape(value: String): String =
        buildString(value.length + 16) {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }

    private fun jsonString(json: String, key: String): String {
        val pattern = Regex(
            "\\\"" + Regex.escape(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\""
        )
        return pattern.find(json)?.groupValues?.get(1) ?: ""
    }

    private fun jsonLong(json: String, key: String): Long {
        val pattern = Regex(
            "\\\"" + Regex.escape(key) + "\\\"\\s*:\\s*(-?\\d+)"
        )
        return pattern.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
    }

    private fun required(value: String?): String =
        value?.takeIf { it.isNotBlank() }
            ?: error("missing physical rebind wire acceptance argument")

    private companion object {
        const val ARG_ENDPOINT = "rebindEndpoint"
        const val ARG_CA_BASE64 = "rebindCaBase64"
        const val ARG_ENTITLEMENT_KEY_DER_BASE64 = "rebindLicenseKeyDerBase64"
        const val REBIND_CODE_FILE = "physical-device-rebind-code.once"
        const val ALGORITHM = "ECDSA-P256-SHA256"
    }
}
