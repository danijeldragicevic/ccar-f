package dev.ccarf.d1.agentsdkhooks;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.ccarf.common.AnthropicClients;
import dev.ccarf.common.Config;

/**
 * Exercise 1.5.1
 * Create a customer support agent with three tools that return data in
 * different formats, and implement a PostToolUse hook that normalizes all
 * dates to ISO 8601 and all status codes to human-readable English strings
 * before the model sees the results. Verify the model receives consistent
 * data across all tool results.
 *
 * <p>The three tools stand in for three MCP servers backed by different
 * systems, each with its own conventions:</p>
 * <ul>
 *   <li>get_customer - Unix epoch timestamps, numeric status codes
 *       ({@link CustomerRecord})</li>
 *   <li>lookup_order - ISO 8601 timestamps, English status strings
 *       ({@link OrderRecord})</li>
 *   <li>check_shipping - DD/MM/YYYY dates, single-character status codes
 *       ({@link ShipmentRecord})</li>
 * </ul>
 * Left as-is, the model has to interpret three date formats and three status
 * representations on every iteration - and gets it wrong some of the time,
 * e.g. reading "04/03/2026" as April 3rd, or "P" as "processed" instead of
 * "pending". Asking it in the prompt to normalize is still probabilistic; a
 * PostToolUse hook does it in code, every time.
 *
 * <p>Note on the Agent SDK: hooks (PreToolUse, PostToolUse, ...) are a
 * feature of the Claude Agent SDK (Python/TypeScript), which the Java SDK
 * doesn't have. Here the hook is modeled in our own Messages API tool loop:
 * {@link PostToolUseHook} is called by the loop after a tool has executed
 * and before its result goes into the tool_result block - exactly the
 * position of the SDK's PostToolUse hook, and its return value plays the
 * role of the SDK's {@code updatedToolOutput}. The tool has already run by
 * the time the hook fires, so a PostToolUse hook can transform what the
 * model sees, but can never block or undo what the tool did - that's what
 * PreToolUse is for (Exercises 1.5.2 and 1.5.3).</p>
 */
public class PostToolUseNormalization {

  private static final int MAX_ITERATIONS = 20;

  private static final ObjectMapper JSON = new ObjectMapper()
      .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

