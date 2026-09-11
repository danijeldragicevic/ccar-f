package dev.ccarf.d1.agenticloops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

class AgenticLoopTest {

    private static final int MAX_ITERATIONS = 5;

    @Test
    void returnsFirstResponseWhenStopReasonIsNotToolUse() {
        Message endTurn = messageWithStopReason(StopReason.END_TURN);
        StubMessageService messageService = new StubMessageService(endTurn);
        AnthropicClient client = new StubAnthropicClient(messageService);

        Message result = AgenticLoop.runLoop(client, "Say hello");

        assertSame(endTurn, result);
        assertEquals(1, messageService.requests.size());
    }

    @Test
    void sendsUserMessageAsFirstTurn() {
        StubMessageService messageService =
                new StubMessageService(messageWithStopReason(StopReason.END_TURN));
        AnthropicClient client = new StubAnthropicClient(messageService);

        AgenticLoop.runLoop(client, "Say hello");

        MessageCreateParams sentParams = messageService.requests.get(0);
        assertEquals(1, sentParams.messages().size());
        MessageParam userTurn = sentParams.messages().get(0);
        assertEquals(MessageParam.Role.USER, userTurn.role());
        assertEquals("Say hello", userTurn.content().string().orElse(null));
    }

    @Test
    void continuesLoopingWhileStopReasonIsToolUse() {
        Message toolUse = messageWithStopReason(StopReason.TOOL_USE);
        Message endTurn = messageWithStopReason(StopReason.END_TURN);
        StubMessageService messageService = new StubMessageService(toolUse, endTurn);
        AnthropicClient client = new StubAnthropicClient(messageService);

        Message result = AgenticLoop.runLoop(client, "Use a tool");

        assertSame(endTurn, result);
        assertEquals(2, messageService.requests.size());
    }

    @Test
    void throwsAfterExceedingMaxToolUseIterations() {
        Message[] allToolUse = new Message[MAX_ITERATIONS];
        Arrays.fill(allToolUse, messageWithStopReason(StopReason.TOOL_USE));
        StubMessageService messageService = new StubMessageService(allToolUse);
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalStateException.class, () -> AgenticLoop.runLoop(client, "Loop forever"));
        assertEquals(MAX_ITERATIONS, messageService.requests.size());
    }

    @Test
    void throwsForUnhandledStopReason() {
        StubMessageService messageService =
                new StubMessageService(messageWithStopReason(StopReason.MAX_TOKENS));
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalStateException.class, () -> AgenticLoop.runLoop(client, "Say hello"));
    }

    // Helper method to create a Message with a specific StopReason
    private static Message messageWithStopReason(StopReason stopReason) {
        return Message.builder()
                .id("msg_test")
                .content(List.of(ContentBlock.ofText(
                        TextBlock.builder().text("hi").citations(List.of()).build())))
                .model(Model.of("claude-test-model"))
                .stopReason(stopReason)
                .stopSequence((String) null)
                .stopDetails((RefusalStopDetails) null)
                .usage(Usage.builder()
                        .inputTokens(1)
                        .outputTokens(1)
                        .cacheCreation((CacheCreation) null)
                        .cacheCreationInputTokens(0L)
                        .cacheReadInputTokens(0L)
                        .inferenceGeo((String) null)
                        .serverToolUse((ServerToolUsage) null)
                        .serviceTier((Usage.ServiceTier) null)
                        .build())
                .build();
    }
}
