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
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.ccarf.common.AnthropicClients;
import dev.ccarf.common.Config;

/**
 * Exercise 1.5.2
 * Implement a PostToolUse hook that intercepts all tool results and
 * normalizes dates to ISO 8601 format and status codes to human-readable
 * English strings.
 *
 * <p>Hook direction: PostToolUse runs after the tool has executed but before
 * the model processes its result - the right place to transform data. It
 * can change what the model sees, but it can't block or undo the tool call,
 * because by the time it fires the tool has already run. (Blocking a call
 * is PreToolUse's job, before execution.)</p>
 *
 * <p>Note on the Agent SDK: hooks are a feature of the Claude Agent SDK
 * (Python/TypeScript), which the Java SDK doesn't have. This class models
 * the SDK's hook API in our own Messages API tool loop, keeping its names
 * so they map one-to-one:</p>
 * <ul>
 *   <li>{@code hooks: { PostToolUse: [{ matcher, hooks: [callback] }] }} -
 *       {@link #HOOKS}, a map from hook event name to {@link HookMatcher}s,
 *       passed to {@link #runLoop} the way the SDK takes it in its
 *       options</li>
 *   <li>{@code HookCallback(input, toolUseID)} - {@link HookCallback}</li>
 *   <li>{@code PostToolUseHookInput} with {@code tool_name},
 *       {@code tool_input}, {@code tool_response} -
 *       {@link PostToolUseHookInput}</li>
 *   <li>{@code { hookSpecificOutput: { hookEventName: "PostToolUse",
 *       updatedToolOutput } }} - {@link HookJsonOutput} /
 *       {@link PostToolUseSpecificOutput}</li>
 * </ul>
 * The loop calls every matching PostToolUse callback after a tool has run
 * and before its tool_result is built; if a callback returns an
 * updatedToolOutput, that object replaces what the model receives.
 *
 * <p>The tools and mock data are the ones from Exercise 1.5.1
 * ({@link DataFormatChaosTools}), duplicated here so this exercise stands
 * alone. Their descriptions now promise the normalized format, since that's
 * what the model actually receives once the hook is registered.</p>
 */
public class PostToolUseNormalizationHook {

  private static final int MAX_ITERATIONS = 20;

  static final String POST_TOOL_USE = "PostToolUse";

  private static final ObjectMapper JSON = new ObjectMapper()
      .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

