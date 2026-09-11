package dev.ccarf.d1.agenticloops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.RefusalStopDetails;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;

class SequentialToolCallLoopTest {

    private static final int MAX_ITERATIONS = 5;

    @Test
    void searchesThenCalculatesAcrossThreeSequentialIterations() {
        Message searchTurn = messageWithToolUse(
                toolUseBlock("call_1", "web_search", Map.of("query", "population of France")));
        Message calculateTurn = messageWithToolUse(
                toolUseBlock("call_2", "calculator", Map.of("expression", "68170000 * 0.02")));
        Message endTurn = messageWithText(StopReason.END_TURN, "2% of that is 1363400.");
        StubMessageService messageService =
                new StubMessageService(searchTurn, calculateTurn, endTurn);
        AnthropicClient client = new StubAnthropicClient(messageService);

        String result = SequentialToolCallLoop.runLoop(client,
                "Search for the population of France, then calculate 2% of it.");

        assertEquals("2% of that is 1363400.", result);
        assertEquals(3, messageService.requests.size());

        // Turn 1's tool_result feeds the search value back before the calculator is ever called.
        List<ContentBlockParam> searchResult = toolResultBlocks(messageService.requests.get(1));
        assertEquals(1, searchResult.size());
        ToolResultBlockParam searchToolResult = searchResult.get(0).toolResult().orElseThrow();
        assertEquals("call_1", searchToolResult.toolUseId());
        assertEquals(
                "Mock search results for \"population of France\": "
                        + "reported value is 68170000 (stub - no real network call).",
                searchToolResult.content().orElseThrow().string().orElseThrow());

        // Turn 2's tool_result is the calculation performed on that search value.
        List<ContentBlockParam> calculateResult = toolResultBlocks(messageService.requests.get(2));
        assertEquals(1, calculateResult.size());
        ToolResultBlockParam calculateToolResult =
                calculateResult.get(0).toolResult().orElseThrow();
        assertEquals("call_2", calculateToolResult.toolUseId());
        assertEquals("1363400",
                calculateToolResult.content().orElseThrow().string().orElseThrow());
    }

    @Test
    void returnsExtractedTextWhenStopReasonIsNotToolUse() {
        Message endTurn = messageWithText(StopReason.END_TURN, "No tools needed.");
        StubMessageService messageService = new StubMessageService(endTurn);
        AnthropicClient client = new StubAnthropicClient(messageService);

        String result = SequentialToolCallLoop.runLoop(client, "Say hello");

        assertEquals("No tools needed.", result);
        assertEquals(1, messageService.requests.size());
    }

    @Test
    void sendsUserMessageAsFirstTurn() {
        StubMessageService messageService =
                new StubMessageService(messageWithText(StopReason.END_TURN, "hi"));
        AnthropicClient client = new StubAnthropicClient(messageService);

        SequentialToolCallLoop.runLoop(client, "Say hello");

        MessageCreateParams sentParams = messageService.requests.get(0);
        assertEquals(1, sentParams.messages().size());
        MessageParam userTurn = sentParams.messages().get(0);
        assertEquals(MessageParam.Role.USER, userTurn.role());
        assertEquals("Say hello", userTurn.content().string().orElse(null));
    }

    @Test
    void throwsAfterExceedingMaxToolUseIterations() {
        Message[] allToolUse = new Message[MAX_ITERATIONS];
        Arrays.fill(allToolUse, messageWithToolUse(
                toolUseBlock("call_1", "calculator", Map.of("expression", "1 + 1"))));
        StubMessageService messageService = new StubMessageService(allToolUse);
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalStateException.class,
                () -> SequentialToolCallLoop.runLoop(client, "Loop forever"));
        assertEquals(MAX_ITERATIONS, messageService.requests.size());
    }

    @Test
    void throwsForUnknownToolName() {
        Message toolUse = messageWithToolUse(
                toolUseBlock("call_1", "unknown_tool", Map.of("foo", "bar")));
        StubMessageService messageService = new StubMessageService(toolUse);
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalArgumentException.class,
                () -> SequentialToolCallLoop.runLoop(client, "Use an unknown tool"));
    }

    @Test
    void throwsForMalformedArithmeticExpression() {
        Message toolUse = messageWithToolUse(
                toolUseBlock("call_1", "calculator", Map.of("expression", "2 + (3")));
        StubMessageService messageService = new StubMessageService(toolUse);
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalArgumentException.class,
                () -> SequentialToolCallLoop.runLoop(client, "What is 2 + (3?"));
    }

    // Extracts the tool_result blocks carried by the last message of a captured request.
    private static List<ContentBlockParam> toolResultBlocks(MessageCreateParams params) {
        MessageParam lastMessage = params.messages().get(params.messages().size() - 1);
        return lastMessage.content().blockParams().orElseThrow();
    }

    // Builds a tool_use content block as Claude would emit it when requesting a tool call.
    private static ToolUseBlock toolUseBlock(String id, String name, Map<String, Object> input) {
        return ToolUseBlock.builder()
                .id(id)
                .name(name)
                .caller(DirectCaller.builder().build())
                .input(JsonValue.from(input))
                .build();
    }

    // Helper method to create a Message whose content is one or more tool_use blocks.
    private static Message messageWithToolUse(ToolUseBlock... toolUseBlocks) {
        List<ContentBlock> content = Arrays.stream(toolUseBlocks)
                .map(ContentBlock::ofToolUse)
                .toList();
        return Message.builder()
                .id("msg_test")
                .content(content)
                .model(Model.of("claude-test-model"))
                .stopReason(StopReason.TOOL_USE)
                .stopSequence((String) null)
                .stopDetails((RefusalStopDetails) null)
                .usage(testUsage())
                .build();
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
