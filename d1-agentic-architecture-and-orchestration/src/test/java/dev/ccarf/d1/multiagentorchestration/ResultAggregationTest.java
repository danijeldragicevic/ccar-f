package dev.ccarf.d1.multiagentorchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.anthropic.models.messages.RefusalStopDetails;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;

import dev.ccarf.common.Config;
import dev.ccarf.d1.multiagentorchestration.ResultAggregation.CoverageEntry;
import dev.ccarf.d1.multiagentorchestration.ResultAggregation.CoverageLevel;

class ResultAggregationTest {

    @Test
    void parsesOneCoverageEntryPerLine() {
        Message endTurn = messageWithText(StopReason.END_TURN,
                "Solar :: WELL_COVERED :: Both subagents reported solar findings.\n"
                        + "Wind :: PARTIALLY_COVERED :: Only briefly mentioned by one subagent.\n"
                        + "Geothermal :: MISSING :: Neither subagent mentioned it.");
        StubMessageService messageService = new StubMessageService(endTurn);
        AnthropicClient client = new StubAnthropicClient(messageService);

        List<CoverageEntry> coverage = ResultAggregation.aggregate(client,
                List.of("Solar", "Wind", "Geothermal"), "web findings", "document findings");

        assertEquals(3, coverage.size());
        assertEquals(new CoverageEntry("Solar", CoverageLevel.WELL_COVERED,
                "Both subagents reported solar findings."), coverage.get(0));
        assertEquals(new CoverageEntry("Wind", CoverageLevel.PARTIALLY_COVERED,
                "Only briefly mentioned by one subagent."), coverage.get(1));
        assertEquals(new CoverageEntry("Geothermal", CoverageLevel.MISSING,
                "Neither subagent mentioned it."), coverage.get(2));
    }

    @Test
    void sendsSubtopicsAndBothSubagentFindingsUnderCoordinatorSystemPrompt() {
        StubMessageService messageService = new StubMessageService(
                messageWithText(StopReason.END_TURN, "Solar :: WELL_COVERED :: covered"));
        AnthropicClient client = new StubAnthropicClient(messageService);

        ResultAggregation.aggregate(client, List.of("Solar", "Wind"), "web findings text",
                "document findings text");

        MessageCreateParams sentParams = messageService.requests.get(0);
        assertEquals(Config.modelMain(), sentParams.model().toString());
        assertEquals(ResultAggregation.SYSTEM_PROMPT,
                sentParams.system().orElseThrow().string().orElseThrow());

        String userPrompt = sentParams.messages().get(0).content().string().orElseThrow();
        assertTrue(userPrompt.contains("Solar"));
        assertTrue(userPrompt.contains("Wind"));
        assertTrue(userPrompt.contains("web findings text"));
        assertTrue(userPrompt.contains("document findings text"));
    }

    @Test
    void throwsForMalformedCoverageLine() {
        Message endTurn = messageWithText(StopReason.END_TURN, "Solar - WELL_COVERED - covered");
        StubMessageService messageService = new StubMessageService(endTurn);
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalArgumentException.class, () -> ResultAggregation.aggregate(client,
                List.of("Solar"), "web findings", "document findings"));
    }

    @Test
    void throwsForUnknownCoverageLevel() {
        Message endTurn =
                messageWithText(StopReason.END_TURN, "Solar :: FULLY_COVERED :: covered");
        StubMessageService messageService = new StubMessageService(endTurn);
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalArgumentException.class, () -> ResultAggregation.aggregate(client,
                List.of("Solar"), "web findings", "document findings"));
    }

    @Test
    void throwsForUnhandledStopReason() {
        StubMessageService messageService =
                new StubMessageService(messageWithText(StopReason.MAX_TOKENS, "truncated..."));
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalStateException.class, () -> ResultAggregation.aggregate(client,
                List.of("Solar"), "web findings", "document findings"));
    }

    @Test
    void throwsForUnexpectedToolUseStopReason() {
        StubMessageService messageService =
                new StubMessageService(messageWithText(StopReason.TOOL_USE, "unexpected"));
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalStateException.class, () -> ResultAggregation.aggregate(client,
                List.of("Solar"), "web findings", "document findings"));
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
                .cacheCreation((CacheCreation) null)
                .cacheCreationInputTokens(0L)
                .cacheReadInputTokens(0L)
                .inferenceGeo((String) null)
                .serverToolUse((ServerToolUsage) null)
                .serviceTier((Usage.ServiceTier) null)
                .build();
    }
}
