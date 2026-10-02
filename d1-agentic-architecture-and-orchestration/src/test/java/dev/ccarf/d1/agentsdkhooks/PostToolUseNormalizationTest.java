package dev.ccarf.d1.agentsdkhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ccarf.common.Config;
import dev.ccarf.d1.agentsdkhooks.PostToolUseNormalization.PostToolUseHook;
import org.junit.jupiter.api.Test;

class PostToolUseNormalizationTest {

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

  @Test
  void returnsFinalTextWhenStopReasonIsEndTurnOnTheFirstTurn() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.END_TURN, "How can I help?"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    String result = PostToolUseNormalization.runLoop(client, "Hi",
        PostToolUseNormalization.NORMALIZING_HOOK);

    assertEquals("How can I help?", result);
    assertEquals(1, messageService.requests.size());
  }

  @Test
  void firstRequestCarriesTheUserTurnSystemPromptAndAllThreeTools() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.END_TURN, "How can I help?"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    PostToolUseNormalization.runLoop(client, "Where is my order?",
        PostToolUseNormalization.NORMALIZING_HOOK);

    MessageCreateParams sentParams = messageService.requests.get(0);
    assertEquals(Config.modelMain(), sentParams.model().toString());
    assertEquals(PostToolUseNormalization.SUPPORT_AGENT_SYSTEM_PROMPT,
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
  void withTheNormalizingHookTheModelReceivesNormalizedToolResults() {
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(allThreeToolCalls()),
        messageWithText(StopReason.END_TURN, "Here's everything."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    String result = PostToolUseNormalization.runLoop(client, "Status please",
        PostToolUseNormalization.NORMALIZING_HOOK);

    assertEquals("Here's everything.", result);
    List<ToolResultBlockParam> toolResults = toolResults(messageService.requests.get(1));
    assertEquals(List.of("call_1", "call_2", "call_3"),
        toolResults.stream().map(ToolResultBlockParam::toolUseId).toList());
    assertEquals(List.of(NORMALIZED_CUSTOMER, RAW_ORDER, NORMALIZED_SHIPMENT),
        toolResults.stream().map(PostToolUseNormalizationTest::content).toList());
  }

  @Test
  void withThePassThroughHookTheModelReceivesTheRawToolResults() {
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(allThreeToolCalls()),
        messageWithText(StopReason.END_TURN, "Here's everything."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    PostToolUseNormalization.runLoop(client, "Status please",
        PostToolUseNormalization.PASS_THROUGH_HOOK);

    assertEquals(List.of(RAW_CUSTOMER, RAW_ORDER, RAW_SHIPMENT),
        toolResults(messageService.requests.get(1)).stream()
            .map(PostToolUseNormalizationTest::content)
            .toList());
  }

  // The "verify the model receives consistent data" step: across every tool
  // and every mock record, each date the model sees is an ISO 8601 UTC
  // timestamp and each status is one English word from a known set.
  @Test
  void everyDateAndStatusTheModelReceivesIsInOneConsistentFormat() throws Exception {
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(
            toolUseBlock("call_1", "get_customer", Map.of("customer_id", "CUST-1001")),
            toolUseBlock("call_2", "lookup_order", Map.of("order_id", "ORD-5001")),
            toolUseBlock("call_3", "lookup_order", Map.of("order_id", "ORD-5002")),
            toolUseBlock("call_4", "check_shipping", Map.of("order_id", "ORD-5001")),
            toolUseBlock("call_5", "check_shipping", Map.of("order_id", "ORD-5002"))),
        messageWithText(StopReason.END_TURN, "Done."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    PostToolUseNormalization.runLoop(client, "Everything please",
        PostToolUseNormalization.NORMALIZING_HOOK);

    Set<String> dateFields = Set.of("created_at", "ordered_at", "estimated_delivery");
    Set<String> englishStatuses = Set.of("active", "suspended", "closed", "processing",
        "pending", "shipped", "delivered");
    List<ToolResultBlockParam> toolResults = toolResults(messageService.requests.get(1));
    assertEquals(5, toolResults.size());
    for (ToolResultBlockParam toolResult : toolResults) {
      assertFalse(toolResult.isError().orElse(false), content(toolResult));
      JsonNode output = JSON.readTree(content(toolResult));
      for (String field : dateFields) {
        if (output.has(field)) {
          assertTrue(output.get(field).isTextual()
              && output.get(field).asText().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"),
              field + " is not an ISO 8601 UTC timestamp: " + output);
        }
      }
      assertTrue(englishStatuses.contains(output.get("status").asText()),
          "status is not an English string: " + output);
    }
  }

  @Test
  void theHookReceivesTheToolNameInputAndRawOutput() {
    List<String> seen = new ArrayList<>();
    PostToolUseHook recordingHook = (toolName, toolInput, toolResponse) -> {
      seen.add(toolName + " " + toolInput.get("order_id").convert(String.class) + " " + toolResponse);
      return toolResponse;
    };
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(
            toolUseBlock("call_1", "check_shipping", Map.of("order_id", "ORD-5001"))),
        messageWithText(StopReason.END_TURN, "Done."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    PostToolUseNormalization.runLoop(client, "Where is ORD-5001?", recordingHook);

    assertEquals(List.of("check_shipping ORD-5001 " + RAW_SHIPMENT), seen);
  }

  @Test
  void theHooksReturnValueReplacesWhatTheModelReceives() {
    PostToolUseHook replacingHook = (toolName, toolInput, toolResponse) -> "{\"replaced\":true}";
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(toolUseBlock("call_1", "lookup_order", Map.of("order_id", "ORD-5001"))),
        messageWithText(StopReason.END_TURN, "Done."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    PostToolUseNormalization.runLoop(client, "Order?", replacingHook);

    ToolResultBlockParam toolResult = onlyToolResult(messageService.requests.get(1));
    assertEquals("call_1", toolResult.toolUseId());
    assertEquals("{\"replaced\":true}", content(toolResult));
  }

  @Test
  void errorToolResultsBypassTheHook() {
    List<String> hookCalls = new ArrayList<>();
    PostToolUseHook recordingHook = (toolName, toolInput, toolResponse) -> {
      hookCalls.add(toolName);
      return toolResponse;
    };
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(toolUseBlock("call_1", "lookup_order", Map.of("order_id", "ORD-0000"))),
        messageWithText(StopReason.END_TURN, "I couldn't find that order."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    PostToolUseNormalization.runLoop(client, "Order ORD-0000?", recordingHook);

    ToolResultBlockParam toolResult = onlyToolResult(messageService.requests.get(1));
    assertTrue(toolResult.isError().orElse(false));
    assertEquals("No order found with ID ORD-0000", content(toolResult));
    assertTrue(hookCalls.isEmpty(), "the hook must not see error results: " + hookCalls);
  }

  @Test
  void aHookThatCannotNormalizeSendsAnErrorInsteadOfTheRawData() {
    PostToolUseHook failingHook = (toolName, toolInput, toolResponse) -> {
      throw new IllegalArgumentException("Unknown shipping status code: X");
    };
    StubMessageService messageService = new StubMessageService(
        messageWithToolUse(
            toolUseBlock("call_1", "check_shipping", Map.of("order_id", "ORD-5001"))),
        messageWithText(StopReason.END_TURN, "Sorry, I can't read that shipment."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    PostToolUseNormalization.runLoop(client, "Where is ORD-5001?", failingHook);

    ToolResultBlockParam toolResult = onlyToolResult(messageService.requests.get(1));
    assertEquals("call_1", toolResult.toolUseId());
    assertTrue(toolResult.isError().orElse(false));
    String content = content(toolResult);
    assertTrue(content.contains("could not be normalized"), content);
    assertTrue(content.contains("Unknown shipping status code: X"), content);
    assertFalse(content.contains("04/03/2026"), "raw data must not reach the model: " + content);
  }

  @Test
  void normalizeConvertsGetCustomersEpochTimestampAndNumericStatus() {
    assertEquals(NORMALIZED_CUSTOMER,
        PostToolUseNormalization.normalizeToolOutput("get_customer", Map.of(), RAW_CUSTOMER));
  }

  @Test
  void normalizeConvertsCheckShippingsDayMonthYearDateAndLetterStatus() {
    assertEquals(NORMALIZED_SHIPMENT,
        PostToolUseNormalization.normalizeToolOutput("check_shipping", Map.of(), RAW_SHIPMENT));
  }

  @Test
  void normalizeReadsDayMonthYearAsDayFirstAndPAsPending() {
    String raw = "{\"order_id\":\"ORD-5002\",\"tracking_number\":\"TRK-88232\","
        + "\"estimated_delivery\":\"12/03/2026\",\"status\":\"P\"}";

    assertEquals("{\"order_id\":\"ORD-5002\",\"tracking_number\":\"TRK-88232\","
            + "\"estimated_delivery\":\"2026-03-12T00:00:00Z\",\"status\":\"pending\"}",
        PostToolUseNormalization.normalizeToolOutput("check_shipping", Map.of(), raw));
  }

  @Test
  void normalizePassesLookupOrderAndUnknownToolsThroughUnchanged() {
    assertEquals(RAW_ORDER,
        PostToolUseNormalization.normalizeToolOutput("lookup_order", Map.of(), RAW_ORDER));
    assertEquals("not json at all",
        PostToolUseNormalization.normalizeToolOutput("some_other_tool", Map.of(), "not json at all"));
  }

  @Test
  void normalizeRejectsAnUnknownCustomerStatusCode() {
    String raw = "{\"customer_id\":\"CUST-1001\",\"name\":\"Jane Doe\","
        + "\"created_at\":1709294400,\"status\":9}";

    assertThrows(IllegalArgumentException.class,
        () -> PostToolUseNormalization.normalizeToolOutput("get_customer", Map.of(), raw));
  }

  @Test
  void normalizeRejectsAnUnknownShippingStatusCode() {
    String raw = "{\"order_id\":\"ORD-5001\",\"tracking_number\":\"TRK-88231\","
        + "\"estimated_delivery\":\"04/03/2026\",\"status\":\"X\"}";

    assertThrows(IllegalArgumentException.class,
        () -> PostToolUseNormalization.normalizeToolOutput("check_shipping", Map.of(), raw));
  }

  @Test
  void normalizeRejectsImpossibleOrWronglyFormattedDeliveryDates() {
    for (String date : List.of("31/02/2026", "2026-03-04", "13/13/2026")) {
      String raw = "{\"order_id\":\"ORD-5001\",\"tracking_number\":\"TRK-88231\","
          + "\"estimated_delivery\":\"" + date + "\",\"status\":\"S\"}";

      assertThrows(IllegalArgumentException.class,
          () -> PostToolUseNormalization.normalizeToolOutput("check_shipping", Map.of(), raw),
          date);
    }
  }

  @Test
  void executeToolReturnsEachToolsRawOutputAsJson() {
    assertEquals(RAW_CUSTOMER, content(PostToolUseNormalization.executeTool(
        toolUseBlock("call_1", "get_customer", Map.of("customer_id", "CUST-1001")))));
    assertEquals(RAW_ORDER, content(PostToolUseNormalization.executeTool(
        toolUseBlock("call_2", "lookup_order", Map.of("order_id", "ORD-5001")))));
    assertEquals(RAW_SHIPMENT, content(PostToolUseNormalization.executeTool(
        toolUseBlock("call_3", "check_shipping", Map.of("order_id", "ORD-5001")))));
  }

  @Test
  void lookupMissesAndMissingInputsAreReturnedAsErrorToolResultsRatherThanThrown() {
    ToolResultBlockParam customerMiss = PostToolUseNormalization.executeTool(
        toolUseBlock("call_1", "get_customer", Map.of("customer_id", "CUST-0000")));
    ToolResultBlockParam shipmentMiss = PostToolUseNormalization.executeTool(
        toolUseBlock("call_2", "check_shipping", Map.of("order_id", "ORD-0000")));
    ToolResultBlockParam missingInput = PostToolUseNormalization.executeTool(
        toolUseBlock("call_3", "lookup_order", Map.of()));

    assertTrue(customerMiss.isError().orElse(false));
    assertTrue(shipmentMiss.isError().orElse(false));
    assertTrue(missingInput.isError().orElse(false));
    assertEquals("Missing required input: order_id", content(missingInput));
  }

  @Test
  void throwsAfterExceedingTheSafetyCap() {
    Message[] allToolUse = new Message[MAX_ITERATIONS];
    Arrays.fill(allToolUse, messageWithToolUse(
        toolUseBlock("call_1", "lookup_order", Map.of("order_id", "ORD-5001"))));
    StubMessageService messageService = new StubMessageService(allToolUse);
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalStateException.class, () -> PostToolUseNormalization.runLoop(client,
        "Loop", PostToolUseNormalization.NORMALIZING_HOOK));
    assertEquals(MAX_ITERATIONS, messageService.requests.size());
  }

  @Test
  void throwsForUnhandledStopReason() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.MAX_TOKENS, "truncated..."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalStateException.class, () -> PostToolUseNormalization.runLoop(client,
        "Hi", PostToolUseNormalization.NORMALIZING_HOOK));
  }

  @Test
  void throwsForUnknownToolName() {
    assertThrows(IllegalArgumentException.class, () -> PostToolUseNormalization.executeTool(
        toolUseBlock("call_1", "unknown_tool", Map.of("foo", "bar"))));
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
