package dev.ccarf.d1.agentsdkhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.ContentBlock;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ccarf.common.Config;
import dev.ccarf.d1.agentsdkhooks.PostToolUseNormalizationHook.HookCallback;
import dev.ccarf.d1.agentsdkhooks.PostToolUseNormalizationHook.HookJsonOutput;
import dev.ccarf.d1.agentsdkhooks.PostToolUseNormalizationHook.HookMatcher;
import dev.ccarf.d1.agentsdkhooks.PostToolUseNormalizationHook.PostToolUseHookInput;
import dev.ccarf.d1.agentsdkhooks.PostToolUseNormalizationHook.PostToolUseSpecificOutput;
import org.junit.jupiter.api.Test;

class PostToolUseNormalizationHookTest {

  private static final int MAX_ITERATIONS = 20;

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String RAW_CUSTOMER =
      "{\"customer_id\":\"CUST-1001\",\"name\":\"Jane Doe\",\"created_at\":1709294400,\"status\":1}";
  private static final String RAW_ORDER = "{\"order_id\":\"ORD-5001\",\"customer_id\":\"CUST-1001\","
      + "\"ordered_at\":\"2026-03-02T09:30:00Z\",\"status\":\"shipped\"}";
  private static final String RAW_SHIPMENT = "{\"order_id\":\"ORD-5001\","
      + "\"tracking_number\":\"TRK-88231\",\"estimated_delivery\":\"04/03/2026\",\"status\":\"S\"}";

  private static final String NORMALIZED_CUSTOMER = "{\"customer_id\":\"CUST-1001\","
      + "\"name\":\"Jane Doe\",\"created_at\":\"2024-03-01T12:00:00Z\",\"status\":\"active\"}";
  private static final String NORMALIZED_SHIPMENT = "{\"order_id\":\"ORD-5001\","
      + "\"tracking_number\":\"TRK-88231\",\"estimated_delivery\":\"2026-03-04T00:00:00Z\","
      + "\"status\":\"shipped\"}";

  // --- the agent loop ---

  @Test
  void returnsFinalTextWhenStopReasonIsEndTurnOnTheFirstTurn() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.END_TURN, "How can I help?"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    String result = PostToolUseNormalizationHook.runLoop(client, "Hi",
        PostToolUseNormalizationHook.HOOKS);

