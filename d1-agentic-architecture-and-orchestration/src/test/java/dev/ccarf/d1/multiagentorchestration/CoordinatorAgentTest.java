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

class CoordinatorAgentTest {

    @Test
    void returnsExtractedTextWhenStopReasonIsEndTurn() {
        Message endTurn = messageWithText(StopReason.END_TURN, "# Research Report\n...");
        StubMessageService messageService = new StubMessageService(endTurn);
        AnthropicClient client = new StubAnthropicClient(messageService);

        String result = CoordinatorAgent.research(client, "Renewable energy");

        assertEquals("# Research Report\n...", result);
        assertEquals(1, messageService.requests.size());
    }

    @Test
    void joinsMultipleTextBlocksWithNewline() {
        Message endTurn = messageWithText(StopReason.END_TURN, "Section one.", "Section two.");
        StubMessageService messageService = new StubMessageService(endTurn);
        AnthropicClient client = new StubAnthropicClient(messageService);

        String result = CoordinatorAgent.research(client, "Renewable energy");

        assertEquals("Section one.\nSection two.", result);
    }

    @Test
    void sendsTopicAsUserMessageUnderCoordinatorSystemPrompt() {
        StubMessageService messageService =
                new StubMessageService(messageWithText(StopReason.END_TURN, "report"));
        AnthropicClient client = new StubAnthropicClient(messageService);

        CoordinatorAgent.research(client, "Renewable energy");

        MessageCreateParams sentParams = messageService.requests.get(0);
        assertEquals(CoordinatorAgent.SYSTEM_PROMPT, sentParams.system().orElseThrow().string()
                .orElseThrow());
        assertEquals(1, sentParams.messages().size());
        MessageParam userTurn = sentParams.messages().get(0);
        assertEquals(MessageParam.Role.USER, userTurn.role());
        assertEquals("Renewable energy", userTurn.content().string().orElse(null));
    }

    @Test
    void throwsForUnhandledStopReason() {
        StubMessageService messageService =
                new StubMessageService(messageWithText(StopReason.MAX_TOKENS, "truncated..."));
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalStateException.class,
                () -> CoordinatorAgent.research(client, "Renewable energy"));
    }

    @Test
    void throwsForUnexpectedToolUseStopReason() {
        StubMessageService messageService =
                new StubMessageService(messageWithText(StopReason.TOOL_USE, "unexpected"));
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalStateException.class,
                () -> CoordinatorAgent.research(client, "Renewable energy"));
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
