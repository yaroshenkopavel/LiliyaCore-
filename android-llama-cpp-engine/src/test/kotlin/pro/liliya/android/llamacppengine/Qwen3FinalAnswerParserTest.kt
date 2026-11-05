package pro.liliya.android.llamacppengine

import org.junit.Assert.assertEquals
import org.junit.Test

class Qwen3FinalAnswerParserTest {
    @Test fun extracts_physical_russian_greeting() {
        assertEquals(
            Qwen3FinalAnswerParser.Result.Final("Здравствуйте."),
            Qwen3FinalAnswerParser.parse("<think>\n\n</think>\n\nЗдравствуйте.")
        )
    }

    @Test fun extracts_physical_color() {
        assertEquals(
            Qwen3FinalAnswerParser.Result.Final("Красный."),
            Qwen3FinalAnswerParser.parse("<think>\n\n</think>\n\nКрасный.")
        )
    }

    @Test fun incomplete_thinking_is_not_presented_as_answer() {
        assertEquals(
            Qwen3FinalAnswerParser.Result.IncompleteThinking,
            Qwen3FinalAnswerParser.parse("<think>Okay, let me think")
        )
    }

    @Test fun blank_final_is_not_accepted() {
        assertEquals(
            Qwen3FinalAnswerParser.Result.EmptyFinal,
            Qwen3FinalAnswerParser.parse("<think>private</think> \n ")
        )
    }

    @Test fun ordinary_text_is_preserved_exactly() {
        val answer = "Here is literal <think> markup in documentation."
        assertEquals(Qwen3FinalAnswerParser.Result.Final(answer), Qwen3FinalAnswerParser.parse(answer))
    }

    @Test fun nested_second_thinking_block_is_rejected() {
        val output = "<think>one</think><think>two</think>answer"
        assertEquals(Qwen3FinalAnswerParser.Result.Malformed(output), Qwen3FinalAnswerParser.parse(output))
    }

    @Test fun plain_russian_answer_is_preserved() {
        assertEquals(
            Qwen3FinalAnswerParser.Result.Final("Привет!"),
            Qwen3FinalAnswerParser.parse("Привет!")
        )
    }
}
