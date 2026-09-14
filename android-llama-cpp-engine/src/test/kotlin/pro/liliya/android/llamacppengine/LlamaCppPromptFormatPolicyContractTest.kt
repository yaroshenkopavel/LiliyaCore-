package pro.liliya.android.llamacppengine

import kotlin.test.assertEquals
import org.junit.Test

class LlamaCppPromptFormatPolicyContractTest {

    @Test
    fun existing_policy_construction_remains_raw_by_default() {
        assertEquals(LlamaCppPromptFormatPolicy.RAW, policy().promptFormatPolicy)
    }

    @Test
    fun native_codes_are_explicit_and_stable() {
        assertEquals(0, LlamaCppPromptFormatPolicy.RAW.nativeCode)
        assertEquals(1, LlamaCppPromptFormatPolicy.MODEL_DEFAULT_CHAT_TEMPLATE.nativeCode)
    }

    @Test
    fun template_mode_requires_explicit_policy_selection() {
        assertEquals(
            LlamaCppPromptFormatPolicy.MODEL_DEFAULT_CHAT_TEMPLATE,
            policy().copy(
                promptFormatPolicy = LlamaCppPromptFormatPolicy.MODEL_DEFAULT_CHAT_TEMPLATE
            ).promptFormatPolicy
        )
    }

    private fun policy() = LlamaCppEnginePolicy(
        contextTokens = 64,
        maxPromptTokens = 32,
        maxGeneratedTokens = 16,
        batchTokens = 16,
        microBatchTokens = 8,
        threadCount = 2,
        maxPromptChars = 128,
        maxPromptUtf8Bytes = 256,
        maxOutputChars = 64,
        maxOutputUtf8Bytes = 256,
        useMmap = true
    )
}
