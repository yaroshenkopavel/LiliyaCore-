package pro.liliya.core.cognitive

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CognitiveStructuredResponseParserContractTest {
    private val budgets = CognitiveStructuredResponseBudgets.from(CognitiveRuntimeLimits())
    private val parser = CognitiveStructuredResponseParser(budgets)

    @Test
    fun valid_v1_response_parses_exact_order_and_decodes_declared_escapes() {
        val result = assertIs<CognitiveStructuredResponseParseResult.Parsed>(
            parser.parse(validResponse())
        )
        val response = result.response

        assertEquals("goal=private", response.planningGoal)
        assertEquals(listOf("step one", "step\ntwo"), response.planningSteps)
        assertEquals(listOf("premise one", "premise\ttwo"), response.reasoningPremises)
        assertEquals("analysis\\private", response.reasoningAnalysis)
        assertEquals("conclusion\rprivate", response.reasoningConclusion)
        assertEquals(listOf("option one", "option=two"), response.decisionOptions)
        assertEquals(1, response.selectedDecisionOptionIndex)
        assertEquals("rationale", response.decisionRationale)
        assertEquals("result", response.resultContent)
        assertEquals("reflection", response.reflectionContent)
        assertEquals("learning", response.learningProposal)

        val rendered = response.toString()
        assertFalse(rendered.contains("goal=private"))
        assertFalse(rendered.contains("analysis\\private"))
        assertFalse(rendered.contains("learning"))
        assertTrue(rendered.contains("redacted"))
    }

    @Test
    fun exactly_one_terminal_lf_is_allowed_but_more_trailing_data_is_rejected() {
        assertIs<CognitiveStructuredResponseParseResult.Parsed>(
            parser.parse(validResponse() + "\n")
        )

        assertEquals(
            CognitiveStructuredResponseFailure.TRAILING_DATA_REJECTED,
            rejected(validResponse() + "\n\n")
        )
        assertEquals(
            CognitiveStructuredResponseFailure.TRAILING_DATA_REJECTED,
            rejected(validResponse() + "\nextra")
        )
    }

    @Test
    fun wrong_version_missing_or_out_of_order_fields_fail_closed() {
        assertEquals(
            CognitiveStructuredResponseFailure.VERSION_REJECTED,
            rejected(validResponse().replaceFirst(
                CognitiveStructuredResponseProtocol.VERSION,
                "LILIYA_COGNITIVE_RESPONSE_V2"
            ))
        )
        assertEquals(
            CognitiveStructuredResponseFailure.STRUCTURE_REJECTED,
            rejected(validResponse().replace("PLANNING_GOAL=goal=private\n", ""))
        )
        assertEquals(
            CognitiveStructuredResponseFailure.STRUCTURE_REJECTED,
            rejected(validResponse().replace(
                "REASONING_ANALYSIS=analysis\\\\private\nREASONING_CONCLUSION=conclusion\\rprivate",
                "REASONING_CONCLUSION=conclusion\\rprivate\nREASONING_ANALYSIS=analysis\\\\private"
            ))
        )
    }

    @Test
    fun list_count_integer_overflow_and_selected_index_are_strict() {
        assertEquals(
            CognitiveStructuredResponseFailure.COUNT_REJECTED,
            rejected(validResponse().replace("PLANNING_STEP_COUNT=2", "PLANNING_STEP_COUNT=0"))
        )
        assertEquals(
            CognitiveStructuredResponseFailure.COUNT_REJECTED,
            rejected(validResponse().replace(
                "PLANNING_STEP_COUNT=2",
                "PLANNING_STEP_COUNT=999999999999999999999"
            ))
        )
        assertEquals(
            CognitiveStructuredResponseFailure.COUNT_REJECTED,
            rejected(validResponse().replace("DECISION_SELECTED_INDEX=1", "DECISION_SELECTED_INDEX=2"))
        )
    }

    @Test
    fun unknown_dangling_escape_and_raw_control_are_rejected() {
        assertEquals(
            CognitiveStructuredResponseFailure.ESCAPE_REJECTED,
            rejected(validResponse().replace("RESULT_CONTENT=result", "RESULT_CONTENT=bad\\q"))
        )
        assertEquals(
            CognitiveStructuredResponseFailure.ESCAPE_REJECTED,
            rejected(validResponse().replace("RESULT_CONTENT=result", "RESULT_CONTENT=bad\\"))
        )
        assertEquals(
            CognitiveStructuredResponseFailure.CONTROL_CHARACTER_REJECTED,
            rejected(validResponse().replace("RESULT_CONTENT=result", "RESULT_CONTENT=bad\tvalue"))
        )
    }

    @Test
    fun decoded_field_and_total_output_limits_are_independently_enforced() {
        val smallFieldBudgets = budgets.copy(maxResultChars = 3)
        assertEquals(
            CognitiveStructuredResponseFailure.FIELD_LIMIT_REJECTED,
            rejected(validResponse(), CognitiveStructuredResponseParser(smallFieldBudgets))
        )

        val output = validResponse()
        val smallOutputBudgets = budgets.copy(maxOutputChars = output.length - 1)
        assertEquals(
            CognitiveStructuredResponseFailure.OUTPUT_LIMIT_REJECTED,
            rejected(output, CognitiveStructuredResponseParser(smallOutputBudgets))
        )
    }

    @Test
    fun parser_instance_is_reusable_concurrently_without_shared_failure_state() {
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val first = AtomicReference<CognitiveStructuredResponseParseResult>()
        val second = AtomicReference<CognitiveStructuredResponseParseResult>()

        thread(start = true) {
            start.await()
            first.set(parser.parse(validResponse()))
            done.countDown()
        }
        thread(start = true) {
            start.await()
            second.set(parser.parse(validResponse().replace("RESULT_CONTENT=result", "RESULT_CONTENT=bad\\q")))
            done.countDown()
        }

        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertIs<CognitiveStructuredResponseParseResult.Parsed>(first.get())
        assertEquals(
            CognitiveStructuredResponseFailure.ESCAPE_REJECTED,
            assertIs<CognitiveStructuredResponseParseResult.Rejected>(second.get()).reason
        )
    }

    @Test
    fun protocol_exposes_nonzero_minimum_valid_envelope_size() {
        assertTrue(CognitiveStructuredResponseProtocol.minimumEnvelopeChars > 0)
        assertTrue(CognitiveStructuredResponseProtocol.minimumEnvelopeChars < budgets.maxOutputChars)
    }

    private fun rejected(
        output: String,
        parser: CognitiveStructuredResponseParser = this.parser
    ): CognitiveStructuredResponseFailure =
        assertIs<CognitiveStructuredResponseParseResult.Rejected>(parser.parse(output)).reason

    private fun validResponse(): String = listOf(
        CognitiveStructuredResponseProtocol.VERSION,
        "PLANNING_GOAL=goal=private",
        "PLANNING_STEP_COUNT=2",
        "PLANNING_STEP=step one",
        "PLANNING_STEP=step\\ntwo",
        "REASONING_PREMISE_COUNT=2",
        "REASONING_PREMISE=premise one",
        "REASONING_PREMISE=premise\\ttwo",
        "REASONING_ANALYSIS=analysis\\\\private",
        "REASONING_CONCLUSION=conclusion\\rprivate",
        "DECISION_OPTION_COUNT=2",
        "DECISION_OPTION=option one",
        "DECISION_OPTION=option=two",
        "DECISION_SELECTED_INDEX=1",
        "DECISION_RATIONALE=rationale",
        "RESULT_CONTENT=result",
        "REFLECTION_CONTENT=reflection",
        "LEARNING_PROPOSAL=learning",
        "END"
    ).joinToString("\n")
}
