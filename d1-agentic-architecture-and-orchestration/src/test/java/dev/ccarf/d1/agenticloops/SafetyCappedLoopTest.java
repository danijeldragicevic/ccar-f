package dev.ccarf.d1.agenticloops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
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

class SafetyCappedLoopTest {

    private static final int MAX_ITERATIONS = 20;

    @Test
    void returnsExtractedTextWhenStopReasonIsNotToolUse() {
        Message endTurn = messageWithText(StopReason.END_TURN, "The answer is 84.");
        StubMessageService messageService = new StubMessageService(endTurn);
        AnthropicClient client = new StubAnthropicClient(messageService);

        String result = SafetyCappedLoop.runLoop(client, "What is 12 * 7?");

        assertEquals("The answer is 84.", result);
        assertEquals(1, messageService.requests.size());
    }

    @Test
    void sendsUserMessageAsFirstTurn() {
        StubMessageService messageService =
                new StubMessageService(messageWithText(StopReason.END_TURN, "hi"));
        AnthropicClient client = new StubAnthropicClient(messageService);

        SafetyCappedLoop.runLoop(client, "Say hello");

        MessageCreateParams sentParams = messageService.requests.get(0);
        assertEquals(1, sentParams.messages().size());
        MessageParam userTurn = sentParams.messages().get(0);
        assertEquals(MessageParam.Role.USER, userTurn.role());
        assertEquals("Say hello", userTurn.content().string().orElse(null));
    }

    @Test
    void continuesLoopingWhileStopReasonIsToolUse() {
        Message toolUse = messageWithToolUse(
                toolUseBlock("call_1", "calculator", Map.of("expression", "2 + 3")));
        Message endTurn = messageWithText(StopReason.END_TURN, "It's 5.");
        StubMessageService messageService = new StubMessageService(toolUse, endTurn);
        AnthropicClient client = new StubAnthropicClient(messageService);

        String result = SafetyCappedLoop.runLoop(client, "Use a tool");

        assertEquals("It's 5.", result);
        assertEquals(2, messageService.requests.size());
    }

    @Test
    void terminatesViaStopReasonWellBeforeReachingTheSafetyCap() {
        Message toolUse = messageWithToolUse(
                toolUseBlock("call_1", "calculator", Map.of("expression", "2 + 3")));
        Message endTurn = messageWithText(StopReason.END_TURN, "It's 5.");
        StubMessageService messageService = new StubMessageService(toolUse, endTurn);
        AnthropicClient client = new StubAnthropicClient(messageService);

        SafetyCappedLoop.runLoop(client, "Use a tool");

        assertTrue(messageService.requests.size() < MAX_ITERATIONS,
                "a normal query should terminate via stop_reason well before the safety cap");
    }

    @Test
    void throwsAndLogsWarningAfterExceedingSafetyCap() {
        Message[] allToolUse = new Message[MAX_ITERATIONS];
        Arrays.fill(allToolUse, messageWithToolUse(
                toolUseBlock("call_1", "calculator", Map.of("expression", "1 + 1"))));
        StubMessageService messageService = new StubMessageService(allToolUse);
        AnthropicClient client = new StubAnthropicClient(messageService);

        PrintStream originalErr = System.err;
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(capturedErr));
        try {
            assertThrows(IllegalStateException.class,
                    () -> SafetyCappedLoop.runLoop(client, "Loop forever"));
        } finally {
            System.setErr(originalErr);
        }

        assertEquals(MAX_ITERATIONS, messageService.requests.size());
        String warning = capturedErr.toString();
        assertTrue(warning.contains("WARNING"), "expected a warning to be logged: " + warning);
        assertTrue(warning.contains(String.valueOf(MAX_ITERATIONS)),
                "expected the warning to mention the cap: " + warning);
    }

    @Test
    void sendsCalculatorResultAsToolResultBeforeReturningFinalText() {
        Message toolUse = messageWithToolUse(
                toolUseBlock("call_1", "calculator", Map.of("expression", "12 * (3 + 4)")));
        Message endTurn = messageWithText(StopReason.END_TURN, "The answer is 84.");
        StubMessageService messageService = new StubMessageService(toolUse, endTurn);
        AnthropicClient client = new StubAnthropicClient(messageService);

        String result = SafetyCappedLoop.runLoop(client, "What is 12 * (3 + 4)?");

        assertEquals("The answer is 84.", result);
        List<ContentBlockParam> toolResults = toolResultBlocks(messageService.requests.get(1));
        assertEquals(1, toolResults.size());
        ToolResultBlockParam toolResult = toolResults.get(0).toolResult().orElseThrow();
        assertEquals("call_1", toolResult.toolUseId());
        assertEquals("84", toolResult.content().orElseThrow().string().orElseThrow());
    }

    @Test
    void sendsWebSearchMockResultAsToolResultBeforeReturningFinalText() {
        Message toolUse = messageWithToolUse(
                toolUseBlock("call_1", "web_search", Map.of("query", "latest AI news")));
        Message endTurn = messageWithText(StopReason.END_TURN, "Here's a summary.");
        StubMessageService messageService = new StubMessageService(toolUse, endTurn);
        AnthropicClient client = new StubAnthropicClient(messageService);

        String result = SafetyCappedLoop.runLoop(client, "Search for the latest AI news");

        assertEquals("Here's a summary.", result);
        List<ContentBlockParam> toolResults = toolResultBlocks(messageService.requests.get(1));
        ToolResultBlockParam toolResult = toolResults.get(0).toolResult().orElseThrow();
        assertEquals("Mock search results for \"latest AI news\" (stub - no real network call).",
                toolResult.content().orElseThrow().string().orElseThrow());
    }

    @Test
    void throwsForUnknownToolName() {
        Message toolUse = messageWithToolUse(
                toolUseBlock("call_1", "unknown_tool", Map.of("foo", "bar")));
        StubMessageService messageService = new StubMessageService(toolUse);
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalArgumentException.class,
                () -> SafetyCappedLoop.runLoop(client, "Use an unknown tool"));
    }

    @Test
    void throwsForMalformedArithmeticExpression() {
        Message toolUse = messageWithToolUse(
                toolUseBlock("call_1", "calculator", Map.of("expression", "2 + (3")));
        StubMessageService messageService = new StubMessageService(toolUse);
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalArgumentException.class,
                () -> SafetyCappedLoop.runLoop(client, "What is 2 + (3?"));
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
