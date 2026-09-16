package dev.ccarf.d1.multiagentorchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.RefusalStopDetails;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;

import dev.ccarf.d1.multiagentorchestration.IterativeRefinementLoop.CoverageEntry;
import dev.ccarf.d1.multiagentorchestration.IterativeRefinementLoop.CoverageLevel;

class IterativeRefinementLoopTest {

    private static final String FIVE_SUBTOPICS = "Solar\nWind\nGeothermal\nTidal\nBiomass";
    private static final String ALL_WELL_COVERED =
            "Solar :: WELL_COVERED :: covered\n"
                    + "Wind :: WELL_COVERED :: covered\n"
                    + "Geothermal :: WELL_COVERED :: covered\n"
                    + "Tidal :: WELL_COVERED :: covered\n"
                    + "Biomass :: WELL_COVERED :: covered";
    private static final String GEOTHERMAL_MISSING =
            "Solar :: WELL_COVERED :: covered\n"
                    + "Wind :: WELL_COVERED :: covered\n"
                    + "Geothermal :: MISSING :: not mentioned\n"
                    + "Tidal :: WELL_COVERED :: covered\n"
                    + "Biomass :: WELL_COVERED :: covered";

    @Test
    void stopsAfterFirstAggregationWhenNoGapsExist() {
        StubMessageService messageService = new StubMessageService(
                messageWithText(StopReason.END_TURN, FIVE_SUBTOPICS),
                messageWithText(StopReason.END_TURN, "web findings"),
                messageWithText(StopReason.END_TURN, "doc findings"),
                messageWithText(StopReason.END_TURN, ALL_WELL_COVERED));
        AnthropicClient client = new StubAnthropicClient(messageService);

        List<CoverageEntry> coverage = IterativeRefinementLoop.researchWithRefinement(client,
                "Renewable energy");

        assertEquals(5, coverage.size());
        assertTrue(coverage.stream().allMatch(entry -> entry.level() == CoverageLevel.WELL_COVERED));
        assertEquals(4, messageService.requests.size());
    }

    @Test
    void resolvesGapsAfterOneRefinementIteration() {
        StubMessageService messageService = new StubMessageService(
                messageWithText(StopReason.END_TURN, FIVE_SUBTOPICS),
                messageWithText(StopReason.END_TURN, "web findings"),
                messageWithText(StopReason.END_TURN, "doc findings"),
                messageWithText(StopReason.END_TURN, GEOTHERMAL_MISSING),
                messageWithText(StopReason.END_TURN, "geothermal web follow-up findings"),
                messageWithText(StopReason.END_TURN, "geothermal doc follow-up findings"),
                messageWithText(StopReason.END_TURN, ALL_WELL_COVERED));
        AnthropicClient client = new StubAnthropicClient(messageService);

        List<CoverageEntry> coverage = IterativeRefinementLoop.researchWithRefinement(client,
                "Renewable energy");

        assertTrue(coverage.stream().allMatch(entry -> entry.level() == CoverageLevel.WELL_COVERED));
        assertEquals(7, messageService.requests.size());
    }

    @Test
    void sendsTargetedFollowUpOnlyForGapSubtopics() {
        StubMessageService messageService = new StubMessageService(
                messageWithText(StopReason.END_TURN, FIVE_SUBTOPICS),
                messageWithText(StopReason.END_TURN, "web findings"),
                messageWithText(StopReason.END_TURN, "doc findings"),
                messageWithText(StopReason.END_TURN, GEOTHERMAL_MISSING),
                messageWithText(StopReason.END_TURN, "geothermal web follow-up findings"),
                messageWithText(StopReason.END_TURN, "geothermal doc follow-up findings"),
                messageWithText(StopReason.END_TURN, ALL_WELL_COVERED));
        AnthropicClient client = new StubAnthropicClient(messageService);

        IterativeRefinementLoop.researchWithRefinement(client, "Renewable energy");

        MessageCreateParams followUpWebSearchRequest = messageService.requests.get(4);
        String followUpPrompt =
                followUpWebSearchRequest.messages().get(0).content().string().orElseThrow();
        assertTrue(followUpPrompt.contains("Geothermal"));
        assertFalse(followUpPrompt.contains("- Solar"));
        assertTrue(followUpPrompt.contains("web findings"));
    }

