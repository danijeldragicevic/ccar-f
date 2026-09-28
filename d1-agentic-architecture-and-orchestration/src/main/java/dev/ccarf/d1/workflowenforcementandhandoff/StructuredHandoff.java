package dev.ccarf.d1.workflowenforcementandhandoff;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import dev.ccarf.common.AnthropicClients;
import dev.ccarf.common.Config;

/**
 * Exercise 1.4.4
 * Implement a structured handoff protocol: when the agent cannot resolve an
 * issue, it compiles a self-contained summary with customer ID, conversation
 * summary, root cause analysis, refund amount, and recommended action.
 *
 * <p>The human agent who picks up an escalation does NOT see the
 * conversation transcript - the handoff is the only thing they receive. So
 * the handoff is not free text but a {@link HandoffSummary} with the five
 * required fields, and the agent produces it by calling a fourth tool,
 * escalate_to_human, whose input schema requires all five.</p>
 *
 * <p>A schema can require a field, but it can't stop the model from filling
 * it with "N/A" or "TBD". So {@link #compileHandoff} checks every field in
 * code - the same idea as the prerequisite gate of Exercise 1.4.2 - and
 * rejects an empty or placeholder field with an is_error tool_result naming
 * each problem, so the model fixes the handoff and calls the tool again.
 * Only a complete handoff is ever queued for a human.</p>
 *
 * <p>To give the agent something it genuinely can't resolve, process_refund
 * now has an automated limit of {@link #AUTOMATED_REFUND_LIMIT}: a larger
 * refund needs a human's approval. The prerequisite gate is unchanged.</p>
 */
public class StructuredHandoff {

  private static final int MAX_ITERATIONS = 20;

  // Refunds above this amount can't be processed by the agent - they need a
  // human agent's approval, i.e. a handoff.
  static final double AUTOMATED_REFUND_LIMIT = 100.00;

  private static final ObjectMapper JSON = new ObjectMapper()
      .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

  // Values that fill a field without informing anyone: a whole value like
  // "N/A" or "unknown", a to-be-decided marker anywhere, or a template
  // marker such as "<customer id>" or "[insert amount]".
  private static final Pattern PLACEHOLDER = Pattern.compile(
      "(?i)^(n/?a|none|null|unknown|pending|placeholder|see above|-+|\\.+|\\?+)$"
          + "|(?i)\\b(tbd|todo|lorem ipsum)\\b"
          + "|<[^>]+>|\\{\\{[^}]*}}|(?i)\\[(insert|add|fill|enter)[^\\]]*]");

  static final String SUPPORT_AGENT_SYSTEM_PROMPT = """
      You are a customer support agent for an online store. You help \
      customers with orders, returns, and refunds using the tools available \
      to you.

      Always verify the customer's identity with get_customer before taking \
      any action on their account, and never process a refund for a customer \
      get_customer has not returned as verified.

      If you cannot resolve the customer's issue yourself, escalate it with \
      escalate_to_human. The human agent will not see this conversation - \
      the handoff is all they get - so make every field specific and \
      self-contained. Then tell the customer their case has been passed to a \
      human agent; do not tell them it has been resolved.""";

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
   * The structured handoff - everything a human agent receives about an
   * escalated case, since they have no access to the conversation.
   *
   * @param customerId          the customer the case is about
   * @param conversationSummary what the customer asked for and what the agent already did
   * @param rootCause           why the agent could not resolve the issue itself
   * @param refundAmount        the refund amount in question, in USD
   * @param recommendedAction   what the human agent should do next
   */
  public record HandoffSummary(String customerId, String conversationSummary, String rootCause,
      double refundAmount, String recommendedAction) {
  }

  /**
   * escalate_to_human's output: confirmation that the handoff was queued.
   */
  public record HandoffConfirmation(String handoffId, String customerId, String status) {
  }

  /**
   * What one conversation produced: the agent's final response to the
   * customer and, if the agent escalated, the handoff a human received.
   */
  record SupportOutcome(String finalResponse, Optional<HandoffSummary> handoff) {
  }

  /**
   * The session-level state: the verified customer IDs the prerequisite gate
   * reads, as in Exercise 1.4.2, plus the handoff once one was accepted.
   */
  static final class SessionState {
    private final Set<String> verifiedCustomerIds = new HashSet<>();
    private HandoffSummary handoff;

    void recordVerifiedCustomer(String customerId) {
      verifiedCustomerIds.add(customerId);
    }

    boolean isVerified(String customerId) {
      return verifiedCustomerIds.contains(customerId);
    }

    void recordHandoff(HandoffSummary handoff) {
      this.handoff = handoff;
    }

    Optional<HandoffSummary> handoff() {
      return Optional.ofNullable(handoff);
    }
  }

