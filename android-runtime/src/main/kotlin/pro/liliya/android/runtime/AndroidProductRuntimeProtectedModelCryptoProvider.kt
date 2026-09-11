package pro.liliya.android.runtime

import java.security.Provider
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Product-local Ed25519 provider used only at the protected-model signer boundary.
 *
 * The provider is deliberately not installed into the process-wide Security registry. Parsing the
 * portable X.509 signer key and verifying the package signature must use the same explicit provider
 * on Android, where the platform JCA surface does not expose an Ed25519 KeyFactory contract.
 */
internal object AndroidProductRuntimeProtectedModelCryptoProvider {
    val provider: Provider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BouncyCastleProvider()
    }
}
