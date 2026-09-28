package dev.ccarf.d1.workflowenforcementandhandoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import com.fasterxml.jackson.core.type.TypeReference;
import dev.ccarf.common.Config;
import dev.ccarf.d1.workflowenforcementandhandoff.StructuredHandoff.HandoffSummary;
import dev.ccarf.d1.workflowenforcementandhandoff.StructuredHandoff.SessionState;
import dev.ccarf.d1.workflowenforcementandhandoff.StructuredHandoff.SupportOutcome;
import org.junit.jupiter.api.Test;

class StructuredHandoffTest {

  private static final int MAX_ITERATIONS = 20;

  private static final Map<String, Object> COMPLETE_HANDOFF = Map.of(
      "customer_id", "CUST-1001",
      "conversation_summary", "Jane Doe (verified) reports the standing desk from ORD-5003 "
          + "arrived with a cracked frame and asked for a full refund.",
      "root_cause", "The $349.00 refund exceeds the $100.00 automated refund limit.",
      "refund_amount", 349.00,
      "recommended_action", "Approve the full refund of $349.00 for ORD-5003.");

  @Test
  void firstRequestCarriesTheUserTurnSystemPromptAndAllFourTools() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.END_TURN, "How can I help?"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    StructuredHandoff.runLoop(client, "I want a refund");