  // STRICT + "uuuu" so impossible dates like 31/02/2026 are rejected rather
  // than silently adjusted to the nearest valid day.
  private static final DateTimeFormatter DD_MM_YYYY =
      DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);

  private static final Map<Integer, String> CUSTOMER_STATUSES = Map.of(
      1, "active",
      2, "suspended",
      3, "closed");

  private static final Map<String, String> SHIPPING_STATUSES = Map.of(
      "S", "shipped",
      "P", "pending",
      "D", "delivered");

  static final String SUPPORT_AGENT_SYSTEM_PROMPT = """
      You are a customer support agent for an online store. You help \
      customers with questions about their account, orders, and deliveries \
      using the tools available to you.""";

  /**
   * A hook the tool loop calls after every successful tool execution, before
   * the result is sent to the model - the equivalent of the Agent SDK's
   * PostToolUse hook. Error results bypass it (the Agent SDK has a separate
   * PostToolUseFailure event for those).
   */
  @FunctionalInterface
  interface PostToolUseHook {

    /**
     * @param toolName     the name of the tool that was executed
     * @param toolInput    the input the tool was called with
     * @param toolResponse the tool's raw output, as JSON
     * @return the output the model receives instead - the SDK's
     *         {@code updatedToolOutput}; return toolResponse unchanged to
     *         pass it through
     */
    String onPostToolUse(String toolName, Map<String, JsonValue> toolInput, String toolResponse);
  }

  /** The no-op hook: the model sees every tool's raw output. */
  static final PostToolUseHook PASS_THROUGH_HOOK = (toolName, toolInput, toolResponse) -> toolResponse;

  /** The normalizing hook this exercise is about - see {@link #normalizeToolOutput}. */
  static final PostToolUseHook NORMALIZING_HOOK = PostToolUseNormalization::normalizeToolOutput;

  /**
   * get_customer's raw output, from the CRM system.
   *
   * @param createdAt Unix epoch seconds, e.g. 1709294400
   * @param status    numeric account status: 1 = active, 2 = suspended,
   *                  3 = closed
   */
  public record CustomerRecord(String customerId, String name, long createdAt, int status) {
  }

  /**
   * lookup_order's raw output, from the order system.
   *
   * @param orderedAt ISO 8601 UTC timestamp, e.g. "2026-03-02T09:30:00Z"
   * @param status    English status string, e.g. "shipped", "processing"
   */
  public record OrderRecord(String orderId, String customerId, String orderedAt, String status) {
  }

  /**
   * check_shipping's raw output, from the shipping carrier.
   *
   * @param estimatedDelivery DD/MM/YYYY, e.g. "04/03/2026" = 4 March 2026
   * @param status            single-character code: S = shipped,
   *                          P = pending, D = delivered
   */
  public record ShipmentRecord(String orderId, String trackingNumber, String estimatedDelivery,
      String status) {
  }

  // Mock data standing in for three different backend systems. The dates
  // are chosen so that misreading DD/MM as MM/DD gives a plausible but wrong
  // answer, and ORD-5002 pairs order status "processing" with shipping
  // status "P" (pending) - easy to conflate without normalization.
  private static final Map<String, CustomerRecord> CUSTOMERS = Map.of(
      "CUST-1001", new CustomerRecord("CUST-1001", "Jane Doe", 1709294400L, 1));

  private static final Map<String, OrderRecord> ORDERS = Map.of(
      "ORD-5001", new OrderRecord("ORD-5001", "CUST-1001", "2026-03-02T09:30:00Z", "shipped"),
      "ORD-5002", new OrderRecord("ORD-5002", "CUST-1001", "2026-03-03T14:05:00Z", "processing"));

  private static final Map<String, ShipmentRecord> SHIPMENTS = Map.of(
      "ORD-5001", new ShipmentRecord("ORD-5001", "TRK-88231", "04/03/2026", "S"),
      "ORD-5002", new ShipmentRecord("ORD-5002", "TRK-88232", "12/03/2026", "P"));

  public static void main(String[] args) {

    AnthropicClient client = AnthropicClients.fromDotEnv();

    String finalResponse = runLoop(client, "Hi, I'm customer CUST-1001. When did I open my "
        + "account, and what's the status and expected delivery date of my orders ORD-5001 and "
        + "ORD-5002?", NORMALIZING_HOOK);
    System.out.println("\n" + finalResponse);
  }

  /**
   * Runs the support agent's loop for one conversation. Sends userMessage
   * with the system prompt and all three tools, executes every requested
   * tool, passes each successful result through hook before it goes into the
   * tool_result block, and repeats while stop_reason is tool_use. Returns
   * the final text once stop_reason is end_turn; any other stop_reason is
   * treated as unhandled.
   *
   * <p>The hook is a parameter so the same loop can run with
   * {@link #PASS_THROUGH_HOOK} (the model sees three formats) or
   * {@link #NORMALIZING_HOOK} (the model sees one) - which is how the
   * "verify the model receives consistent data" step is checked. Each
   * tool_result is logged as raw and as sent, so the difference is visible
   * in the console.</p>
   *
   * @param client      the Anthropic client to send requests through
   * @param userMessage the customer's message
   * @param hook        the PostToolUse hook applied to every successful tool result
   * @return the text content of the final response once stop_reason is end_turn
   * @throws IllegalStateException on an unhandled stop_reason, or after
   *                               {@value #MAX_ITERATIONS} tool-use iterations
   */
  static String runLoop(AnthropicClient client, String userMessage, PostToolUseHook hook) {
    List<MessageParam> messages = new ArrayList<>();
    messages.add(MessageParam.builder()
        .role(MessageParam.Role.USER)
        .content(userMessage)
        .build());

    for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
      MessageCreateParams params = MessageCreateParams.builder()
          .model(Config.modelMain())
          .maxTokens(Config.maxTokens())
          .system(SUPPORT_AGENT_SYSTEM_PROMPT)
          .addTool(getGetCustomerTool())
          .addTool(getLookupOrderTool())
          .addTool(getCheckShippingTool())
          .messages(messages)
          .build();

      Message response = client.messages().create(params);
      StopReason stopReason = response.stopReason().get();
      System.out.println("[turn " + iteration + "] stop_reason: " + stopReason);

      if (stopReason.equals(StopReason.END_TURN)) {
        return extractFinalResponse(response);
      }

      if (!stopReason.equals(StopReason.TOOL_USE)) {
        throw new IllegalStateException("Unhandled stop_reason: " + stopReason);
      }

      messages.add(response.toParam());
      messages.add(executeToolCalls(response, hook));
    }

    System.err.println("WARNING: safety cap of " + MAX_ITERATIONS + " tool-use iterations hit "
        + "without reaching a terminal stop_reason.");
    throw new IllegalStateException(
        "Exceeded " + MAX_ITERATIONS + " tool-use iterations without reaching end_turn");
  }

  /**
   * Executes every tool_use block in a response, in order, runs each
   * successful result through hook, and packages them into the single user
   * message of tool_result blocks the API expects as the next turn.
   *
   * @param response the assistant response whose stop_reason was tool_use
   * @param hook     the PostToolUse hook
   * @return a user MessageParam carrying one tool_result block per tool_use block
   */
  private static MessageParam executeToolCalls(Message response, PostToolUseHook hook) {
    List<ContentBlockParam> toolResults = new ArrayList<>();
    for (ToolUseBlock toolUse : response.content().stream()
        .flatMap(block -> block.toolUse().stream())
        .toList()) {
      ToolResultBlockParam rawResult = executeTool(toolUse);
      toolResults.add(ContentBlockParam.ofToolResult(applyHook(toolUse, rawResult, hook)));
    }

    return MessageParam.builder()
        .role(MessageParam.Role.USER)
        .contentOfBlockParams(toolResults)
        .build();
  }

  // The PostToolUse position: the tool has already executed, the model
  // hasn't seen its result yet. Error results bypass the hook. If the hook
  // can't normalize a result, the model gets an error instead of the raw,
  // unnormalized data - an unmappable value is surfaced, never guessed at.
  private static ToolResultBlockParam applyHook(ToolUseBlock toolUse,
      ToolResultBlockParam rawResult, PostToolUseHook hook) {
    String rawOutput = rawResult.content().orElseThrow().asString();
    System.out.println("[" + toolUse.name() + "] raw:  " + rawOutput);

    if (rawResult.isError().orElse(false)) {
      return rawResult;
    }

    String updatedOutput;
    try {
      updatedOutput = hook.onPostToolUse(toolUse.name(), toolInput(toolUse), rawOutput);
    } catch (IllegalArgumentException e) {
      System.out.println("[" + toolUse.name() + "] hook failed: " + e.getMessage());
      return errorResult(toolUse.id(), "The " + toolUse.name() + " result could not be "
          + "normalized: " + e.getMessage());
    }
    System.out.println("[" + toolUse.name() + "] sent: " + updatedOutput);

    return successResult(toolUse.id(), updatedOutput);
  }

  /**
   * Routes a tool_use block to its stub implementation and returns the
   * tool's raw output - before any hook has seen it. A lookup that can't be
   * completed returns an is_error tool_result rather than throwing.
   *
   * @param toolUse the tool_use block requesting execution
   * @return the tool_result block carrying the tool's raw JSON output
   * @throws IllegalArgumentException if the tool name is not one of the three tools
   */
  static ToolResultBlockParam executeTool(ToolUseBlock toolUse) {
    Map<String, JsonValue> input = toolInput(toolUse);

    return switch (toolUse.name()) {
      case "get_customer" ->
          handleLookup(toolUse.id(), () -> getCustomer(stringArg(input, "customer_id")));
      case "lookup_order" ->
          handleLookup(toolUse.id(), () -> lookupOrder(stringArg(input, "order_id")));
      case "check_shipping" ->
          handleLookup(toolUse.id(), () -> checkShipping(stringArg(input, "order_id")));
      default -> throw new IllegalArgumentException("Unknown tool: " + toolUse.name());
    };
  }

  // A failed lookup (unknown ID, missing input) becomes an is_error
  // tool_result for the model to react to, rather than ending the conversation.
  private static ToolResultBlockParam handleLookup(String toolUseId, Supplier<Object> lookup) {
    try {
      return successResult(toolUseId, toJson(lookup.get()));
    } catch (IllegalArgumentException e) {
      return errorResult(toolUseId, e.getMessage());
    }
  }

  /**
   * The PostToolUse hook's logic: rewrites a tool's raw JSON output so that
   * every tool speaks the same format, leaving all other fields untouched.
   * <ul>
   *   <li>get_customer: created_at epoch seconds -> ISO 8601 UTC timestamp;
   *       status 1/2/3 -> "active"/"suspended"/"closed"</li>
   *   <li>lookup_order: already ISO 8601 and English - passes through
   *       unchanged</li>
   *   <li>check_shipping: estimated_delivery DD/MM/YYYY -> ISO 8601
   *       (midnight UTC, e.g. "2026-03-04T00:00:00Z"); status S/P/D ->
   *       "shipped"/"pending"/"delivered"</li>
   * </ul>
   * Output of any other tool passes through unchanged.
   *
   * @param toolName     the name of the tool that was executed
   * @param toolInput    the input the tool was called with
   * @param toolResponse the tool's raw output, as JSON
   * @return the normalized output, as JSON
   * @throws IllegalArgumentException on a status code or date that can't be
   *                                  mapped - an unknown value is surfaced,
   *                                  never guessed at
   */
  static String normalizeToolOutput(String toolName, Map<String, JsonValue> toolInput, String toolResponse) {
    return switch (toolName) {
      case "get_customer" -> {
        ObjectNode customer = parseObject(toolResponse);
        customer.put("created_at", epochSecondsToIso(customer.get("created_at").asLong()));
        customer.put("status", customerStatusToEnglish(customer.get("status").asInt()));
        yield toJson(customer);
      }
      case "check_shipping" -> {
        ObjectNode shipment = parseObject(toolResponse);
        shipment.put("estimated_delivery",
            ddMmYyyyToIso(shipment.get("estimated_delivery").asText()));
        shipment.put("status", shippingStatusToEnglish(shipment.get("status").asText()));
        yield toJson(shipment);
      }
      default -> toolResponse;
    };
  }

  private static String epochSecondsToIso(long epochSeconds) {
    return Instant.ofEpochSecond(epochSeconds).toString();
  }

  private static String ddMmYyyyToIso(String date) {
    try {
      return LocalDate.parse(date, DD_MM_YYYY).atStartOfDay(ZoneOffset.UTC).toInstant().toString();
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("Not a valid DD/MM/YYYY date: " + date, e);
    }
  }

  private static String customerStatusToEnglish(int code) {
    String status = CUSTOMER_STATUSES.get(code);
    if (status == null) {
      throw new IllegalArgumentException("Unknown customer status code: " + code);
    }
    return status;
  }

  private static String shippingStatusToEnglish(String code) {
    String status = SHIPPING_STATUSES.get(code);
    if (status == null) {
      throw new IllegalArgumentException("Unknown shipping status code: " + code);
    }
    return status;
  }

  private static ObjectNode parseObject(String json) {
    try {
      return (ObjectNode) JSON.readTree(json);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Map<String, JsonValue> toolInput(ToolUseBlock toolUse) {
    return toolUse._input().convert(new TypeReference<Map<String, JsonValue>>() {});
  }

  private static ToolResultBlockParam successResult(String toolUseId, String content) {
    return ToolResultBlockParam.builder()
        .toolUseId(toolUseId)
        .content(content)
        .build();
  }

  private static ToolResultBlockParam errorResult(String toolUseId, String message) {
    return ToolResultBlockParam.builder()
        .toolUseId(toolUseId)
        .content(message)
        .isError(true)
        .build();
  }

  private static String stringArg(Map<String, JsonValue> input, String key) {
    JsonValue value = input.get(key);
    if (value == null) {
      throw new IllegalArgumentException("Missing required input: " + key);
    }
    return value.convert(String.class);
  }

  private static String toJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String extractFinalResponse(Message response) {
    return response.content().stream()
        .flatMap(block -> block.text().stream())
        .map(TextBlock::text)
        .collect(Collectors.joining("\n"));
  }

  /**
   * The get_customer tool - looks a customer up by ID. Its description
   * promises the normalized format, since that's what the model actually
   * receives.
   *
   * @return the get_customer tool definition
   */
  static Tool getGetCustomerTool() {
    return Tool.builder()
        .name("get_customer")
        .description("""
            Call this whenever the customer asks about their account - e.g. \
            when it was opened or whether it is active - and you have their \
            customer ID. Returns customer_id, name, created_at (ISO 8601 UTC \
            timestamp) and status ("active", "suspended" or "closed").""")
        .inputSchema(Tool.InputSchema.builder()
            .properties(Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("customer_id", JsonValue.from(Map.of(
                    "type", "string",
                    "description", "The customer ID, e.g. 'CUST-1001'.")))
                .build())
            .required(List.of("customer_id"))
            .build())
        .build();
  }

  /**
   * The lookup_order tool - returns an order's details by its ID.
   *
   * @return the lookup_order tool definition
   */
  static Tool getLookupOrderTool() {
    return Tool.builder()
        .name("lookup_order")
        .description("""
            Call this whenever the customer refers to a specific order and \
            wants to know when it was placed or where it is in fulfilment. \
            Returns order_id, the customer_id it belongs to, ordered_at (ISO \
            8601 UTC timestamp) and status (e.g. "processing", "shipped"). \
            For delivery dates and shipment status, use check_shipping.""")
        .inputSchema(Tool.InputSchema.builder()
            .properties(Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("order_id", JsonValue.from(Map.of(
                    "type", "string",
                    "description", "The order ID to look up, e.g. 'ORD-5001'.")))
                .build())
            .required(List.of("order_id"))
            .build())
        .build();
  }

  /**
   * The check_shipping tool - returns an order's shipment status and
   * estimated delivery date.
   *
   * @return the check_shipping tool definition
   */
  static Tool getCheckShippingTool() {
    return Tool.builder()
        .name("check_shipping")
        .description("""
            Call this whenever the customer asks when an order will arrive or \
            whether it has shipped. Returns order_id, tracking_number, \
            estimated_delivery (ISO 8601 UTC timestamp) and status \
            ("pending", "shipped" or "delivered").""")
        .inputSchema(Tool.InputSchema.builder()
            .properties(Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("order_id", JsonValue.from(Map.of(
                    "type", "string",
                    "description", "The order ID whose shipment to check, e.g. 'ORD-5001'.")))
                .build())
            .required(List.of("order_id"))
            .build())
        .build();
  }

  /**
   * get_customer's stub implementation, against {@link #CUSTOMERS}.
   *
   * @throws IllegalArgumentException if no customer has that ID
   */
  static CustomerRecord getCustomer(String customerId) {
    CustomerRecord customer = CUSTOMERS.get(customerId);
    if (customer == null) {
      throw new IllegalArgumentException("No customer found with ID " + customerId);
    }
    return customer;
  }

  /**
   * lookup_order's stub implementation, against {@link #ORDERS}.
   *
   * @throws IllegalArgumentException if no order has that ID
   */
  static OrderRecord lookupOrder(String orderId) {
    OrderRecord order = ORDERS.get(orderId);
    if (order == null) {
      throw new IllegalArgumentException("No order found with ID " + orderId);
    }
    return order;
  }

  /**
   * check_shipping's stub implementation, against {@link #SHIPMENTS}.
   *
   * @throws IllegalArgumentException if no shipment exists for that order ID
   */
  static ShipmentRecord checkShipping(String orderId) {
    ShipmentRecord shipment = SHIPMENTS.get(orderId);
    if (shipment == null) {
      throw new IllegalArgumentException("No shipment found for order ID " + orderId);
    }
    return shipment;
  }
}
