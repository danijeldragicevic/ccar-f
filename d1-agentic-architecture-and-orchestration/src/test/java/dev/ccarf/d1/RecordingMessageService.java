package dev.ccarf.d1;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import com.anthropic.core.ClientOptions;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCountTokensParams;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageTokensCount;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.messages.BatchService;

// Minimal implementation of MessageService that only implements the create() method,
// returning the given responses in order and recording the params it was called with.
final class RecordingMessageService implements MessageService {
    final List<MessageCreateParams> requests = new ArrayList<>();
    private final Deque<Message> responses;

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
