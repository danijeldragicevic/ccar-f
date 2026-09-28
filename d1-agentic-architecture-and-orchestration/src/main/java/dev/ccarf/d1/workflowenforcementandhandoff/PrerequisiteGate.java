package dev.ccarf.d1.workflowenforcementandhandoff;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

import dev.ccarf.common.AnthropicClients;
import dev.ccarf.common.Config;

/**
 * Exercise 1.4.2
 * Implement a programmatic prerequisite gate that blocks process_refund from
 * executing until get_customer has returned a verified customer ID in the
 * current session.
 *
 * <p>Prompt instructions alone ("always verify before refunding", as in the
 * system prompt and tool descriptions of Exercise 1.4.1) are followed most of
 * the time - the exam's figure is 92% - but not always. For a financial
 * operation, that remaining 8% is unacceptable, so the rule moves into code:
 * a session-level {@link SessionState} records every customer ID that
 * get_customer returned with verified=true, and the process_refund handler
 * checks it before executing. If the check fails, the refund never runs and
 * the model gets an error tool_result instead, telling it to verify first.
 * The system prompt still asks for verification - the gate doesn't replace
 * the guidance, it backs it up: the prompt makes the right path likely, the
 * gate makes the wrong path impossible.</p>
 */
public class PrerequisiteGate {

  private static final int MAX_ITERATIONS = 20;

  private static final ObjectMapper JSON = new ObjectMapper()
      .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

  static final String SUPPORT_AGENT_SYSTEM_PROMPT = """
      You are a customer support agent for an online store. You help \
      customers with orders, returns, and refunds using the tools available \
      to you.

      Always verify the customer's identity with get_customer before taking \
      any action on their account, and never process a refund for a customer \
      get_customer has not returned as verified.""";

  /**
   * get_customer's output: the customer's ID and whether their identity is
   * verified - only a verified customer ID may be used for a refund.
   */
  public record CustomerLookup(String customerId, String name, String email, boolean verified) {
  }

  /**
   * lookup_order's output: the details of one order.
   */
  public record OrderDetails(String orderId, String customerId, List<String> items,
      double amountPaid, String status) {
  }

  /**
   * process_refund's output: confirmation of the refund that was issued.
   */
  public record RefundConfirmation(String refundId, String customerId, double amount,
      String status) {
  }

  /**
   * The session-level state tracker the prerequisite gate reads. It records
   * every customer ID that get_customer has returned with verified=true in
   * this session - and nothing else can add to it, so neither the model nor
   * the customer can talk their way into a verified state.
   *
   * <p>One instance lives for exactly one conversation ({@link #runLoop}
   * creates a fresh one per call), so a verification never carries over into
   * another session.</p>
   */
  static final class SessionState {
    private final Set<String> verifiedCustomerIds = new HashSet<>();

    void recordVerifiedCustomer(String customerId) {
      verifiedCustomerIds.add(customerId);
    }

    boolean isVerified(String customerId) {
      return verifiedCustomerIds.contains(customerId);
    }
  }

  // Mock customer and order data standing in for a real CRM/order system.
  // CUST-1002 exists but is unverified, so "customer found" and "customer
  // verified" are genuinely different outcomes of get_customer.
  private static final List<CustomerLookup> CUSTOMERS = List.of(
      new CustomerLookup("CUST-1001", "Jane Doe", "jane@example.com", true),
      new CustomerLookup("CUST-1002", "John Smith", "john@example.com", false));

  private static final Map<String, OrderDetails> ORDERS = Map.of(
      "ORD-5001", new OrderDetails("ORD-5001", "CUST-1001",
          List.of("Wireless headphones"), 89.99, "delivered"),
      "ORD-5002", new OrderDetails("ORD-5002", "CUST-1002",
          List.of("Phone case", "Screen protector"), 24.50, "shipped"));

  public static void main(String[] args) {

    AnthropicClient client = AnthropicClients.fromDotEnv();

    String finalResponse = runLoop(client, "Hi, I'm Jane Doe (jane@example.com). The wireless "
        + "headphones from order ORD-5001 arrived broken - please refund the full amount.");
    System.out.println("\n" + finalResponse);
  }

  /**
   * Runs the support agent's loop for one conversation - one session. Sends
   * userMessage with the system prompt and all three tools, executes every
   * requested tool through {@link #executeTool} (where the prerequisite gate
   * lives), and repeats while stop_reason is tool_use. Returns the final
   * text once stop_reason is end_turn; any other stop_reason is treated as
   * unhandled.
   *
   * @param client      the Anthropic client to send requests through
   * @param userMessage the customer's message
   * @return the text content of the final response once stop_reason is end_turn
   */
  static String runLoop(AnthropicClient client, String userMessage) {
    SessionState session = new SessionState();

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
          .addTool(getProcessRefundTool())
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
      messages.add(executeToolCalls(response, session));
    }