  // Mock customer and order data standing in for a real CRM/order system.
  // ORD-5003 is above the automated refund limit, so refunding it needs a human.
  private static final List<CustomerLookup> CUSTOMERS = List.of(
      new CustomerLookup("CUST-1001", "Jane Doe", "jane@example.com", true),
      new CustomerLookup("CUST-1002", "John Smith", "john@example.com", false));

  private static final Map<String, OrderDetails> ORDERS = Map.of(
      "ORD-5001", new OrderDetails("ORD-5001", "CUST-1001",
          List.of("Wireless headphones"), 89.99, "delivered"),
      "ORD-5002", new OrderDetails("ORD-5002", "CUST-1002",
          List.of("Phone case", "Screen protector"), 24.50, "shipped"),
      "ORD-5003", new OrderDetails("ORD-5003", "CUST-1001",
          List.of("Standing desk"), 349.00, "delivered"));

  public static void main(String[] args) {

    AnthropicClient client = AnthropicClients.fromDotEnv();

    SupportOutcome outcome = runLoop(client, "Hi, I'm Jane Doe (jane@example.com). The "
        + "standing desk from order ORD-5003 arrived with a cracked frame - please refund the "
        + "full amount.");
    System.out.println("\n" + outcome.finalResponse());

    outcome.handoff().ifPresentOrElse(handoff -> {
      System.out.println("\nHandoff received by the human agent:");
      System.out.println("  customer_id:          " + handoff.customerId());
      System.out.println("  conversation_summary: " + handoff.conversationSummary());
      System.out.println("  root_cause:           " + handoff.rootCause());
      System.out.println("  refund_amount:        " + handoff.refundAmount());
      System.out.println("  recommended_action:   " + handoff.recommendedAction());
    }, () -> System.out.println("\nNo handoff - the agent resolved the issue itself."));
  }