    MessageCreateParams sentParams = messageService.requests.get(0);
    assertEquals(Config.modelMain(), sentParams.model().toString());
    assertEquals(StructuredHandoff.SUPPORT_AGENT_SYSTEM_PROMPT,
        sentParams.system().orElseThrow().string().orElseThrow());
    assertEquals(List.of("get_customer", "lookup_order", "process_refund", "escalate_to_human"),
        sentParams.tools().orElseThrow().stream()
            .map(tool -> tool.tool().map(Tool::name).orElseThrow())
            .toList());
    assertEquals("I want a refund",
        sentParams.messages().get(0).content().string().orElseThrow());
  }

  @Test
  void escalateToHumanRequiresAllFiveFieldsInItsSchema() {
    Tool tool = StructuredHandoff.getEscalateToHumanTool();

    assertEquals(List.of("customer_id", "conversation_summary", "root_cause", "refund_amount",
        "recommended_action"), tool.inputSchema().required().orElseThrow());
  }

  @Test
  void refundAboveTheLimitFailsSoTheAgentEscalatesWithACompleteHandoff() {
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(toolUseBlock("call_1", "get_customer",
            Map.of("email", "jane@example.com"))),
        messageWithToolUse(refundCall("call_2", "CUST-1001", 349.00)),
        messageWithToolUse(toolUseBlock("call_3", "escalate_to_human", COMPLETE_HANDOFF)),
        messageWithText(StopReason.END_TURN, "I've passed your case to a human agent."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    SupportOutcome outcome = StructuredHandoff.runLoop(client, "Refund ORD-5003");

    ToolResultBlockParam refundResult = onlyToolResult(messageService.requests.get(2));
    assertTrue(refundResult.isError().orElse(false));
    assertTrue(refundResult.content().orElseThrow().string().orElseThrow()
        .contains("automated refund limit"));

    ToolResultBlockParam handoffResult = onlyToolResult(messageService.requests.get(3));
    assertFalse(handoffResult.isError().orElse(false));
    assertEquals("{\"handoff_id\":\"HO-CUST-1001\",\"customer_id\":\"CUST-1001\","
            + "\"status\":\"queued_for_human_agent\"}",
        handoffResult.content().orElseThrow().string().orElseThrow());

    assertEquals("I've passed your case to a human agent.", outcome.finalResponse());
    HandoffSummary handoff = outcome.handoff().orElseThrow();
    assertEquals("CUST-1001", handoff.customerId());
    assertEquals(COMPLETE_HANDOFF.get("conversation_summary"), handoff.conversationSummary());
    assertEquals(COMPLETE_HANDOFF.get("root_cause"), handoff.rootCause());
    assertEquals(349.00, handoff.refundAmount());
    assertEquals(COMPLETE_HANDOFF.get("recommended_action"), handoff.recommendedAction());
  }

  @Test
  void anIncompleteHandoffIsRejectedAndOnlyTheCorrectedOneIsRecorded() {
    Map<String, Object> incomplete = new HashMap<>(COMPLETE_HANDOFF);
    incomplete.put("root_cause", "TBD");
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(toolUseBlock("call_1", "escalate_to_human", incomplete)),
        messageWithToolUse(toolUseBlock("call_2", "escalate_to_human", COMPLETE_HANDOFF)),
        messageWithText(StopReason.END_TURN, "Escalated."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    SupportOutcome outcome = StructuredHandoff.runLoop(client, "Refund ORD-5003");

    ToolResultBlockParam rejected = onlyToolResult(messageService.requests.get(1));
    assertTrue(rejected.isError().orElse(false));
    String content = rejected.content().orElseThrow().string().orElseThrow();
    assertTrue(content.startsWith("HANDOFF REJECTED"), content);
    assertTrue(content.contains("root_cause"), content);
    assertEquals(COMPLETE_HANDOFF.get("root_cause"), outcome.handoff().orElseThrow().rootCause());
  }

  @Test
  void noHandoffWhenTheAgentResolvesTheIssueItself() {
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(toolUseBlock("call_1", "get_customer",
            Map.of("email", "jane@example.com"))),
        messageWithToolUse(refundCall("call_2", "CUST-1001", 89.99)),
        messageWithText(StopReason.END_TURN, "Your refund has been processed."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    SupportOutcome outcome = StructuredHandoff.runLoop(client, "Refund ORD-5001");

    assertFalse(onlyToolResult(messageService.requests.get(2)).isError().orElse(false));
    assertEquals(Optional.empty(), outcome.handoff());
  }

  @Test
  void compileHandoffReturnsAllFiveFieldsTrimmed() {
    Map<String, Object> padded = new HashMap<>(COMPLETE_HANDOFF);
    padded.put("customer_id", "  CUST-1001 ");

    HandoffSummary handoff = StructuredHandoff.compileHandoff(input(padded));

    assertEquals(new HandoffSummary("CUST-1001",
        (String) COMPLETE_HANDOFF.get("conversation_summary"),
        (String) COMPLETE_HANDOFF.get("root_cause"), 349.00,
        (String) COMPLETE_HANDOFF.get("recommended_action")), handoff);
  }

  @Test
  void compileHandoffRejectsPlaceholderText() {
    for (String placeholder : List.of("N/A", "n/a", "none", "Unknown", "TBD", "...", "-",
        "Root cause TBD", "<customer id>", "[insert amount]", "{{summary}}")) {
      Map<String, Object> withPlaceholder = new HashMap<>(COMPLETE_HANDOFF);
      withPlaceholder.put("recommended_action", placeholder);

      IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
          () -> StructuredHandoff.compileHandoff(input(withPlaceholder)), placeholder);
      assertTrue(exception.getMessage().contains("recommended_action contains placeholder text"),
          exception.getMessage());
    }
  }

  @Test
  void compileHandoffAcceptsRealTextThatMerelyContainsPlaceholderLikeWords() {
    Map<String, Object> handoff = new HashMap<>(COMPLETE_HANDOFF);
    handoff.put("conversation_summary", "Customer says the cause is unknown to them; the desk "
        + "arrived cracked and none of the parts are usable.");

    StructuredHandoff.compileHandoff(input(handoff));
  }

  @Test
  void compileHandoffReportsEveryProblemAtOnce() {
    Map<String, Object> broken = new HashMap<>(COMPLETE_HANDOFF);
    broken.remove("customer_id");
    broken.put("conversation_summary", "   ");
    broken.put("root_cause", "N/A");
    broken.put("refund_amount", -5);
    broken.remove("recommended_action");

    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
        () -> StructuredHandoff.compileHandoff(input(broken)));

    String message = exception.getMessage();
    assertTrue(message.contains("customer_id is missing"), message);
    assertTrue(message.contains("conversation_summary is empty"), message);
    assertTrue(message.contains("root_cause contains placeholder text"), message);
    assertTrue(message.contains("refund_amount must not be negative"), message);
    assertTrue(message.contains("recommended_action is missing"), message);
  }

  @Test
  void compileHandoffRejectsAMissingRefundAmountButAcceptsZero() {
    Map<String, Object> noAmount = new HashMap<>(COMPLETE_HANDOFF);
    noAmount.remove("refund_amount");
    Map<String, Object> zeroAmount = new HashMap<>(COMPLETE_HANDOFF);
    zeroAmount.put("refund_amount", 0);

    assertThrows(IllegalArgumentException.class,
        () -> StructuredHandoff.compileHandoff(input(noAmount)));
    assertEquals(0.0, StructuredHandoff.compileHandoff(input(zeroAmount)).refundAmount());
  }

  @Test
  void refundAboveTheAutomatedLimitIsAnErrorEvenForAVerifiedCustomer() {
    SessionState session = new SessionState();
    StructuredHandoff.executeTool(
        toolUseBlock("call_1", "get_customer", Map.of("email", "jane@example.com")), session);

    ToolResultBlockParam overLimit =
        StructuredHandoff.executeTool(refundCall("call_2", "CUST-1001", 349.00), session);
    ToolResultBlockParam atLimit = StructuredHandoff.executeTool(
        refundCall("call_3", "CUST-1001", StructuredHandoff.AUTOMATED_REFUND_LIMIT), session);

    assertTrue(overLimit.isError().orElse(false));
    assertFalse(atLimit.isError().orElse(false));
  }

  @Test
  void prerequisiteGateStillBlocksAnUnverifiedRefund() {
    ToolResultBlockParam result = StructuredHandoff.executeTool(
        refundCall("call_1", "CUST-1001", 89.99), new SessionState());

    assertTrue(result.isError().orElse(false));
    assertTrue(result.content().orElseThrow().string().orElseThrow()
        .startsWith("PREREQUISITE NOT MET"));
  }

  @Test
  void throwsAfterExceedingTheSafetyCap() {
    Message[] allToolUse = new Message[MAX_ITERATIONS];
    Arrays.fill(allToolUse, messageWithToolUse(
        toolUseBlock("call_1", "lookup_order", Map.of("order_id", "ORD-5003"))));
    StubMessageService messageService = new StubMessageService(allToolUse);
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalStateException.class, () -> StructuredHandoff.runLoop(client, "Loop"));
    assertEquals(MAX_ITERATIONS, messageService.requests.size());
  }

  @Test
  void throwsForUnhandledStopReason() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.MAX_TOKENS, "truncated..."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalStateException.class, () -> StructuredHandoff.runLoop(client, "Hi"));
  }

  @Test
  void throwsForUnknownToolName() {
    assertThrows(IllegalArgumentException.class, () -> StructuredHandoff.executeTool(
        toolUseBlock("call_1", "unknown_tool", Map.of("foo", "bar")), new SessionState()));
  }

  // Converts a plain map into tool input, the shape executeTool hands to compileHandoff.
  private static Map<String, JsonValue> input(Map<String, Object> values) {
    return JsonValue.from(values).convert(new TypeReference<Map<String, JsonValue>>() {});
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
