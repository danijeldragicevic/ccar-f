package dev.ccarf.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientAsync;
import com.anthropic.core.ClientOptions;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCountTokensParams;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.MessageTokensCount;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.RefusalStopDetails;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.BetaService;
import com.anthropic.services.blocking.CompletionService;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.ModelService;
import com.anthropic.services.blocking.messages.BatchService;

class AgenticLoopTest {

    private static final int MAX_ITERATIONS = 5;

    @Test
    void returnsFirstResponseWhenStopReasonIsNotToolUse() {
        Message endTurn = messageWithStopReason(StopReason.END_TURN);
        RecordingMessageService messageService = new RecordingMessageService(endTurn);
        AnthropicClient client = new StubAnthropicClient(messageService);

        Message result = AgenticLoop.runLoop(client, "Say hello");

        assertSame(endTurn, result);
        assertEquals(1, messageService.requests.size());
    }

    @Test
    void sendsUserMessageAsFirstTurn() {
        RecordingMessageService messageService =
                new RecordingMessageService(messageWithStopReason(StopReason.END_TURN));
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
        RecordingMessageService messageService = new RecordingMessageService(toolUse, endTurn);
        AnthropicClient client = new StubAnthropicClient(messageService);

        Message result = AgenticLoop.runLoop(client, "Use a tool");

        assertSame(endTurn, result);
        assertEquals(2, messageService.requests.size());
    }

    @Test
    void throwsAfterExceedingMaxToolUseIterations() {
        Message[] allToolUse = new Message[MAX_ITERATIONS];
        Arrays.fill(allToolUse, messageWithStopReason(StopReason.TOOL_USE));
        RecordingMessageService messageService = new RecordingMessageService(allToolUse);
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalStateException.class, () -> AgenticLoop.runLoop(client, "Loop forever"));
        assertEquals(MAX_ITERATIONS, messageService.requests.size());
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

    // Minimal implementation of MessageService that only implements the create() method
    private static final class RecordingMessageService implements MessageService {
        private final Deque<Message> responses;
        private final List<MessageCreateParams> requests = new ArrayList<>();

        RecordingMessageService(Message... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public Message create(MessageCreateParams params, RequestOptions requestOptions) {
            requests.add(params);
            return responses.poll();
        }

        @Override
        public MessageService.WithRawResponse withRawResponse() {
            throw new UnsupportedOperationException();
        }

        @Override
        public MessageService withOptions(Consumer<ClientOptions.Builder> modifier) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BatchService batches() {
            throw new UnsupportedOperationException();
        }

        @Override
        public StreamResponse<RawMessageStreamEvent> createStreaming(
                MessageCreateParams params, RequestOptions requestOptions) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MessageTokensCount countTokens(
                MessageCountTokensParams params, RequestOptions requestOptions) {
            throw new UnsupportedOperationException();
        }
    }

    // Minimal implementation of AnthropicClient that only implements the messages() method
    private static final class StubAnthropicClient implements AnthropicClient {
        private final MessageService messageService;

        StubAnthropicClient(MessageService messageService) {
            this.messageService = messageService;
        }

        @Override
        public MessageService messages() {
            return messageService;
        }

        @Override
        public AnthropicClientAsync async() {
            throw new UnsupportedOperationException();
        }

        @Override
        public AnthropicClient.WithRawResponse withRawResponse() {
            throw new UnsupportedOperationException();
        }

        @Override
        public AnthropicClient withOptions(Consumer<ClientOptions.Builder> modifier) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionService completions() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelService models() {
            throw new UnsupportedOperationException();
        }

        @Override
        public BetaService beta() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            // No-op
        }
    }
}