  /**
   * Runs the support agent's loop for one conversation - one session. Sends
   * userMessage with the system prompt and all four tools, executes every
   * requested tool through {@link #executeTool}, and repeats while
   * stop_reason is tool_use. Returns once stop_reason is end_turn; any other
   * stop_reason is treated as unhandled.
   *
   * @param client      the Anthropic client to send requests through
   * @param userMessage the customer's message
   * @return the final text once stop_reason is end_turn, plus the handoff if one was accepted
   */
  static SupportOutcome runLoop(AnthropicClient client, String userMessage) {
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
          .addTool(getEscalateToHumanTool())
          .messages(messages)
          .build();

      Message response = client.messages().create(params);
      StopReason stopReason = response.stopReason().get();
      System.out.println("[turn " + iteration + "] stop_reason: " + stopReason);

      if (stopReason.equals(StopReason.END_TURN)) {
        return new SupportOutcome(extractFinalResponse(response), session.handoff());
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
   * Compiles escalate_to_human's input into a {@link HandoffSummary},
   * rejecting it unless all five fields are populated with real content.
   * Every problem is reported at once, so the model can fix them all in a
   * single retry.
   *
   * @param input escalate_to_human's input
   * @return the complete handoff
   * @throws IllegalArgumentException naming every missing, empty, or placeholder field
   */
  static HandoffSummary compileHandoff(Map<String, JsonValue> input) {
    List<String> problems = new ArrayList<>();

    String customerId = requireText(input, "customer_id", problems);
    String conversationSummary = requireText(input, "conversation_summary", problems);
    String rootCause = requireText(input, "root_cause", problems);
    String recommendedAction = requireText(input, "recommended_action", problems);

    double refundAmount = 0;
    JsonValue amount = input.get("refund_amount");
    if (amount == null) {
      problems.add("refund_amount is missing");
    } else {
      refundAmount = amount.convert(Double.class);
      if (refundAmount < 0) {
        problems.add("refund_amount must not be negative, got: " + refundAmount);
      }
    }

    if (!problems.isEmpty()) {
      throw new IllegalArgumentException("HANDOFF REJECTED: the human agent receives nothing "
          + "but this handoff, so every field must be specific and complete. Fix these fields "
          + "and call escalate_to_human again: " + String.join("; ", problems));
    }
    return new HandoffSummary(customerId, conversationSummary, rootCause, refundAmount,
        recommendedAction);
  }

  // Returns the trimmed text of a required field, adding a problem instead if
  // it is missing, blank, or placeholder text.
  private static String requireText(Map<String, JsonValue> input, String key,
      List<String> problems) {
    String value = stringArg(input, key);
    if (value == null) {
      problems.add(key + " is missing");
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      problems.add(key + " is empty");
    } else if (PLACEHOLDER.matcher(trimmed).find()) {
      problems.add(key + " contains placeholder text: \"" + trimmed + "\"");
    }
    return trimmed;
  }

  /**
   * Executes every tool_use block in a response, in the order Claude emitted
   * them, and packages the results into the single user message of
   * tool_result blocks the API expects as the next turn.
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
   * the call - the prerequisite gate refusing a refund, a refund above the
   * automated limit, an incomplete handoff - returns an is_error tool_result
   * for the model to react to, rather than throwing.
   *
   * @param toolUse the tool_use block requesting execution
   * @param session the current session's state
   * @return the tool_result block for this call
   * @throws IllegalArgumentException if the tool name is not one of the four tools
   */
  static ToolResultBlockParam executeTool(ToolUseBlock toolUse, SessionState session) {
    Map<String, JsonValue> input =
        toolUse._input().convert(new TypeReference<Map<String, JsonValue>>() {});

    return switch (toolUse.name()) {
      case "get_customer" -> handleGetCustomer(toolUse.id(), input, session);
      case "lookup_order" -> handleLookupOrder(toolUse.id(), input);
      case "process_refund" -> handleProcessRefund(toolUse.id(), input, session);
      case "escalate_to_human" -> handleEscalateToHuman(toolUse.id(), input, session);
      default -> throw new IllegalArgumentException("Unknown tool: " + toolUse.name());
    };
  }

  // get_customer is the only code path that writes to the verified set -
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

    // The prerequisite gate from Exercise 1.4.2.
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

  // Only a handoff that passes compileHandoff is recorded and "sent" to a human.
  private static ToolResultBlockParam handleEscalateToHuman(String toolUseId,
      Map<String, JsonValue> input, SessionState session) {
    HandoffSummary handoff;
    try {
      handoff = compileHandoff(input);
    } catch (IllegalArgumentException e) {
      System.out.println("[handoff] REJECTED - " + e.getMessage());
      return errorResult(toolUseId, e.getMessage());
    }

    session.recordHandoff(handoff);
    System.out.println("[handoff] ACCEPTED for " + handoff.customerId());
    return successResult(toolUseId, toJson(new HandoffConfirmation(
        "HO-" + handoff.customerId(), handoff.customerId(), "queued_for_human_agent")));
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
   * Exercise 1.4.1 - now stating the automated refund limit as well.
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
            customer ID the customer supplied themselves. Refunds above \
            $%.2f cannot be processed here; escalate those with \
            escalate_to_human instead. Issues a refund of the given amount to \
            the given customer and returns a confirmation: refund_id, \
            customer_id, amount, and status.""".formatted(AUTOMATED_REFUND_LIMIT))
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

  /**
   * The escalate_to_human tool definition. All five handoff fields are
   * required by the schema; {@link #compileHandoff} additionally rejects
   * empty and placeholder values, which a schema can't express.
   *
   * @return the escalate_to_human tool definition
   */
  static Tool getEscalateToHumanTool() {
    return Tool.builder()
        .name("escalate_to_human")
        .description("""
            Call this when you cannot resolve the customer's issue yourself - \
            e.g. a refund above the automated limit, a customer who cannot be \
            verified, or a request outside your tools - after gathering what \
            you can with the other tools. Hands the case to a human agent who \
            has NO access to this conversation: this handoff is all they \
            receive, so every field must be specific and self-contained, with \
            no placeholders like "N/A" or "TBD". Returns a confirmation: \
            handoff_id, customer_id, and status.""")
        .inputSchema(Tool.InputSchema.builder()
            .properties(Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("customer_id", JsonValue.from(Map.of(
                    "type", "string",
                    "description", "The customer ID returned by get_customer, e.g. 'CUST-1001'.")))
                .putAdditionalProperty("conversation_summary", JsonValue.from(Map.of(
                    "type", "string",
                    "description", "What the customer asked for and what you already did or "
                        + "found, including order IDs and items - written for someone who "
                        + "has not seen this conversation.")))
                .putAdditionalProperty("root_cause", JsonValue.from(Map.of(
                    "type", "string",
                    "description", "Why you could not resolve the issue yourself, e.g. the "
                        + "refund exceeds the automated limit.")))
                .putAdditionalProperty("refund_amount", JsonValue.from(Map.of(
                    "type", "number",
                    "minimum", 0,
                    "description", "The refund amount in question in USD, e.g. 349.00 - "
                        + "0 if the case involves no refund.")))
                .putAdditionalProperty("recommended_action", JsonValue.from(Map.of(
                    "type", "string",
                    "description", "The concrete next step you recommend the human agent "
                        + "take, e.g. 'Approve the refund of $349.00 for ORD-5003'.")))
                .build())
            .required(List.of("customer_id", "conversation_summary", "root_cause",
                "refund_amount", "recommended_action"))
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
    if (amount > AUTOMATED_REFUND_LIMIT) {
      throw new IllegalArgumentException(("Refund of $%.2f exceeds the automated refund limit "
          + "of $%.2f - it needs a human agent's approval. Escalate it with escalate_to_human.")
          .formatted(amount, AUTOMATED_REFUND_LIMIT));
    }
    return new RefundConfirmation("REF-" + customerId + "-" + Math.round(amount * 100),
        customerId, amount, "processed");
  }
}