    assertEquals("How can I help?", result);
    assertEquals(1, messageService.requests.size());
  }

  @Test
  void firstRequestCarriesTheUserTurnSystemPromptAndAllThreeTools() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.END_TURN, "How can I help?"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    PostToolUseNormalizationHook.runLoop(client, "Where is my order?",
        PostToolUseNormalizationHook.HOOKS);

    MessageCreateParams sentParams = messageService.requests.get(0);
    assertEquals(Config.modelMain(), sentParams.model().toString());
    assertEquals(PostToolUseNormalizationHook.SUPPORT_AGENT_SYSTEM_PROMPT,
        sentParams.system().orElseThrow().string().orElseThrow());
    assertEquals(List.of("get_customer", "lookup_order", "check_shipping"),
        sentParams.tools().orElseThrow().stream()
            .map(tool -> tool.tool().map(Tool::name).orElseThrow())
            .toList());
    assertEquals(1, sentParams.messages().size());
    MessageParam userTurn = sentParams.messages().get(0);
    assertEquals(MessageParam.Role.USER, userTurn.role());
    assertEquals("Where is my order?", userTurn.content().string().orElseThrow());
  }

  @Test
  void throwsAfterExceedingTheSafetyCap() {
    Message[] allToolUse = new Message[MAX_ITERATIONS];
    Arrays.fill(allToolUse, messageWithToolUse(
        toolUseBlock("call_1", "lookup_order", Map.of("order_id", "ORD-5001"))));
    StubMessageService messageService = new StubMessageService(allToolUse);
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalStateException.class, () -> PostToolUseNormalizationHook.runLoop(client,
        "Loop", PostToolUseNormalizationHook.HOOKS));
    assertEquals(MAX_ITERATIONS, messageService.requests.size());
  }

  @Test
  void throwsForUnhandledStopReason() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.MAX_TOKENS, "truncated..."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalStateException.class, () -> PostToolUseNormalizationHook.runLoop(client,
        "Hi", PostToolUseNormalizationHook.HOOKS));
  }

  // --- the hook registration and how the loop runs it ---

  @Test
  void hooksRegistersOnePostToolUseMatcherThatCoversEveryTool() {
    List<HookMatcher> postToolUse =
        PostToolUseNormalizationHook.HOOKS.get(PostToolUseNormalizationHook.POST_TOOL_USE);

    assertEquals(1, PostToolUseNormalizationHook.HOOKS.size());
    assertEquals(1, postToolUse.size());
    assertEquals(1, postToolUse.get(0).hooks().size());
    for (String toolName : List.of("get_customer", "lookup_order", "check_shipping", "any_tool")) {
      assertTrue(postToolUse.get(0).matches(toolName), toolName);
    }
  }

  @Test
  void withTheHookRegisteredTheModelReceivesNormalizedToolResults() {
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(allThreeToolCalls()),
        messageWithText(StopReason.END_TURN, "Here's everything."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    String result = PostToolUseNormalizationHook.runLoop(client, "Status please",
        PostToolUseNormalizationHook.HOOKS);

    assertEquals("Here's everything.", result);
    List<ToolResultBlockParam> toolResults = toolResults(messageService.requests.get(1));
    assertEquals(List.of("call_1", "call_2", "call_3"),
        toolResults.stream().map(ToolResultBlockParam::toolUseId).toList());
    assertEquals(List.of(NORMALIZED_CUSTOMER, RAW_ORDER, NORMALIZED_SHIPMENT),
        toolResults.stream().map(PostToolUseNormalizationHookTest::content).toList());
  }

  @Test
  void withNoHooksRegisteredTheModelReceivesTheRawToolResults() {
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(allThreeToolCalls()),
        messageWithText(StopReason.END_TURN, "Here's everything."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    PostToolUseNormalizationHook.runLoop(client, "Status please", Map.of());

    assertEquals(List.of(RAW_CUSTOMER, RAW_ORDER, RAW_SHIPMENT),
        toolResults(messageService.requests.get(1)).stream()
            .map(PostToolUseNormalizationHookTest::content)
            .toList());
  }

  @Test
  void theCallbackReceivesTheEventNameToolNameInputRawResponseAndToolUseId() {
    List<String> seen = new ArrayList<>();
    HookCallback recordingCallback = (input, toolUseId) -> {
      seen.add(input.hookEventName() + " " + input.toolName() + " "
          + input.toolInput().get("order_id").convert(String.class) + " "
          + input.toolResponse() + " " + toolUseId);
      return HookJsonOutput.noChange();
    };
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(
            toolUseBlock("call_1", "check_shipping", Map.of("order_id", "ORD-5001"))),
        messageWithText(StopReason.END_TURN, "Done."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    PostToolUseNormalizationHook.runLoop(client, "Where is ORD-5001?",
        postToolUse(".*", recordingCallback));

    assertEquals(List.of("PostToolUse check_shipping ORD-5001 " + RAW_SHIPMENT + " call_1"), seen);
  }

  @Test
  void updatedToolOutputReplacesWhatTheModelReceives() {
    HookCallback replacingCallback = (input, toolUseId) ->
        HookJsonOutput.updatedToolOutput(json("{\"replaced\":true}"));
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(toolUseBlock("call_1", "lookup_order", Map.of("order_id", "ORD-5001"))),
        messageWithText(StopReason.END_TURN, "Done."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    PostToolUseNormalizationHook.runLoop(client, "Order?", postToolUse(".*", replacingCallback));

    ToolResultBlockParam toolResult = onlyToolResult(messageService.requests.get(1));
    assertEquals("call_1", toolResult.toolUseId());
    assertEquals("{\"replaced\":true}", content(toolResult));
  }

  @Test
  void aCallbackOnlyRunsForToolsItsMatcherMatches() {
    List<String> calledFor = new ArrayList<>();
    HookCallback recordingCallback = (input, toolUseId) -> {
      calledFor.add(input.toolName());
      return HookJsonOutput.noChange();
    };
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(allThreeToolCalls()),
        messageWithText(StopReason.END_TURN, "Done."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    PostToolUseNormalizationHook.runLoop(client, "Status please",
        postToolUse("get_customer|check_shipping", recordingCallback));

    assertEquals(List.of("get_customer", "check_shipping"), calledFor);
  }

  @Test
  void matchingCallbacksRunInOrderEachSeeingThePreviousOutput() {
    HookCallback first = (input, toolUseId) ->
        HookJsonOutput.updatedToolOutput(json("{\"step\":\"first\"}"));
    HookCallback second = (input, toolUseId) -> HookJsonOutput.updatedToolOutput(
        json("{\"step\":\"second\",\"saw\":" + input.toolResponse() + "}"));
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(toolUseBlock("call_1", "lookup_order", Map.of("order_id", "ORD-5001"))),
        messageWithText(StopReason.END_TURN, "Done."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    PostToolUseNormalizationHook.runLoop(client, "Order?", Map.of(
        PostToolUseNormalizationHook.POST_TOOL_USE,
        List.of(new HookMatcher(".*", List.of(first, second)))));

    assertEquals("{\"step\":\"second\",\"saw\":{\"step\":\"first\"}}",
        content(onlyToolResult(messageService.requests.get(1))));
  }

  @Test
  void errorToolResultsBypassTheHooks() {
    List<String> calledFor = new ArrayList<>();
    HookCallback recordingCallback = (input, toolUseId) -> {
      calledFor.add(input.toolName());
      return HookJsonOutput.noChange();
    };
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(toolUseBlock("call_1", "lookup_order", Map.of("order_id", "ORD-0000"))),
        messageWithText(StopReason.END_TURN, "I couldn't find that order."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    PostToolUseNormalizationHook.runLoop(client, "Order ORD-0000?",
        postToolUse(".*", recordingCallback));

    ToolResultBlockParam toolResult = onlyToolResult(messageService.requests.get(1));
    assertTrue(toolResult.isError().orElse(false));
    assertEquals("No order found with ID ORD-0000", content(toolResult));
    assertTrue(calledFor.isEmpty(), "hooks must not see error results: " + calledFor);
  }

  @Test
  void aCallbackThatCannotNormalizeSendsAnErrorInsteadOfTheRawData() {
    HookCallback failingCallback = (input, toolUseId) -> {
      throw new IllegalArgumentException("Unknown shipping status code: X");
    };
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(
            toolUseBlock("call_1", "check_shipping", Map.of("order_id", "ORD-5001"))),
        messageWithText(StopReason.END_TURN, "Sorry, I can't read that shipment."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    PostToolUseNormalizationHook.runLoop(client, "Where is ORD-5001?",
        postToolUse(".*", failingCallback));

    ToolResultBlockParam toolResult = onlyToolResult(messageService.requests.get(1));
    assertEquals("call_1", toolResult.toolUseId());
    assertTrue(toolResult.isError().orElse(false));
    String content = content(toolResult);
    assertTrue(content.contains("could not be normalized"), content);
    assertTrue(content.contains("Unknown shipping status code: X"), content);
    assertFalse(content.contains("04/03/2026"), "raw data must not reach the model: " + content);
  }

  // --- the normalizing callback itself ---

  @Test
  void normalizeRewritesGetCustomersEpochTimestampAndNumericStatus() {
    PostToolUseSpecificOutput output = updatedOutput(
        PostToolUseNormalizationHook.normalizeToolResponse(hookInput("get_customer", RAW_CUSTOMER),
            "call_1"));

    assertEquals("PostToolUse", output.hookEventName());
    assertEquals(json(NORMALIZED_CUSTOMER), output.updatedToolOutput());
  }

  @Test
  void normalizeRewritesCheckShippingsDayMonthYearDateAndLetterStatus() {
    PostToolUseSpecificOutput output = updatedOutput(
        PostToolUseNormalizationHook.normalizeToolResponse(
            hookInput("check_shipping", RAW_SHIPMENT), "call_1"));

    assertEquals("PostToolUse", output.hookEventName());
    assertEquals(json(NORMALIZED_SHIPMENT), output.updatedToolOutput());
  }

  @Test
  void normalizeReadsDayMonthYearAsDayFirstAndPAsPending() {
    String raw = "{\"order_id\":\"ORD-5002\",\"tracking_number\":\"TRK-88232\","
        + "\"estimated_delivery\":\"12/03/2026\",\"status\":\"P\"}";

    PostToolUseSpecificOutput output = updatedOutput(
        PostToolUseNormalizationHook.normalizeToolResponse(hookInput("check_shipping", raw),
            "call_1"));

    assertEquals(json("{\"order_id\":\"ORD-5002\",\"tracking_number\":\"TRK-88232\","
            + "\"estimated_delivery\":\"2026-03-12T00:00:00Z\",\"status\":\"pending\"}"),
        output.updatedToolOutput());
  }

  @Test
  void normalizeDoesNotModifyTheToolResponseItWasGiven() {
    PostToolUseHookInput input = hookInput("get_customer", RAW_CUSTOMER);

    PostToolUseNormalizationHook.normalizeToolResponse(input, "call_1");

    assertEquals(json(RAW_CUSTOMER), input.toolResponse());
  }

  @Test
  void normalizeReturnsNoChangeForLookupOrderAndUnknownTools() {
    assertTrue(PostToolUseNormalizationHook.normalizeToolResponse(
        hookInput("lookup_order", RAW_ORDER), "call_1").hookSpecificOutput().isEmpty());
    assertTrue(PostToolUseNormalizationHook.normalizeToolResponse(
        hookInput("some_other_tool", "{\"status\":7}"), "call_2").hookSpecificOutput().isEmpty());
  }

  @Test
  void normalizeRejectsAnUnknownCustomerStatusCode() {
    String raw = "{\"customer_id\":\"CUST-1001\",\"name\":\"Jane Doe\","
        + "\"created_at\":1709294400,\"status\":9}";

    assertThrows(IllegalArgumentException.class, () ->
        PostToolUseNormalizationHook.normalizeToolResponse(hookInput("get_customer", raw), "call_1"));
  }

  @Test
  void normalizeRejectsAnUnknownShippingStatusCode() {
    String raw = "{\"order_id\":\"ORD-5001\",\"tracking_number\":\"TRK-88231\","
        + "\"estimated_delivery\":\"04/03/2026\",\"status\":\"X\"}";

    assertThrows(IllegalArgumentException.class, () ->
        PostToolUseNormalizationHook.normalizeToolResponse(hookInput("check_shipping", raw),
            "call_1"));
  }

  @Test
  void normalizeRejectsImpossibleOrWronglyFormattedDeliveryDates() {
    for (String date : List.of("31/02/2026", "2026-03-04", "13/13/2026")) {
      String raw = "{\"order_id\":\"ORD-5001\",\"tracking_number\":\"TRK-88231\","
          + "\"estimated_delivery\":\"" + date + "\",\"status\":\"S\"}";

      assertThrows(IllegalArgumentException.class, () ->
          PostToolUseNormalizationHook.normalizeToolResponse(hookInput("check_shipping", raw),
              "call_1"), date);
    }
  }

  // --- the tools ---

  @Test
  void executeToolReturnsEachToolsRawOutputAsJson() {
    assertEquals(RAW_CUSTOMER, content(PostToolUseNormalizationHook.executeTool(
        toolUseBlock("call_1", "get_customer", Map.of("customer_id", "CUST-1001")))));
    assertEquals(RAW_ORDER, content(PostToolUseNormalizationHook.executeTool(
        toolUseBlock("call_2", "lookup_order", Map.of("order_id", "ORD-5001")))));
    assertEquals(RAW_SHIPMENT, content(PostToolUseNormalizationHook.executeTool(
        toolUseBlock("call_3", "check_shipping", Map.of("order_id", "ORD-5001")))));
  }

  @Test
  void lookupMissesAndMissingInputsAreReturnedAsErrorToolResultsRatherThanThrown() {
    ToolResultBlockParam customerMiss = PostToolUseNormalizationHook.executeTool(
        toolUseBlock("call_1", "get_customer", Map.of("customer_id", "CUST-0000")));
    ToolResultBlockParam shipmentMiss = PostToolUseNormalizationHook.executeTool(
        toolUseBlock("call_2", "check_shipping", Map.of("order_id", "ORD-0000")));
    ToolResultBlockParam missingInput = PostToolUseNormalizationHook.executeTool(
        toolUseBlock("call_3", "lookup_order", Map.of()));

    assertTrue(customerMiss.isError().orElse(false));
    assertTrue(shipmentMiss.isError().orElse(false));
    assertTrue(missingInput.isError().orElse(false));
    assertEquals("Missing required input: order_id", content(missingInput));
  }

  @Test
  void throwsForUnknownToolName() {
    assertThrows(IllegalArgumentException.class, () -> PostToolUseNormalizationHook.executeTool(
        toolUseBlock("call_1", "unknown_tool", Map.of("foo", "bar"))));
  }

  // --- helpers ---

  private static Map<String, List<HookMatcher>> postToolUse(String matcher, HookCallback callback) {
    return Map.of(PostToolUseNormalizationHook.POST_TOOL_USE,
        List.of(new HookMatcher(matcher, List.of(callback))));
  }

  private static PostToolUseHookInput hookInput(String toolName, String toolResponse) {
    return new PostToolUseHookInput("PostToolUse", toolName, Map.of(), json(toolResponse));
  }

  private static PostToolUseSpecificOutput updatedOutput(HookJsonOutput output) {
    return output.hookSpecificOutput().orElseThrow();
  }

  private static JsonNode json(String json) {
    try {
      return JSON.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private static ToolUseBlock[] allThreeToolCalls() {
    return new ToolUseBlock[] {
        toolUseBlock("call_1", "get_customer", Map.of("customer_id", "CUST-1001")),
        toolUseBlock("call_2", "lookup_order", Map.of("order_id", "ORD-5001")),
        toolUseBlock("call_3", "check_shipping", Map.of("order_id", "ORD-5001"))};
  }

  private static String content(ToolResultBlockParam toolResult) {
    return toolResult.content().orElseThrow().string().orElseThrow();
  }

  // Returns the single tool_result block carried by the last message of a captured request.
  private static ToolResultBlockParam onlyToolResult(MessageCreateParams params) {
    List<ToolResultBlockParam> toolResults = toolResults(params);
    assertEquals(1, toolResults.size());
    return toolResults.get(0);
  }

  // Extracts the tool_result blocks carried by the last message of a captured request.
  private static List<ToolResultBlockParam> toolResults(MessageCreateParams params) {
    MessageParam lastMessage = params.messages().get(params.messages().size() - 1);
    return lastMessage.content().blockParams().orElseThrow().stream()
        .map(block -> block.toolResult().orElseThrow())
        .toList();
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
