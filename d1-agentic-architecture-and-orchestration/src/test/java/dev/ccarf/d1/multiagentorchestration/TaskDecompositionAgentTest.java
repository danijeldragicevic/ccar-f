package dev.ccarf.d1.multiagentorchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.RefusalStopDetails;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;

class TaskDecompositionAgentTest {

    @Test
    void returnsOneSubtopicPerLine() {
        Message endTurn = messageWithText(StopReason.END_TURN,
                "Solar\nWind\nGeothermal\nTidal\nBiomass\nFusion");
        StubMessageService messageService = new StubMessageService(endTurn);
        AnthropicClient client = new StubAnthropicClient(messageService);

        List<String> subtopics = TaskDecompositionAgent.decompose(client, "Renewable energy");

        assertEquals(List.of("Solar", "Wind", "Geothermal", "Tidal", "Biomass", "Fusion"),
                subtopics);
    }

    @Test
    void trimsWhitespaceAndDropsBlankLines() {
        Message endTurn = messageWithText(StopReason.END_TURN,
                "  Solar  \nWind\n\nGeothermal\nTidal\nBiomass\n");
        StubMessageService messageService = new StubMessageService(endTurn);
        AnthropicClient client = new StubAnthropicClient(messageService);

        List<String> subtopics = TaskDecompositionAgent.decompose(client, "Renewable energy");

        assertEquals(List.of("Solar", "Wind", "Geothermal", "Tidal", "Biomass"), subtopics);
    }

    @Test
    void sendsTopicAsUserMessageUnderDecompositionSystemPrompt() {
        StubMessageService messageService = new StubMessageService(
                messageWithText(StopReason.END_TURN, "Solar\nWind\nGeothermal\nTidal\nBiomass"));
        AnthropicClient client = new StubAnthropicClient(messageService);

        TaskDecompositionAgent.decompose(client, "Renewable energy");

        MessageCreateParams sentParams = messageService.requests.get(0);
        assertEquals(TaskDecompositionAgent.SYSTEM_PROMPT,
                sentParams.system().orElseThrow().string().orElseThrow());
        assertEquals(1, sentParams.messages().size());
        MessageParam userTurn = sentParams.messages().get(0);
        assertEquals(MessageParam.Role.USER, userTurn.role());
        assertEquals("Renewable energy", userTurn.content().string().orElse(null));
    }

    @Test
    void throwsWhenFewerThanFiveSubtopicsAreReturned() {
        Message endTurn = messageWithText(StopReason.END_TURN, "Solar\nWind");
        StubMessageService messageService = new StubMessageService(endTurn);
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalStateException.class,
                () -> TaskDecompositionAgent.decompose(client, "Renewable energy"));
    }

    @Test
    void throwsForUnhandledStopReason() {
        StubMessageService messageService =
                new StubMessageService(messageWithText(StopReason.MAX_TOKENS, "truncated..."));
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalStateException.class,
                () -> TaskDecompositionAgent.decompose(client, "Renewable energy"));
    }

    @Test
    void throwsForUnexpectedToolUseStopReason() {
        StubMessageService messageService =
                new StubMessageService(messageWithText(StopReason.TOOL_USE, "unexpected"));
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalStateException.class,
                () -> TaskDecompositionAgent.decompose(client, "Renewable energy"));
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