    System.err.println("WARNING: safety cap of " + MAX_ITERATIONS + " tool-use iterations hit "
        + "without reaching a terminal stop_reason.");
    throw new IllegalStateException(
        "Exceeded " + MAX_ITERATIONS + " tool-use iterations without reaching end_turn");
  }

  /**
   * Executes every tool_use block in a response, in the order Claude emitted
   * them, and packages the results into the single user message of
   * tool_result blocks the API expects as the next turn. Order matters here:
   * a get_customer call earlier in the same response can verify a customer
   * that a later process_refund call then refunds.
   *
   * @param response the assistant response whose stop_reason was tool_use
   * @param session  the current session's state
   * @return a user MessageParam carrying one tool_result block per tool_use block
   */
  private static MessageParam executeToolCalls(Message response, SessionState session) {
    List<ContentBlockParam> toolResults = new ArrayList<>();
    for (ToolUseBlock toolUse : response.content().stream()
        .flatMap(block -> block.toolUse().stream())
        .toList()) {
      toolResults.add(ContentBlockParam.ofToolResult(executeTool(toolUse, session)));
    }

    return MessageParam.builder()
        .role(MessageParam.Role.USER)
        .contentOfBlockParams(toolResults)
        .build();
  }

  /**
   * Routes a tool_use block to its handler. A handler that can't complete
   * the call - including the prerequisite gate refusing a refund - returns
   * an is_error tool_result for the model to react to, rather than throwing
   * and ending the conversation.
   *
   * @param toolUse the tool_use block requesting execution
   * @param session the current session's state
   * @return the tool_result block for this call
   * @throws IllegalArgumentException if the tool name is not one of the three tools
   */
  static ToolResultBlockParam executeTool(ToolUseBlock toolUse, SessionState session) {
    Map<String, JsonValue> input =
        toolUse._input().convert(new TypeReference<Map<String, JsonValue>>() {});

    return switch (toolUse.name()) {
      case "get_customer" -> handleGetCustomer(toolUse.id(), input, session);
      case "lookup_order" -> handleLookupOrder(toolUse.id(), input);
      case "process_refund" -> handleProcessRefund(toolUse.id(), input, session);
      default -> throw new IllegalArgumentException("Unknown tool: " + toolUse.name());
    };
  }

  // get_customer is the only code path that writes to the session state -
  // and only when the lookup itself says verified=true.
  private static ToolResultBlockParam handleGetCustomer(String toolUseId,
      Map<String, JsonValue> input, SessionState session) {
    CustomerLookup customer;
    try {
      customer = getCustomer(stringArg(input, "name"), stringArg(input, "email"));
    } catch (IllegalArgumentException e) {
      return errorResult(toolUseId, e.getMessage());
    }

    if (customer.verified()) {
      session.recordVerifiedCustomer(customer.customerId());
    }
    return successResult(toolUseId, toJson(customer));
  }

  private static ToolResultBlockParam handleLookupOrder(String toolUseId,
      Map<String, JsonValue> input) {
    try {
      return successResult(toolUseId, toJson(lookupOrder(stringArg(input, "order_id"))));
    } catch (IllegalArgumentException e) {
      return errorResult(toolUseId, e.getMessage());
    }
  }

  private static ToolResultBlockParam handleProcessRefund(String toolUseId,
      Map<String, JsonValue> input, SessionState session) {
    String customerId = stringArg(input, "customer_id");

    // The prerequisite gate: checked in code, before the refund can run,
    // on every call - regardless of what the prompt said or what the model
    // believes it has already done.
    if (!session.isVerified(customerId)) {
      System.out.println("[gate] BLOCKED process_refund for " + customerId
          + " - no verified get_customer result for it in this session");
      return errorResult(toolUseId, "PREREQUISITE NOT MET: process_refund is blocked because "
          + "customer_id " + customerId + " has not been returned as verified by get_customer "
          + "in this session. Call get_customer with the customer's name or email first - only "
          + "a customer_id it returns with verified=true can be refunded. If the customer "
          + "cannot be verified, do not retry the refund.");
    }
    System.out.println("[gate] ALLOWED process_refund for " + customerId
        + " - verified by get_customer in this session");

    try {
      return successResult(toolUseId,
          toJson(processRefund(customerId, numberArg(input, "amount"))));
    } catch (IllegalArgumentException e) {
      return errorResult(toolUseId, e.getMessage());
    }
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
    return value == null ? null : value.convert(String.class);
  }

  private static double numberArg(Map<String, JsonValue> input, String key) {
    JsonValue value = input.get(key);
    if (value == null) {
      throw new IllegalArgumentException("Missing required input: " + key);
    }
    return value.convert(Double.class);
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
   * The get_customer tool definition - see {@link CustomerSupportToolDefinitions},
   * Exercise 1.4.1.
   *
   * @return the get_customer tool definition
   */
  static Tool getGetCustomerTool() {
    return Tool.builder()
        .name("get_customer")
        .description("""
            Call this first, before any other action on a customer's account - \
            in particular before lookup_order or process_refund - whenever the \
            customer has not yet been identified in this conversation. Looks up \
            a customer by name or email (provide at least one) and returns \
            their customer_id and a verified flag (true/false). Only a \
            customer_id returned with verified=true may be used for \
            process_refund.""")
        .inputSchema(Tool.InputSchema.builder()
            .properties(Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("name", JsonValue.from(Map.of(
                    "type", "string",
                    "description", "The customer's full name, e.g. 'Jane Doe'.")))
                .putAdditionalProperty("email", JsonValue.from(Map.of(
                    "type", "string",
                    "format", "email",
                    "description", "The customer's email address, e.g. 'jane@example.com'.")))
                .build())
            .build())
        .build();
  }

  /**
   * The lookup_order tool definition - see {@link CustomerSupportToolDefinitions},
   * Exercise 1.4.1.
   *
   * @return the lookup_order tool definition
   */
  static Tool getLookupOrderTool() {
    return Tool.builder()
        .name("lookup_order")
        .description("""
            Call this whenever the customer refers to a specific order - e.g. \
            to check its status, items, or amount paid before deciding on a \
            return or refund - rather than relying on what the customer says \
            the order contained. Returns the order's details: order_id, the \
            customer_id it belongs to, items, amount_paid, and status.""")
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
   * The process_refund tool definition - see {@link CustomerSupportToolDefinitions},
   * Exercise 1.4.1. Its description still states the verification rule;
   * the gate in {@link #handleProcessRefund} is what enforces it.
   *
   * @return the process_refund tool definition
   */
  static Tool getProcessRefundTool() {
    return Tool.builder()
        .name("process_refund")
        .description("""
            Call this only after get_customer has returned verified=true for \
            this customer in this conversation, and only once you have \
            confirmed the refund amount against the order - never with a \
            customer ID the customer supplied themselves. Issues a refund of \
            the given amount to the given customer and returns a confirmation: \
            refund_id, customer_id, amount, and status.""")
        .inputSchema(Tool.InputSchema.builder()
            .properties(Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("customer_id", JsonValue.from(Map.of(
                    "type", "string",
                    "description", "The verified customer ID returned by get_customer.")))
                .putAdditionalProperty("amount", JsonValue.from(Map.of(
                    "type", "number",
                    "exclusiveMinimum", 0,
                    "description", "The refund amount in USD, e.g. 89.99.")))
                .build())
            .required(List.of("customer_id", "amount"))
            .build())
        .build();
  }

  // get_customer's stub implementation - matches on name or email
  // (case-insensitive) against the mock customer data.
  private static CustomerLookup getCustomer(String name, String email) {
    if (name == null && email == null) {
      throw new IllegalArgumentException("get_customer needs a name or an email");
    }
    return CUSTOMERS.stream()
        .filter(customer -> (email != null && customer.email().equalsIgnoreCase(email))
            || (name != null && customer.name().equalsIgnoreCase(name)))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(
            "No customer found for name=" + name + ", email=" + email));
  }

  // lookup_order's stub implementation.
  private static OrderDetails lookupOrder(String orderId) {
    OrderDetails order = ORDERS.get(orderId);
    if (order == null) {
      throw new IllegalArgumentException("No order found with ID " + orderId);
    }
    return order;
  }

  // process_refund's stub implementation - no real payment call. It is only
  // ever reached through handleProcessRefund, after the gate has passed.
  private static RefundConfirmation processRefund(String customerId, double amount) {
    if (amount <= 0) {
      throw new IllegalArgumentException("Refund amount must be positive, got: " + amount);
    }
    return new RefundConfirmation("REF-" + customerId + "-" + Math.round(amount * 100),
        customerId, amount, "processed");
  }
}