    @Test
    void stopsAtMaxIterationsWithoutThrowingWhenGapsPersist() {
        StubMessageService messageService = new StubMessageService(
                messageWithText(StopReason.END_TURN, FIVE_SUBTOPICS),
                messageWithText(StopReason.END_TURN, "web findings"),
                messageWithText(StopReason.END_TURN, "doc findings"),
                messageWithText(StopReason.END_TURN, GEOTHERMAL_MISSING),
                messageWithText(StopReason.END_TURN, "web follow-up 1"),
                messageWithText(StopReason.END_TURN, "doc follow-up 1"),
                messageWithText(StopReason.END_TURN, GEOTHERMAL_MISSING),
                messageWithText(StopReason.END_TURN, "web follow-up 2"),
                messageWithText(StopReason.END_TURN, "doc follow-up 2"),
                messageWithText(StopReason.END_TURN, GEOTHERMAL_MISSING),
                messageWithText(StopReason.END_TURN, "web follow-up 3"),
                messageWithText(StopReason.END_TURN, "doc follow-up 3"),
                messageWithText(StopReason.END_TURN, GEOTHERMAL_MISSING));
        AnthropicClient client = new StubAnthropicClient(messageService);

        List<CoverageEntry> coverage = IterativeRefinementLoop.researchWithRefinement(client,
                "Renewable energy");

        assertTrue(coverage.stream().anyMatch(entry -> entry.level() == CoverageLevel.MISSING));
        assertEquals(13, messageService.requests.size());
    }

    @Test
    void throwsWhenDecompositionProducesFewerThanFiveSubtopics() {
        StubMessageService messageService =
                new StubMessageService(messageWithText(StopReason.END_TURN, "Solar\nWind"));
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalStateException.class,
                () -> IterativeRefinementLoop.researchWithRefinement(client, "Renewable energy"));
    }

    @Test
    void throwsForUnhandledStopReasonDuringAggregation() {
        StubMessageService messageService = new StubMessageService(
                messageWithText(StopReason.END_TURN, FIVE_SUBTOPICS),
                messageWithText(StopReason.END_TURN, "web findings"),
                messageWithText(StopReason.END_TURN, "doc findings"),
                messageWithText(StopReason.MAX_TOKENS, "truncated..."));
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalStateException.class,
                () -> IterativeRefinementLoop.researchWithRefinement(client, "Renewable energy"));
    }

    // Helper method to create a Message with a given StopReason and one text block per string.
    private static Message messageWithText(StopReason stopReason, String... texts) {
        List<ContentBlock> content = Arrays.stream(texts)
                .map(text -> ContentBlock.ofText(
                        TextBlock.builder().text(text).citations(List.of()).build()))
                .toList();
        return Message.builder()
                .id("msg_test")
                .content(content)
                .model(Model.of("claude-test-model"))
                .stopReason(stopReason)
                .stopSequence((String) null)
                .stopDetails((RefusalStopDetails) null)
                .usage(testUsage())
                .build();
    }

    private static Usage testUsage() {
        return Usage.builder()
                .inputTokens(1)
                .outputTokens(1)
                .outputTokensDetails((OutputTokensDetails) null)
                .cacheCreation((CacheCreation) null)
                .cacheCreationInputTokens(0L)
                .cacheReadInputTokens(0L)
                .inferenceGeo((String) null)
                .serverToolUse((ServerToolUsage) null)
                .serviceTier((Usage.ServiceTier) null)
                .build();
    }
}
