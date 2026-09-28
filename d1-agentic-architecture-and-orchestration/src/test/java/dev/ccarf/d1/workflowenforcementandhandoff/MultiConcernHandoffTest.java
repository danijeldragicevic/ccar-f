package dev.ccarf.d1.workflowenforcementandhandoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import dev.ccarf.d1.workflowenforcementandhandoff.MultiConcernHandoff.Concern;
import dev.ccarf.d1.workflowenforcementandhandoff.MultiConcernHandoff.ConcernType;
import dev.ccarf.d1.workflowenforcementandhandoff.MultiConcernHandoff.HandoffSummary;
import dev.ccarf.d1.workflowenforcementandhandoff.MultiConcernHandoff.MultiConcernOutcome;
import dev.ccarf.d1.workflowenforcementandhandoff.MultiConcernHandoff.SessionState;
import dev.ccarf.d1.workflowenforcementandhandoff.MultiConcernHandoff.ToolCallRecord;

class MultiConcernHandoffTest {

  private static final int MAX_ITERATIONS = 20;

  private static final Map<String, Object> RETURN_CONCERN = Map.of(
      "type", "return",
      "findings", "ORD-5004 (espresso machine, $219.00) was delivered 2026-08-10; its return "
          + "window ended 2026-09-09. The customer reports it stopped working.",
      "root_cause", "The return window has passed, so a return needs a human exception.",
      "refund_amount", 219.00,
      "recommended_action", "Approve a warranty return for ORD-5004 and refund $219.00.");

  private static final Map<String, Object> BILLING_CONCERN = Map.of(
      "type", "billing_dispute",
      "findings", "ORD-5004 was charged twice on 2026-08-08: CHG-7001 and CHG-7002, "
          + "$219.00 each.",
      "root_cause", "Duplicate charge; this agent cannot reverse charges.",
      "refund_amount", 219.00,
      "recommended_action", "Reverse the duplicate charge CHG-7002 ($219.00).");

  private static final Map<String, Object> ACCOUNT_CONCERN = Map.of(
      "type", "account_update",
      "findings", "Current shipping address is 12 Old Street, Springfield, IL 62701; the "
          + "customer asked to change it to 48 New Road, Springfield, IL 62704.",
      "root_cause", "This agent has read-only access to account details.",
      "refund_amount", 0,
      "recommended_action", "Update the shipping address to 48 New Road, Springfield, IL 62704.");

  private static final Map<String, Object> COMPLETE_HANDOFF = Map.of(
      "customer_id", "CUST-1001",
      "conversation_summary", "Jane Doe wants to return a broken espresso machine, disputes "
          + "a duplicate charge for it, and asked to update her shipping address.",
      "concerns", List.of(RETURN_CONCERN, BILLING_CONCERN, ACCOUNT_CONCERN));

  @Test
  void firstRequestCarriesTheMessageSystemPromptAndAllFiveTools() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.END_TURN, "How can I help?"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    MultiConcernHandoff.runLoop(client, MultiConcernHandoff.MULTI_CONCERN_MESSAGE);