  // STRICT + "uuuu" so impossible dates like 31/02/2026 are rejected rather
  // than silently adjusted to the nearest valid day.
  private static final DateTimeFormatter DD_MM_YYYY =
      DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);

  // Status codes mean different things in different systems, so each
  // mapping belongs to the tool (source system) that emits it.
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
   * What a PostToolUse callback receives - the Agent SDK's
   * PostToolUseHookInput, reduced to the fields this exercise needs.
   *
   * @param hookEventName always {@value #POST_TOOL_USE}
   * @param toolName      the name of the tool that was executed
   * @param toolInput     the input the tool was called with
   * @param toolResponse  the tool's output, as the tool returned it
   */
  record PostToolUseHookInput(String hookEventName, String toolName,
      Map<String, JsonValue> toolInput, JsonNode toolResponse) {
  }

  /**
   * The PostToolUse-specific part of a hook's output.
   *
   * @param hookEventName     always {@value #POST_TOOL_USE}
   * @param updatedToolOutput the object the model receives instead of the
   *                          tool's own output
   */
  record PostToolUseSpecificOutput(String hookEventName, JsonNode updatedToolOutput) {
  }

  /**
   * What a hook callback returns - the Agent SDK's HookJSONOutput. An empty
   * hookSpecificOutput means "no change": the tool's output passes through.
   */
  record HookJsonOutput(Optional<PostToolUseSpecificOutput> hookSpecificOutput) {

    static HookJsonOutput noChange() {
      return new HookJsonOutput(Optional.empty());
    }

    static HookJsonOutput updatedToolOutput(JsonNode updatedToolOutput) {
      return new HookJsonOutput(
          Optional.of(new PostToolUseSpecificOutput(POST_TOOL_USE, updatedToolOutput)));
    }
  }

  /** A hook callback - the Agent SDK's HookCallback. */
  @FunctionalInterface
  interface HookCallback {

    /**
     * @param input     the hook input
     * @param toolUseId the id of the tool_use block that triggered the hook
     * @return the hook's output
     */
    HookJsonOutput call(PostToolUseHookInput input, String toolUseId);
  }

  /**
   * Registers callbacks for the tools whose name matches a regex - the
   * Agent SDK's HookCallbackMatcher.
   *
   * @param matcher a regex matched against the full tool name; ".*"
   *                matches every tool
   * @param hooks   the callbacks to run, in order
   */
  record HookMatcher(String matcher, List<HookCallback> hooks) {

    boolean matches(String toolName) {
      return Pattern.matches(matcher, toolName);
    }
  }

  /**
   * The hook registration this exercise is about: one PostToolUse matcher
   * covering every tool, running {@link #normalizeToolResponse}.
   */
  static final Map<String, List<HookMatcher>> HOOKS = Map.of(
      POST_TOOL_USE, List.of(new HookMatcher(".*",
          List.of(PostToolUseNormalizationHook::normalizeToolResponse))));

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

  // Mock data standing in for three different backend systems - see
  // DataFormatChaosTools, Exercise 1.5.1.
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
        + "ORD-5002?", HOOKS);
    System.out.println("\n" + finalResponse);
  }

  /**
   * Runs the support agent's loop for one conversation. Sends userMessage
   * with the system prompt and all three tools, executes every requested
   * tool, runs the registered PostToolUse hooks on each result before it
   * goes into the tool_result block, and repeats while stop_reason is
   * tool_use. Returns the final text once stop_reason is end_turn; any other
   * stop_reason is treated as unhandled.
   *
   * @param client      the Anthropic client to send requests through
   * @param userMessage the customer's message
   * @param hooks       the hook registration, keyed by hook event name
   * @return the text content of the final response once stop_reason is end_turn
   * @throws IllegalStateException on an unhandled stop_reason, or after
   *                               {@value #MAX_ITERATIONS} tool-use iterations
   */
  static String runLoop(AnthropicClient client, String userMessage,
      Map<String, List<HookMatcher>> hooks) {
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
      messages.add(executeToolCalls(response, hooks));
    }

    System.err.println("WARNING: safety cap of " + MAX_ITERATIONS + " tool-use iterations hit "
        + "without reaching a terminal stop_reason.");
    throw new IllegalStateException(
        "Exceeded " + MAX_ITERATIONS + " tool-use iterations without reaching end_turn");
  }

  /**
   * Executes every tool_use block in a response, in order, runs the
   * PostToolUse hooks on each result, and packages them into the single
   * user message of tool_result blocks the API expects as the next turn.
   *
   * @param response the assistant response whose stop_reason was tool_use
   * @param hooks    the hook registration
   * @return a user MessageParam carrying one tool_result block per tool_use block
   */
  private static MessageParam executeToolCalls(Message response,
      Map<String, List<HookMatcher>> hooks) {
    List<ContentBlockParam> toolResults = new ArrayList<>();
    for (ToolUseBlock toolUse : response.content().stream()
        .flatMap(block -> block.toolUse().stream())
        .toList()) {
      ToolResultBlockParam rawResult = executeTool(toolUse);
      toolResults.add(ContentBlockParam.ofToolResult(runPostToolUseHooks(hooks, toolUse, rawResult)));
    }

    return MessageParam.builder()
        .role(MessageParam.Role.USER)
        .contentOfBlockParams(toolResults)
        .build();
  }

  // The PostToolUse position: the tool has already executed, the model
  // hasn't seen its result yet. Every callback of every matcher whose regex
  // matches the tool name runs in turn, each seeing the output as the
  // previous one left it. Error results bypass the hooks (the Agent SDK has
  // a separate PostToolUseFailure event for those). If a callback can't
  // normalize a value, the model gets an error instead of the raw data - an
  // unmappable value is surfaced, never guessed at.
  private static ToolResultBlockParam runPostToolUseHooks(Map<String, List<HookMatcher>> hooks,
      ToolUseBlock toolUse, ToolResultBlockParam rawResult) {
    String rawOutput = rawResult.content().orElseThrow().asString();
    System.out.println("[" + toolUse.name() + "] tool_response:     " + rawOutput);

    if (rawResult.isError().orElse(false)) {
      return rawResult;
    }

    JsonNode output = parse(rawOutput);
    try {
      for (HookMatcher hookMatcher : hooks.getOrDefault(POST_TOOL_USE, List.of())) {
        if (!hookMatcher.matches(toolUse.name())) {
          continue;
        }
        for (HookCallback callback : hookMatcher.hooks()) {
          PostToolUseHookInput input =
              new PostToolUseHookInput(POST_TOOL_USE, toolUse.name(), toolInput(toolUse), output);
          Optional<PostToolUseSpecificOutput> specificOutput =
              callback.call(input, toolUse.id()).hookSpecificOutput();
          if (specificOutput.isPresent()) {
            output = specificOutput.get().updatedToolOutput();
          }
        }
      }
    } catch (IllegalArgumentException e) {
      System.out.println("[" + toolUse.name() + "] PostToolUse hook failed: " + e.getMessage());
      return errorResult(toolUse.id(), "The " + toolUse.name() + " result could not be "
          + "normalized: " + e.getMessage());
    }

    String updatedOutput = toJson(output);
    System.out.println("[" + toolUse.name() + "] sent to the model: " + updatedOutput);
    return successResult(toolUse.id(), updatedOutput);
  }

  /**
   * The PostToolUse hook callback: reads tool_response and returns a
   * rewritten copy as updatedToolOutput, so that every tool speaks the same
   * format. Only date and status fields change; everything else is kept.
   * <ul>
   *   <li>get_customer: created_at Unix epoch seconds -> ISO 8601 UTC
   *       timestamp; status 1/2/3 -> "active"/"suspended"/"closed"</li>
   *   <li>check_shipping: estimated_delivery DD/MM/YYYY -> ISO 8601
   *       (midnight UTC, e.g. "2026-03-04T00:00:00Z"); status S/P/D ->
   *       "shipped"/"pending"/"delivered"</li>
   *   <li>lookup_order, and any other tool: already ISO 8601 and English,
   *       or nothing to normalize - no change</li>
   * </ul>
   *
   * @param input     the hook input carrying tool_name and tool_response
   * @param toolUseId the id of the tool_use block that triggered the hook
   * @return updatedToolOutput with the normalized object, or no change
   * @throws IllegalArgumentException on a status code or date that can't be
   *                                  mapped
   */
  static HookJsonOutput normalizeToolResponse(PostToolUseHookInput input, String toolUseId) {
    return switch (input.toolName()) {
      case "get_customer" -> {
        ObjectNode customer = input.toolResponse().deepCopy();
        customer.put("created_at", epochSecondsToIso(customer.get("created_at").asLong()));
        customer.put("status", customerStatusToEnglish(customer.get("status").asInt()));
        yield HookJsonOutput.updatedToolOutput(customer);
      }
      case "check_shipping" -> {
        ObjectNode shipment = input.toolResponse().deepCopy();
        shipment.put("estimated_delivery",
            ddMmYyyyToIso(shipment.get("estimated_delivery").asText()));
        shipment.put("status", shippingStatusToEnglish(shipment.get("status").asText()));
        yield HookJsonOutput.updatedToolOutput(shipment);
      }
      default -> HookJsonOutput.noChange();
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

  private static JsonNode parse(String json) {
    try {
      return JSON.readTree(json);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
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
   * promises the normalized format, since that's what the model receives
   * once the PostToolUse hook is registered.
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
   * estimated delivery date. Its description promises the normalized
   * format, as for get_customer.
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

  // get_customer's stub implementation, against CUSTOMERS.
  private static CustomerRecord getCustomer(String customerId) {
    CustomerRecord customer = CUSTOMERS.get(customerId);
    if (customer == null) {
      throw new IllegalArgumentException("No customer found with ID " + customerId);
    }
    return customer;
  }

  // lookup_order's stub implementation, against ORDERS.
  private static OrderRecord lookupOrder(String orderId) {
    OrderRecord order = ORDERS.get(orderId);
    if (order == null) {
      throw new IllegalArgumentException("No order found with ID " + orderId);
    }
    return order;
  }

  // check_shipping's stub implementation, against SHIPMENTS.
  private static ShipmentRecord checkShipping(String orderId) {
    ShipmentRecord shipment = SHIPMENTS.get(orderId);
    if (shipment == null) {
      throw new IllegalArgumentException("No shipment found for order ID " + orderId);
    }
    return shipment;
  }
}
