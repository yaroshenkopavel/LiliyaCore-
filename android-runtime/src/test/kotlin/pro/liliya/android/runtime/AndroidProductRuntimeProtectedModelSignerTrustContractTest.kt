package pro.liliya.android.runtime

import java.security.KeyPairGenerator
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.Test
import pro.liliya.core.protectedmodel.ProtectedModelSignatureAlgorithm
import pro.liliya.core.protectedmodel.ProtectedModelSignerId

class AndroidProductRuntimeProtectedModelSignerTrustContractTest {
    @Test
    fun exact_ed25519_key_is_resolved_only_for_exact_signer() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val result = AndroidProductRuntimeProtectedModelSignerTrust.create(
            listOf(
                AndroidProductRuntimeProtectedModelSignerTrustKey(
                    signerId = "primary-model-signer",
                    material = pair.public.encoded
                )
            )
        )

        val ready = assertIs<AndroidProductRuntimeProtectedModelSignerTrustResult.Ready>(result)
        val resolved = requireNotNull(
            ready.resolver.resolve(
                ProtectedModelSignerId("primary-model-signer"),
                ProtectedModelSignatureAlgorithm.ED25519
            )
        )
        assertContentEquals(pair.public.encoded, resolved.encoded)
        assertNull(
            ready.resolver.resolve(
                ProtectedModelSignerId("unknown"),
                ProtectedModelSignatureAlgorithm.ED25519
            )
        )
    }

    @Test
    fun duplicate_signer_ids_are_rejected() {
        val first = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val second = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        val result = AndroidProductRuntimeProtectedModelSignerTrust.create(
            listOf(
                AndroidProductRuntimeProtectedModelSignerTrustKey(
                    "same",
                    first.public.encoded
                ),
                AndroidProductRuntimeProtectedModelSignerTrustKey(
                    "same",
                    second.public.encoded
                )
            )
        )

        assertIs<AndroidProductRuntimeProtectedModelSignerTrustResult.Rejected>(result)
    }

    @Test
    fun malformed_public_key_is_rejected() {
        val result = AndroidProductRuntimeProtectedModelSignerTrust.create(
            listOf(
                AndroidProductRuntimeProtectedModelSignerTrustKey(
                    "primary",
                    byteArrayOf(1, 2, 3, 4)
                )
            )
        )

        assertIs<AndroidProductRuntimeProtectedModelSignerTrustResult.Rejected>(result)
    }

    @Test
    fun caller_mutation_does_not_change_trusted_key_material() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val bytes = pair.public.encoded.copyOf()
        val input = AndroidProductRuntimeProtectedModelSignerTrustKey(
            "primary",
            bytes
        )
        bytes.fill(0)

        val result = AndroidProductRuntimeProtectedModelSignerTrust.create(listOf(input))

        val ready = assertIs<AndroidProductRuntimeProtectedModelSignerTrustResult.Ready>(result)
        val resolved = requireNotNull(
            ready.resolver.resolve(
                ProtectedModelSignerId("primary"),
                ProtectedModelSignatureAlgorithm.ED25519
            )
        )
        assertContentEquals(pair.public.encoded, resolved.encoded)
    }
}
