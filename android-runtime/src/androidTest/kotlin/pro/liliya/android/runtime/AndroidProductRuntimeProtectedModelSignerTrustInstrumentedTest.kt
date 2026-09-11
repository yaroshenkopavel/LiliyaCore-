package pro.liliya.android.runtime

import java.security.KeyPairGenerator
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import pro.liliya.core.protectedmodel.ProtectedModelSignatureAlgorithm
import pro.liliya.core.protectedmodel.ProtectedModelSignerId

@RunWith(AndroidJUnit4::class)
class AndroidProductRuntimeProtectedModelSignerTrustInstrumentedTest {
    @Test
    fun portable_ed25519_x509_signer_is_accepted_on_android() {
        val fixtureProvider = BouncyCastleProvider()
        val pair = KeyPairGenerator.getInstance("Ed25519", fixtureProvider).generateKeyPair()

        val result = AndroidProductRuntimeProtectedModelSignerTrust.create(
            listOf(
                AndroidProductRuntimeProtectedModelSignerTrustKey(
                    signerId = "android-portable-model-signer",
                    material = pair.public.encoded
                )
            )
        )

        val ready = assertIs<AndroidProductRuntimeProtectedModelSignerTrustResult.Ready>(result)
        val resolved = requireNotNull(
            ready.resolver.resolve(
                ProtectedModelSignerId("android-portable-model-signer"),
                ProtectedModelSignatureAlgorithm.ED25519
            )
        )
        assertContentEquals(pair.public.encoded, resolved.encoded)
    }
}
