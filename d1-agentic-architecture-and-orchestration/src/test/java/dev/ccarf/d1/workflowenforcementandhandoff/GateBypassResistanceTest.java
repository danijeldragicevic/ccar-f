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
import dev.ccarf.d1.workflowenforcementandhandoff.GateBypassResistance.BypassAttemptReport;
import dev.ccarf.d1.workflowenforcementandhandoff.GateBypassResistance.Outcome;
import dev.ccarf.d1.workflowenforcementandhandoff.GateBypassResistance.SessionState;
import dev.ccarf.d1.workflowenforcementandhandoff.GateBypassResistance.ToolCallRecord;
import org.junit.jupiter.api.Test;

class GateBypassResistanceTest {

  private static final int MAX_ITERATIONS = 20;

  @Test
  void firstRequestCarriesTheBypassMessageSystemPromptAndAllThreeTools() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.END_TURN, "How can I help?"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    GateBypassResistance.runBypassAttempt(client, GateBypassResistance.BYPASS_ATTEMPT_MESSAGE);

    MessageCreateParams sentParams = messageService.requests.get(0);
    assertEquals(Config.modelMain(), sentParams.model().toString());
    assertEquals(GateBypassResistance.SUPPORT_AGENT_SYSTEM_PROMPT,
        sentParams.system().orElseThrow().string().orElseThrow());
    assertEquals(List.of("get_customer", "lookup_order", "process_refund"),
        sentParams.tools().orElseThrow().stream()
            .map(tool -> tool.tool().map(Tool::name).orElseThrow())
            .toList());
    assertEquals(1, sentParams.messages().size());
    assertEquals(GateBypassResistance.BYPASS_ATTEMPT_MESSAGE,
        sentParams.messages().get(0).content().string().orElseThrow());
  }

  @Test
  void onlyTheFirstTurnForcesAProcessRefundCall() {
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(refundCall("call_1", "CUST-1001", 89.99)),
        messageWithText(StopReason.END_TURN, "I need to verify you first."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    GateBypassResistance.runBypassAttempt(client, GateBypassResistance.BYPASS_ATTEMPT_MESSAGE);

    assertEquals("process_refund", messageService.requests.get(0).toolChoice().orElseThrow()
        .tool().orElseThrow().name());
    assertTrue(messageService.requests.get(1).toolChoice().isEmpty());
  }

  @Test
  void bypassAttemptIsBlockedThenVerifiedThenRefunded() {
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(refundCall("call_1", "CUST-1001", 89.99)),
        messageWithToolUse(toolUseBlock("call_2", "get_customer",
            Map.of("email", "jane@example.com"))),
        messageWithToolUse(refundCall("call_3", "CUST-1001", 89.99)),
        messageWithText(StopReason.END_TURN, "Your refund has been processed."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    BypassAttemptReport report =
        GateBypassResistance.runBypassAttempt(client, GateBypassResistance.BYPASS_ATTEMPT_MESSAGE);

    assertEquals("Your refund has been processed.", report.finalResponse());
    assertEquals(List.of(
            new ToolCallRecord("process_refund", Outcome.BLOCKED_BY_GATE),
            new ToolCallRecord("get_customer", Outcome.SUCCESS),
            new ToolCallRecord("process_refund", Outcome.SUCCESS)),
        report.toolCalls());
    assertEquals(List.of(), GateBypassResistance.checkGateHeld(report.toolCalls()));

    ToolResultBlockParam blocked = onlyToolResult(messageService.requests.get(1));
    assertTrue(blocked.isError().orElse(false));
    String content = blocked.content().orElseThrow().string().orElseThrow();
    assertTrue(content.startsWith(GateBypassResistance.GATE_BLOCKED_PREFIX), content);
    assertFalse(content.contains("refund_id"), "the refund must not have been processed: " + content);
  }

  @Test
  void repeatedBypassAttemptsWithoutVerificationStayBlocked() {
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(refundCall("call_1", "CUST-1001", 89.99)),
        messageWithToolUse(refundCall("call_2", "CUST-1001", 89.99)),
        messageWithText(StopReason.END_TURN, "I can't process this refund."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    BypassAttemptReport report =
        GateBypassResistance.runBypassAttempt(client, GateBypassResistance.BYPASS_ATTEMPT_MESSAGE);

    assertEquals(List.of(
            new ToolCallRecord("process_refund", Outcome.BLOCKED_BY_GATE),
            new ToolCallRecord("process_refund", Outcome.BLOCKED_BY_GATE)),
        report.toolCalls());
    assertEquals(
        List.of("the agent did not call get_customer after the gate blocked the refund"),
        GateBypassResistance.checkGateHeld(report.toolCalls()));
  }

  @Test
  void refundForAnUnverifiedCustomerStaysBlockedEvenAfterGetCustomer() {
    SessionState session = new SessionState();
    GateBypassResistance.executeTool(refundCall("call_1", "CUST-1002", 24.50), session);
    GateBypassResistance.executeTool(
        toolUseBlock("call_2", "get_customer", Map.of("email", "john@example.com")), session);
    GateBypassResistance.executeTool(refundCall("call_3", "CUST-1002", 24.50), session);

    assertEquals(List.of(
            new ToolCallRecord("process_refund", Outcome.BLOCKED_BY_GATE),
            new ToolCallRecord("get_customer", Outcome.SUCCESS),
            new ToolCallRecord("process_refund", Outcome.BLOCKED_BY_GATE)),
        session.toolCalls());
    assertEquals(List.of("the refund was not retried successfully after verification"),
        GateBypassResistance.checkGateHeld(session.toolCalls()));
  }

  @Test
  void aLookupMissIsRecordedAsAnErrorNotAsAGateBlock() {
    SessionState session = new SessionState();

    GateBypassResistance.executeTool(
        toolUseBlock("call_1", "get_customer", Map.of("email", "nobody@example.com")), session);

    assertEquals(List.of(new ToolCallRecord("get_customer", Outcome.ERROR)), session.toolCalls());
  }

  @Test
  void checkReportsAFailureWhenTheGateNeverBlocked() {
    List<String> failures = GateBypassResistance.checkGateHeld(List.of(
        new ToolCallRecord("get_customer", Outcome.SUCCESS),
        new ToolCallRecord("process_refund", Outcome.SUCCESS)));

    assertEquals(List.of("the gate never blocked a process_refund call"), failures);
  }

  @Test
  void checkReportsAFailureWhenVerificationRanBeforeTheBypassAttempt() {
    List<String> failures = GateBypassResistance.checkGateHeld(List.of(
        new ToolCallRecord("get_customer", Outcome.SUCCESS),
        new ToolCallRecord("process_refund", Outcome.BLOCKED_BY_GATE),
        new ToolCallRecord("get_customer", Outcome.SUCCESS),
        new ToolCallRecord("process_refund", Outcome.SUCCESS)));

    assertEquals(
        List.of("get_customer ran before the refund attempt, so the bypass was never tried"),
        failures);
  }

  @Test
  void checkReportsARefundThatSucceededBeforeAnyVerification() {
    List<String> failures = GateBypassResistance.checkGateHeld(List.of(
        new ToolCallRecord("process_refund", Outcome.SUCCESS)));

    assertEquals(List.of("a refund succeeded before get_customer verified the customer",
        "the gate never blocked a process_refund call"), failures);
  }

  @Test
  void throwsAfterExceedingTheSafetyCap() {
    Message[] allToolUse = new Message[MAX_ITERATIONS];
    Arrays.fill(allToolUse, messageWithToolUse(refundCall("call_1", "CUST-1001", 89.99)));
    StubMessageService messageService = new StubMessageService(allToolUse);
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalStateException.class,
        () -> GateBypassResistance.runBypassAttempt(client, "Loop"));
    assertEquals(MAX_ITERATIONS, messageService.requests.size());
  }

  @Test
  void throwsForUnhandledStopReason() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.MAX_TOKENS, "truncated..."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalStateException.class,
        () -> GateBypassResistance.runBypassAttempt(client, "Hi"));
  }

  @Test
  void throwsForUnknownToolName() {
    assertThrows(IllegalArgumentException.class, () -> GateBypassResistance.executeTool(
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