    MessageCreateParams sentParams = messageService.requests.get(0);
    assertEquals(Config.modelMain(), sentParams.model().toString());
    assertEquals(MultiConcernHandoff.SUPPORT_AGENT_SYSTEM_PROMPT,
        sentParams.system().orElseThrow().string().orElseThrow());
    assertEquals(List.of("get_customer", "lookup_order", "get_billing_history", "get_account",
            "escalate_to_human"),
        sentParams.tools().orElseThrow().stream()
            .map(tool -> tool.tool().map(Tool::name).orElseThrow())
            .toList());
    assertEquals(MultiConcernHandoff.MULTI_CONCERN_MESSAGE,
        sentParams.messages().get(0).content().string().orElseThrow());
  }

  @Test
  void escalateToHumanRequiresTheCustomerSummaryAndAListOfConcerns() {
    Tool tool = MultiConcernHandoff.getEscalateToHumanTool();

    assertEquals(List.of("customer_id", "conversation_summary", "concerns"),
        tool.inputSchema().required().orElseThrow());
  }

  @Test
  void allThreeConcernsInvestigatedInParallelAndCoveredByOneHandoff() {
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(toolUseBlock("call_1", "get_customer",
            Map.of("email", "jane@example.com"))),
        messageWithToolUse(
            toolUseBlock("call_2", "lookup_order", Map.of("order_id", "ORD-5004")),
            toolUseBlock("call_3", "get_billing_history", Map.of("customer_id", "CUST-1001")),
            toolUseBlock("call_4", "get_account", Map.of("customer_id", "CUST-1001"))),
        messageWithToolUse(toolUseBlock("call_5", "escalate_to_human", COMPLETE_HANDOFF)),
        messageWithText(StopReason.END_TURN, "All three concerns went to a human agent."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    MultiConcernOutcome outcome =
        MultiConcernHandoff.runLoop(client, MultiConcernHandoff.MULTI_CONCERN_MESSAGE);

    assertEquals(List.of(
            new ToolCallRecord(1, "get_customer", false),
            new ToolCallRecord(2, "lookup_order", false),
            new ToolCallRecord(2, "get_billing_history", false),
            new ToolCallRecord(2, "get_account", false),
            new ToolCallRecord(3, "escalate_to_human", false)),
        outcome.toolCalls());
    assertEquals("{\"handoff_id\":\"HO-CUST-1001\",\"customer_id\":\"CUST-1001\","
            + "\"concern_count\":3,\"status\":\"queued_for_human_agent\"}",
        onlyToolResult(messageService.requests.get(3)).content().orElseThrow().string()
            .orElseThrow());

    HandoffSummary handoff = outcome.handoff().orElseThrow();
    assertEquals(List.of(ConcernType.RETURN, ConcernType.BILLING_DISPUTE,
        ConcernType.ACCOUNT_UPDATE), handoff.concerns().stream().map(Concern::type).toList());
    assertEquals(438.00, handoff.totalRefundAmount());
    assertEquals(List.of(), MultiConcernHandoff.checkHandoffCoversAllConcerns(outcome,
        MultiConcernHandoff.EXPECTED_DETAILS));
  }

  @Test
  void aHandoffThatOmitsAnInvestigatedConcernIsRejectedUntilItCoversIt() {
    Map<String, Object> missingBilling = new HashMap<>(COMPLETE_HANDOFF);
    missingBilling.put("concerns", List.of(RETURN_CONCERN, ACCOUNT_CONCERN));
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(toolUseBlock("call_1", "get_customer",
            Map.of("email", "jane@example.com"))),
        messageWithToolUse(
            toolUseBlock("call_2", "lookup_order", Map.of("order_id", "ORD-5004")),
            toolUseBlock("call_3", "get_billing_history", Map.of("customer_id", "CUST-1001")),
            toolUseBlock("call_4", "get_account", Map.of("customer_id", "CUST-1001"))),
        messageWithToolUse(toolUseBlock("call_5", "escalate_to_human", missingBilling)),
        messageWithToolUse(toolUseBlock("call_6", "escalate_to_human", COMPLETE_HANDOFF)),
        messageWithText(StopReason.END_TURN, "Escalated."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    MultiConcernOutcome outcome =
        MultiConcernHandoff.runLoop(client, MultiConcernHandoff.MULTI_CONCERN_MESSAGE);

    ToolResultBlockParam rejected = onlyToolResult(messageService.requests.get(3));
    assertTrue(rejected.isError().orElse(false));
    String content = rejected.content().orElseThrow().string().orElseThrow();
    assertTrue(content.startsWith("HANDOFF REJECTED"), content);
    assertTrue(content.contains("billing_dispute"), content);
    assertEquals(3, outcome.handoff().orElseThrow().concerns().size());
  }

  @Test
  void checkReportsConcernsInvestigatedOneAfterAnother() {
    MultiConcernOutcome outcome = new MultiConcernOutcome("Escalated.",
        Optional.of(completeHandoff()), List.of(
            new ToolCallRecord(1, "get_customer", false),
            new ToolCallRecord(2, "lookup_order", false),
            new ToolCallRecord(3, "get_billing_history", false),
            new ToolCallRecord(4, "get_account", false)));

    assertEquals(List.of("the concerns were investigated one after another (turns [2, 3, 4]) "
            + "rather than in parallel"),
        MultiConcernHandoff.checkHandoffCoversAllConcerns(outcome,
            MultiConcernHandoff.EXPECTED_DETAILS));
  }

  @Test
  void checkReportsAnOmittedConcernAMissingDetailAndAMissingInvestigation() {
    HandoffSummary handoff = new HandoffSummary("CUST-1001", "Summary", List.of(
        new Concern(ConcernType.RETURN, "The espresso machine is broken.", "Window passed.",
            219.00, "Approve the return."),
        new Concern(ConcernType.ACCOUNT_UPDATE, "New address: 48 New Road.", "Read-only.",
            0, "Update the address.")));
    MultiConcernOutcome outcome = new MultiConcernOutcome("Escalated.", Optional.of(handoff),
        List.of(new ToolCallRecord(2, "lookup_order", false),
            new ToolCallRecord(2, "get_billing_history", true),
            new ToolCallRecord(2, "get_account", false)));

    assertEquals(List.of(
            "billing_dispute was never investigated (no successful get_billing_history call)",
            "the handoff's return entry never mentions \"ORD-5004\"",
            "the handoff omits the billing_dispute concern"),
        MultiConcernHandoff.checkHandoffCoversAllConcerns(outcome,
            MultiConcernHandoff.EXPECTED_DETAILS));
  }

  @Test
  void checkReportsAMissingHandoff() {
    MultiConcernOutcome outcome = new MultiConcernOutcome("Done.", Optional.empty(), List.of(
        new ToolCallRecord(1, "lookup_order", false),
        new ToolCallRecord(1, "get_billing_history", false),
        new ToolCallRecord(1, "get_account", false)));

    assertEquals(List.of("no handoff was produced"),
        MultiConcernHandoff.checkHandoffCoversAllConcerns(outcome,
            MultiConcernHandoff.EXPECTED_DETAILS));
  }

  @Test
  void compileHandoffRejectsACustomerIdGetCustomerNeverReturned() {
    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
        () -> MultiConcernHandoff.compileHandoff(input(COMPLETE_HANDOFF), new SessionState()));

    assertTrue(exception.getMessage().contains(
        "customer_id CUST-1001 was not returned by get_customer"), exception.getMessage());
  }

  @Test
  void compileHandoffReportsEveryConcernProblemWithItsPosition() {
    Map<String, Object> placeholderAction = new HashMap<>(BILLING_CONCERN);
    placeholderAction.put("recommended_action", "TBD");
    Map<String, Object> badType = new HashMap<>(ACCOUNT_CONCERN);
    badType.put("type", "shipping");
    Map<String, Object> broken = new HashMap<>(COMPLETE_HANDOFF);
    broken.put("concerns", List.of(RETURN_CONCERN, placeholderAction, badType, RETURN_CONCERN));

    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
        () -> MultiConcernHandoff.compileHandoff(input(broken), identifiedJane()));

    String message = exception.getMessage();
    assertTrue(message.contains("concerns[1] (billing_dispute): recommended_action contains "
        + "placeholder text"), message);
    assertTrue(message.contains("concerns[2]: type \"shipping\" is not one of"), message);
    assertTrue(message.contains("concerns lists return more than once"), message);
  }

  @Test
  void compileHandoffRejectsMissingOrEmptyConcerns() {
    Map<String, Object> noConcerns = new HashMap<>(COMPLETE_HANDOFF);
    noConcerns.remove("concerns");
    Map<String, Object> emptyConcerns = new HashMap<>(COMPLETE_HANDOFF);
    emptyConcerns.put("concerns", List.of());

    assertTrue(assertThrows(IllegalArgumentException.class,
        () -> MultiConcernHandoff.compileHandoff(input(noConcerns), identifiedJane()))
        .getMessage().contains("concerns is missing"));
    assertTrue(assertThrows(IllegalArgumentException.class,
        () -> MultiConcernHandoff.compileHandoff(input(emptyConcerns), identifiedJane()))
        .getMessage().contains("concerns is empty"));
  }

  @Test
  void compileHandoffAcceptsASingleConcernWhenOnlyThatOneWasInvestigated() {
    SessionState session = identifiedJane();
    MultiConcernHandoff.executeTool(
        toolUseBlock("call_1", "lookup_order", Map.of("order_id", "ORD-5004")), session);
    recordLastCall(session, "lookup_order");
    Map<String, Object> returnOnly = new HashMap<>(COMPLETE_HANDOFF);
    returnOnly.put("concerns", List.of(RETURN_CONCERN));

    HandoffSummary handoff = MultiConcernHandoff.compileHandoff(input(returnOnly), session);

    assertEquals(1, handoff.concerns().size());
  }

  @Test
  void lookupsReturnJsonAndMissesAreErrorResults() {
    SessionState session = new SessionState();

    ToolResultBlockParam account = MultiConcernHandoff.executeTool(
        toolUseBlock("call_1", "get_account", Map.of("customer_id", "CUST-1001")), session);
    ToolResultBlockParam billing = MultiConcernHandoff.executeTool(
        toolUseBlock("call_2", "get_billing_history", Map.of("customer_id", "CUST-1002")),
        session);

    assertEquals("{\"customer_id\":\"CUST-1001\",\"email\":\"jane@example.com\","
            + "\"shipping_address\":\"12 Old Street, Springfield, IL 62701\"}",
        account.content().orElseThrow().string().orElseThrow());
    assertTrue(billing.isError().orElse(false));
  }

  @Test
  void throwsAfterExceedingTheSafetyCap() {
    Message[] allToolUse = new Message[MAX_ITERATIONS];
    Arrays.fill(allToolUse, messageWithToolUse(
        toolUseBlock("call_1", "lookup_order", Map.of("order_id", "ORD-5004"))));
    StubMessageService messageService = new StubMessageService(allToolUse);
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalStateException.class, () -> MultiConcernHandoff.runLoop(client, "Loop"));
    assertEquals(MAX_ITERATIONS, messageService.requests.size());
  }

  @Test
  void throwsForUnhandledStopReason() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.MAX_TOKENS, "truncated..."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalStateException.class, () -> MultiConcernHandoff.runLoop(client, "Hi"));
  }

  @Test
  void throwsForUnknownToolName() {
    assertThrows(IllegalArgumentException.class, () -> MultiConcernHandoff.executeTool(
        toolUseBlock("call_1", "unknown_tool", Map.of("foo", "bar")), new SessionState()));
  }

  // A session in which get_customer has returned Jane's customer ID.
  private static SessionState identifiedJane() {
    SessionState session = new SessionState();
    MultiConcernHandoff.executeTool(
        toolUseBlock("call_0", "get_customer", Map.of("email", "jane@example.com")), session);
    return session;
  }

  // executeTool alone doesn't log the call - the loop does - so tests that
  // bypass the loop record it themselves.
  private static void recordLastCall(SessionState session, String toolName) {
    session.recordToolCall(new ToolCallRecord(1, toolName, false));
  }

  private static HandoffSummary completeHandoff() {
    return MultiConcernHandoff.compileHandoff(input(COMPLETE_HANDOFF), identifiedJane());
  }

  // Converts a plain map into tool input, the shape executeTool hands to compileHandoff.
  private static Map<String, JsonValue> input(Map<String, Object> values) {
    return JsonValue.from(values).convert(new TypeReference<Map<String, JsonValue>>() {});
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
