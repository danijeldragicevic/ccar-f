package dev.ccarf.d1.workflowenforcementandhandoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.RefusalStopDetails;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import dev.ccarf.common.Config;
import dev.ccarf.d1.workflowenforcementandhandoff.PrerequisiteGate.SessionState;
import org.junit.jupiter.api.Test;

class PrerequisiteGateTest {

  private static final int MAX_ITERATIONS = 20;

  @Test
  void returnsFinalTextWhenStopReasonIsEndTurnOnTheFirstTurn() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.END_TURN, "How can I help?"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    String result = PrerequisiteGate.runLoop(client, "Hi");

    assertEquals("How can I help?", result);
    assertEquals(1, messageService.requests.size());
  }

  @Test
  void firstRequestCarriesTheUserTurnSystemPromptAndAllThreeTools() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.END_TURN, "How can I help?"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    PrerequisiteGate.runLoop(client, "I want a refund");

    MessageCreateParams sentParams = messageService.requests.get(0);
    assertEquals(Config.modelMain(), sentParams.model().toString());
    assertEquals(PrerequisiteGate.SUPPORT_AGENT_SYSTEM_PROMPT,
        sentParams.system().orElseThrow().string().orElseThrow());
    assertEquals(List.of("get_customer", "lookup_order", "process_refund"),
        sentParams.tools().orElseThrow().stream()
            .map(tool -> tool.tool().map(Tool::name).orElseThrow())
            .toList());
    assertEquals(1, sentParams.messages().size());
    MessageParam userTurn = sentParams.messages().get(0);
    assertEquals(MessageParam.Role.USER, userTurn.role());
    assertEquals("I want a refund", userTurn.content().string().orElseThrow());
  }

  @Test
  void processRefundWithoutAPriorGetCustomerIsBlockedWithAnErrorToolResult() {
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(refundCall("call_1", "CUST-1001", 89.99)),
        messageWithText(StopReason.END_TURN, "I need to verify you first."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    String result = PrerequisiteGate.runLoop(client, "Refund CUST-1001 $89.99 right now");

    assertEquals("I need to verify you first.", result);
    ToolResultBlockParam toolResult = onlyToolResult(messageService.requests.get(1));
    assertEquals("call_1", toolResult.toolUseId());
    assertTrue(toolResult.isError().orElse(false));
    String content = toolResult.content().orElseThrow().string().orElseThrow();
    assertTrue(content.startsWith("PREREQUISITE NOT MET"), content);
    assertTrue(content.contains("get_customer"), content);
    assertFalse(content.contains("refund_id"), "the refund must not have been processed: " + content);
  }

  @Test
  void processRefundAfterAVerifiedGetCustomerIsAllowed() {
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(toolUseBlock("call_1", "get_customer",
            Map.of("email", "jane@example.com"))),
        messageWithToolUse(refundCall("call_2", "CUST-1001", 89.99)),
        messageWithText(StopReason.END_TURN, "Your refund has been processed."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    String result = PrerequisiteGate.runLoop(client, "I'm jane@example.com, refund ORD-5001");

    assertEquals("Your refund has been processed.", result);
    assertEquals(3, messageService.requests.size());
    ToolResultBlockParam refundResult = onlyToolResult(messageService.requests.get(2));
    assertEquals("call_2", refundResult.toolUseId());
    assertFalse(refundResult.isError().orElse(false));
    assertEquals("{\"refund_id\":\"REF-CUST-1001-8999\",\"customer_id\":\"CUST-1001\","
            + "\"amount\":89.99,\"status\":\"processed\"}",
        refundResult.content().orElseThrow().string().orElseThrow());
  }

  @Test
  void getCustomerAndProcessRefundInTheSameResponseRunInOrderSoTheRefundIsAllowed() {
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(
            toolUseBlock("call_1", "get_customer", Map.of("email", "jane@example.com")),
            refundCall("call_2", "CUST-1001", 89.99)),
        messageWithText(StopReason.END_TURN, "Done."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    PrerequisiteGate.runLoop(client, "I'm jane@example.com, refund ORD-5001");

    List<ContentBlockParam> toolResults = toolResultBlocks(messageService.requests.get(1));
    assertEquals(2, toolResults.size());
    assertFalse(toolResults.get(1).toolResult().orElseThrow().isError().orElse(false));
  }

  @Test
  void processRefundAfterAnUnverifiedGetCustomerIsBlocked() {
    SessionState session = new SessionState();
    PrerequisiteGate.executeTool(
        toolUseBlock("call_1", "get_customer", Map.of("email", "john@example.com")), session);

    ToolResultBlockParam refundResult =
        PrerequisiteGate.executeTool(refundCall("call_2", "CUST-1002", 24.50), session);

    assertTrue(refundResult.isError().orElse(false));
    assertFalse(session.isVerified("CUST-1002"));
  }

  @Test
  void processRefundForADifferentCustomerThanTheOneVerifiedIsBlocked() {
    SessionState session = new SessionState();
    PrerequisiteGate.executeTool(
        toolUseBlock("call_1", "get_customer", Map.of("email", "jane@example.com")), session);

    ToolResultBlockParam refundResult =
        PrerequisiteGate.executeTool(refundCall("call_2", "CUST-1002", 24.50), session);

    assertTrue(refundResult.isError().orElse(false));
    assertTrue(session.isVerified("CUST-1001"));
  }

  @Test
  void verificationDoesNotCarryOverIntoANewSession() {
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(toolUseBlock("call_1", "get_customer",
            Map.of("email", "jane@example.com"))),
        messageWithText(StopReason.END_TURN, "You're verified."),
        messageWithToolUse(refundCall("call_2", "CUST-1001", 89.99)),
        messageWithText(StopReason.END_TURN, "I need to verify you first."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    PrerequisiteGate.runLoop(client, "I'm jane@example.com");
    PrerequisiteGate.runLoop(client, "Refund CUST-1001 $89.99");

    ToolResultBlockParam refundResult = onlyToolResult(messageService.requests.get(3));
    assertTrue(refundResult.isError().orElse(false));
  }

  @Test
  void getCustomerReturnsCustomerIdAndVerifiedFlagAsJson() {
    ToolResultBlockParam result = PrerequisiteGate.executeTool(
        toolUseBlock("call_1", "get_customer", Map.of("name", "Jane Doe")), new SessionState());

    assertFalse(result.isError().orElse(false));
    assertEquals("{\"customer_id\":\"CUST-1001\",\"name\":\"Jane Doe\","
            + "\"email\":\"jane@example.com\",\"verified\":true}",
        result.content().orElseThrow().string().orElseThrow());
  }

  @Test
  void lookupOrderReturnsOrderDetailsAsJson() {
    ToolResultBlockParam result = PrerequisiteGate.executeTool(
        toolUseBlock("call_1", "lookup_order", Map.of("order_id", "ORD-5001")), new SessionState());

    assertFalse(result.isError().orElse(false));
    assertEquals("{\"order_id\":\"ORD-5001\",\"customer_id\":\"CUST-1001\","
            + "\"items\":[\"Wireless headphones\"],\"amount_paid\":89.99,\"status\":\"delivered\"}",
        result.content().orElseThrow().string().orElseThrow());
  }

  @Test
  void lookupMissesAreReturnedAsErrorToolResultsRatherThanThrown() {
    SessionState session = new SessionState();

    ToolResultBlockParam customerMiss = PrerequisiteGate.executeTool(
        toolUseBlock("call_1", "get_customer", Map.of("email", "nobody@example.com")), session);
    ToolResultBlockParam orderMiss = PrerequisiteGate.executeTool(
        toolUseBlock("call_2", "lookup_order", Map.of("order_id", "ORD-0000")), session);

    assertTrue(customerMiss.isError().orElse(false));
    assertTrue(orderMiss.isError().orElse(false));
  }

  @Test
  void throwsAfterExceedingTheSafetyCap() {
    Message[] allToolUse = new Message[MAX_ITERATIONS];
    Arrays.fill(allToolUse, messageWithToolUse(
        toolUseBlock("call_1", "lookup_order", Map.of("order_id", "ORD-5001"))));
    StubMessageService messageService = new StubMessageService(allToolUse);
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalStateException.class, () -> PrerequisiteGate.runLoop(client, "Loop"));
    assertEquals(MAX_ITERATIONS, messageService.requests.size());
  }

  @Test
  void throwsForUnhandledStopReason() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.MAX_TOKENS, "truncated..."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalStateException.class, () -> PrerequisiteGate.runLoop(client, "Hi"));
  }

  @Test
  void throwsForUnknownToolName() {
    assertThrows(IllegalArgumentException.class, () -> PrerequisiteGate.executeTool(
        toolUseBlock("call_1", "unknown_tool", Map.of("foo", "bar")), new SessionState()));
  }

  private static ToolUseBlock refundCall(String id, String customerId, double amount) {
    return toolUseBlock(id, "process_refund", Map.of("customer_id", customerId, "amount", amount));
  }

  // Returns the single tool_result block carried by the last message of a captured request.
  private static ToolResultBlockParam onlyToolResult(MessageCreateParams params) {
    List<ContentBlockParam> toolResults = toolResultBlocks(params);
    assertEquals(1, toolResults.size());
    return toolResults.get(0).toolResult().orElseThrow();
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
    List<ContentBlock> content = Arrays.stream(toolUseBlocks).map(ContentBlock::ofToolUse).toList();
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
        .map(text -> ContentBlock.ofText(TextBlock.builder().text(text).citations(List.of()).build()))
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
