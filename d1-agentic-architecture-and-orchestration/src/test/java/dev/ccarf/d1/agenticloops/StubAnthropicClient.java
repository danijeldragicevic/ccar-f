package dev.ccarf.d1.agenticloops;

import java.util.function.Consumer;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientAsync;
import com.anthropic.core.ClientOptions;
import com.anthropic.services.blocking.BetaService;
import com.anthropic.services.blocking.CompletionService;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.ModelService;

// Minimal implementation of AnthropicClient that only implements the messages() method.
final class StubAnthropicClient implements AnthropicClient {
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
