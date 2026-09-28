package dev.ccarf.d1.workflowenforcementandhandoff;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import dev.ccarf.common.AnthropicClients;
import dev.ccarf.common.Config;

/**
 * Exercise 1.4.5
 * Test the handoff with a multi-concern request (return plus billing dispute
 * plus account update) and verify the handoff summary is complete and
 * self-contained.
 *
 * <p>A multi-concern request tests decomposition: the agent has to split
 * one message into distinct concerns, investigate each, and resolve them
 * together - not handle them one after another, and not forget one. Here
 * each concern has its own investigation tool (lookup_order for the return,
 * get_billing_history for the dispute, get_account for the address change),
 * and the system prompt asks for all of them in the same response - the
 * Messages API's parallel tool use.</p>
 *
 * <p>The agent can only look things up, so every concern ends in one
 * unified handoff. The handoff of Exercise 1.4.4 becomes a list of
 * {@link Concern}s - each with its own findings, root cause, refund amount,
 * and recommended action - and {@link #compileHandoff} enforces coverage in
 * code: a concern the agent investigated but left out of the handoff gets
 * it rejected, just like an empty or placeholder field.</p>
 *
 * <p>{@link #checkHandoffCoversAllConcerns} then verifies the run against
 * what the exercise expects to see: all three concerns investigated in
 * parallel, and all three in the handoff with their specific details.</p>
 */
public class MultiConcernHandoff {

  private static final int MAX_ITERATIONS = 20;

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
      You are a customer support agent for an online store. You can look up \
      customers, orders, billing history, and account details, but you \
      cannot change anything: returns, billing disputes, and account changes \
      are carried out by a human agent from your handoff.

      Handle every request like this:
      1. Identify the customer with get_customer.
      2. Break the request into its distinct concerns - a customer message \
      often contains more than one.
      3. Investigate all concerns in parallel: issue the lookups for every \
      concern in the same response, not one after another.
      4. Escalate once with escalate_to_human, with one entry per concern. \
      The human agent will not see this conversation - the handoff is all \
      they get - so give each concern its specific findings: order IDs, \
      charge IDs, dates, amounts, addresses.
      Then tell the customer which concerns were passed to a human agent; do \
      not tell them anything has been resolved.""";

  static final String MULTI_CONCERN_MESSAGE = """
      Hi, I'm Jane Doe (jane@example.com). The espresso machine from order \
      ORD-5004 stopped working after a few weeks and I'd like to return it. \
      While checking my card statement I also noticed I was charged twice \
      for that order, which I'm disputing. Oh, and I've moved - my new \
      shipping address is 48 New Road, Springfield, IL 62704.""";

  /**
   * The three kinds of concern this agent can investigate, each with the
   * tool that investigates it.
   */
  enum ConcernType {
    RETURN("lookup_order"),
    BILLING_DISPUTE("get_billing_history"),
    ACCOUNT_UPDATE("get_account");

    private final String investigationTool;

    ConcernType(String investigationTool) {
      this.investigationTool = investigationTool;
    }

    String investigationTool() {
      return investigationTool;
    }

    // The name used in tool input, e.g. "billing_dispute".
    String jsonName() {
      return name().toLowerCase(Locale.ROOT);
    }

    static Optional<ConcernType> investigatedBy(String toolName) {
      for (ConcernType type : values()) {
        if (type.investigationTool.equals(toolName)) {
          return Optional.of(type);
        }
      }
      return Optional.empty();
    }
  }

  /**
   * get_customer's output: the customer's ID and whether their identity is
   * verified.
   */
  public record CustomerLookup(String customerId, String name, String email, boolean verified) {
  }

  /**
   * lookup_order's output: the details of one order, including when it was
   * delivered and until when it can be returned.
   */
  public record OrderDetails(String orderId, String customerId, List<String> items,
      double amountPaid, String status, String deliveredOn, String returnWindowEndsOn) {
  }

  /**
   * One card charge in get_billing_history's output.
   */
  public record Charge(String chargeId, String orderId, double amount, String chargedOn) {
  }

  /**
   * get_billing_history's output: every charge on the customer's account.
   */
  public record BillingHistory(String customerId, List<Charge> charges) {
  }

  /**
   * get_account's output: the customer's current account details.
   */
  public record AccountDetails(String customerId, String email, String shippingAddress) {
  }

  /**
   * One concern in the handoff - the five-field handoff of Exercise 1.4.4,
   * scoped to a single issue.
   *
   * @param type              which kind of concern this is
   * @param findings          what the investigation found, with specific IDs, dates, and amounts
   * @param rootCause         why the agent could not resolve this concern itself
   * @param refundAmount      the refund amount in question for this concern, in USD
   * @param recommendedAction what the human agent should do about this concern
   */
  public record Concern(ConcernType type, String findings, String rootCause,
      double refundAmount, String recommendedAction) {
  }

  /**
   * The unified handoff - everything a human agent receives about the case,
   * with one {@link Concern} per issue the customer raised.
   */
  public record HandoffSummary(String customerId, String conversationSummary,
      List<Concern> concerns) {

    double totalRefundAmount() {
      return concerns.stream().mapToDouble(Concern::refundAmount).sum();
    }
  }

  /**
   * escalate_to_human's output: confirmation that the handoff was queued.
   */
  public record HandoffConfirmation(String handoffId, String customerId, int concernCount,
      String status) {
  }

  /**
   * One tool call the agent made, with the turn it was made in - calls
   * from the same turn were issued in parallel.
   */
  record ToolCallRecord(int turn, String toolName, boolean isError) {
  }

  /**
   * What one conversation produced: the agent's final response, the handoff
   * if one was accepted, and every tool call made along the way.
   */
  record MultiConcernOutcome(String finalResponse, Optional<HandoffSummary> handoff,
      List<ToolCallRecord> toolCalls) {
  }

  /**
   * The session-level state: the customer IDs get_customer returned, the
   * concerns investigated so far, the tool-call log, and the handoff once
   * one was accepted.
   */
  static final class SessionState {
    private final Set<String> identifiedCustomerIds = new HashSet<>();
    private final Set<ConcernType> investigatedConcerns = EnumSet.noneOf(ConcernType.class);
    private final List<ToolCallRecord> toolCalls = new ArrayList<>();
    private HandoffSummary handoff;

    void recordIdentifiedCustomer(String customerId) {
      identifiedCustomerIds.add(customerId);
    }

    boolean isIdentified(String customerId) {
      return identifiedCustomerIds.contains(customerId);
    }

    void recordToolCall(ToolCallRecord toolCall) {
      toolCalls.add(toolCall);
      if (!toolCall.isError()) {
        ConcernType.investigatedBy(toolCall.toolName()).ifPresent(investigatedConcerns::add);
      }
    }

    Set<ConcernType> investigatedConcerns() {
      return EnumSet.copyOf(investigatedConcerns);
    }

    List<ToolCallRecord> toolCalls() {
      return List.copyOf(toolCalls);
    }

    void recordHandoff(HandoffSummary handoff) {
      this.handoff = handoff;
    }

    Optional<HandoffSummary> handoff() {
      return Optional.ofNullable(handoff);
    }
  }

  // Mock data standing in for real CRM, order, and billing systems. ORD-5004
  // is past its return window, and it was charged twice (CHG-7001, CHG-7002).
  private static final List<CustomerLookup> CUSTOMERS = List.of(
      new CustomerLookup("CUST-1001", "Jane Doe", "jane@example.com", true),
      new CustomerLookup("CUST-1002", "John Smith", "john@example.com", false));

  private static final Map<String, OrderDetails> ORDERS = Map.of(
      "ORD-5001", new OrderDetails("ORD-5001", "CUST-1001", List.of("Wireless headphones"),
          89.99, "delivered", "2026-09-15", "2026-10-15"),
      "ORD-5004", new OrderDetails("ORD-5004", "CUST-1001", List.of("Espresso machine"),
          219.00, "delivered", "2026-08-10", "2026-09-09"));

  private static final Map<String, List<Charge>> CHARGES = Map.of(
      "CUST-1001", List.of(
          new Charge("CHG-7001", "ORD-5004", 219.00, "2026-08-08"),
          new Charge("CHG-7002", "ORD-5004", 219.00, "2026-08-08"),
          new Charge("CHG-7003", "ORD-5001", 89.99, "2026-09-12")));

  private static final Map<String, AccountDetails> ACCOUNTS = Map.of(
      "CUST-1001", new AccountDetails("CUST-1001", "jane@example.com",
          "12 Old Street, Springfield, IL 62701"));

  // The specific detail the handoff must carry for each of the scenario's
  // concerns - the order to return, the duplicate charge, the new address.
  static final Map<ConcernType, String> EXPECTED_DETAILS = Map.of(
      ConcernType.RETURN, "ORD-5004",
      ConcernType.BILLING_DISPUTE, "CHG-7002",
      ConcernType.ACCOUNT_UPDATE, "48 New Road");

  public static void main(String[] args) {

    AnthropicClient client = AnthropicClients.fromDotEnv();

    MultiConcernOutcome outcome = runLoop(client, MULTI_CONCERN_MESSAGE);
    System.out.println("\n" + outcome.finalResponse());

    System.out.println("\nTool calls:");
    outcome.toolCalls().forEach(call -> System.out.println("  [turn " + call.turn() + "] "
        + call.toolName() + (call.isError() ? " -> error" : "")));

    outcome.handoff().ifPresent(handoff -> {
      System.out.println("\nHandoff received by the human agent:");
      System.out.println("  customer_id:          " + handoff.customerId());
      System.out.println("  conversation_summary: " + handoff.conversationSummary());
      for (Concern concern : handoff.concerns()) {
        System.out.println("  - " + concern.type().jsonName());
        System.out.println("      findings:           " + concern.findings());
        System.out.println("      root_cause:         " + concern.rootCause());
        System.out.println("      refund_amount:      " + concern.refundAmount());
        System.out.println("      recommended_action: " + concern.recommendedAction());
      }
      System.out.println("  total refund amount:  " + handoff.totalRefundAmount());
    });

    List<String> failures = checkHandoffCoversAllConcerns(outcome, EXPECTED_DETAILS);
    if (failures.isEmpty()) {
      System.out.println("\nPASS: all three concerns were investigated in parallel and the "
          + "handoff covers each one with its specific details.");
    } else {
      System.out.println("\nFAIL:");
      failures.forEach(failure -> System.out.println("  - " + failure));
    }
  }

  /**
   * Runs the support agent's loop for one conversation - one session. Sends
   * userMessage with the system prompt and all five tools, executes every
   * requested tool through {@link #executeTool}, and repeats while
   * stop_reason is tool_use. Returns once stop_reason is end_turn; any other
   * stop_reason is treated as unhandled.
   *
   * @param client      the Anthropic client to send requests through
   * @param userMessage the customer's message
   * @return the final text, the handoff if one was accepted, and every tool call made
   */
  static MultiConcernOutcome runLoop(AnthropicClient client, String userMessage) {
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
          .addTool(getBillingHistoryTool())
          .addTool(getAccountTool())
          .addTool(getEscalateToHumanTool())
          .messages(messages)
          .build();

      Message response = client.messages().create(params);
      StopReason stopReason = response.stopReason().get();
      System.out.println("[turn " + iteration + "] stop_reason: " + stopReason);

      if (stopReason.equals(StopReason.END_TURN)) {
        return new MultiConcernOutcome(extractFinalResponse(response), session.handoff(),
            session.toolCalls());
      }

      if (!stopReason.equals(StopReason.TOOL_USE)) {
        throw new IllegalStateException("Unhandled stop_reason: " + stopReason);
      }

      messages.add(response.toParam());
      messages.add(executeToolCalls(response, iteration, session));
    }

    System.err.println("WARNING: safety cap of " + MAX_ITERATIONS + " tool-use iterations hit "
        + "without reaching a terminal stop_reason.");
    throw new IllegalStateException(
        "Exceeded " + MAX_ITERATIONS + " tool-use iterations without reaching end_turn");
  }

  /**
   * Checks a conversation against what the exercise expects to see: a
   * handoff was produced; every expected concern is in it, with its
   * specific detail somewhere in that concern's entry; and every expected
   * concern was investigated, all in the same turn - in parallel.
   *
   * @param outcome         the conversation's outcome
   * @param expectedDetails each expected concern and the detail its entry must mention
   * @return one message per unmet expectation - empty if the handoff is complete
   */
  static List<String> checkHandoffCoversAllConcerns(MultiConcernOutcome outcome,
      Map<ConcernType, String> expectedDetails) {
    List<String> failures = new ArrayList<>();

    Map<ConcernType, Integer> investigatedInTurn = new LinkedHashMap<>();
    for (ToolCallRecord call : outcome.toolCalls()) {
      if (!call.isError()) {
        ConcernType.investigatedBy(call.toolName())
            .ifPresent(type -> investigatedInTurn.putIfAbsent(type, call.turn()));
      }
    }

    for (ConcernType type : new TreeSet<>(expectedDetails.keySet())) {
      if (!investigatedInTurn.containsKey(type)) {
        failures.add(type.jsonName() + " was never investigated (no successful "
            + type.investigationTool() + " call)");
      }
    }
    Set<Integer> turns = expectedDetails.keySet().stream()
        .filter(investigatedInTurn::containsKey)
        .map(investigatedInTurn::get)
        .collect(Collectors.toCollection(TreeSet::new));
    if (turns.size() > 1) {
      failures.add("the concerns were investigated one after another (turns " + turns
          + ") rather than in parallel");
    }

    if (outcome.handoff().isEmpty()) {
      failures.add("no handoff was produced");
      return failures;
    }
    Map<ConcernType, Concern> handedOff = outcome.handoff().get().concerns().stream()
        .collect(Collectors.toMap(Concern::type, concern -> concern));
    for (ConcernType type : new TreeSet<>(expectedDetails.keySet())) {
      Concern concern = handedOff.get(type);
      String detail = expectedDetails.get(type);
      if (concern == null) {
        failures.add("the handoff omits the " + type.jsonName() + " concern");
      } else if (!mentions(concern, detail)) {
        failures.add("the handoff's " + type.jsonName() + " entry never mentions \""
            + detail + "\"");
      }
    }
    return failures;
  }

  private static boolean mentions(Concern concern, String detail) {
    String text = String.join(" ", concern.findings(), concern.rootCause(),
        concern.recommendedAction()).toLowerCase(Locale.ROOT);
    return text.contains(detail.toLowerCase(Locale.ROOT));
  }

  /**
   * Compiles escalate_to_human's input into a {@link HandoffSummary},
   * rejecting it unless it is complete: every field populated with real
   * content, the customer_id one get_customer returned, each concern type
   * listed at most once - and every concern investigated in this session
   * covered. All problems are reported at once, so the model can fix them
   * in a single retry.
   *
   * @param input   escalate_to_human's input
   * @param session the current session's state
   * @return the complete handoff
   * @throws IllegalArgumentException naming every problem found
   */
  static HandoffSummary compileHandoff(Map<String, JsonValue> input, SessionState session) {
    List<String> problems = new ArrayList<>();

    String customerId = requireText(input, "customer_id", problems);
    if (customerId != null && !customerId.isEmpty() && !session.isIdentified(customerId)) {
      problems.add("customer_id " + customerId + " was not returned by get_customer in this "
          + "session");
    }
    String conversationSummary = requireText(input, "conversation_summary", problems);

    List<Map<String, JsonValue>> rawConcerns = List.of();
    JsonValue concernsValue = input.get("concerns");
    if (concernsValue == null) {
      problems.add("concerns is missing");
    } else {
      try {
        rawConcerns = concernsValue.convert(new TypeReference<List<Map<String, JsonValue>>>() {});
        if (rawConcerns.isEmpty()) {
          problems.add("concerns is empty - add one entry per concern the customer raised");
        }
      } catch (RuntimeException e) {
        problems.add("concerns must be an array of concern objects");
      }
    }

    List<Concern> concerns = new ArrayList<>();

    Set<ConcernType> covered = EnumSet.noneOf(ConcernType.class);
    for (int i = 0; i < rawConcerns.size(); i++) {
      Concern concern = compileConcern(rawConcerns.get(i), "concerns[" + i + "]", problems);
      if (concern == null) {
        continue;
      }
      if (!covered.add(concern.type())) {
        problems.add("concerns lists " + concern.type().jsonName() + " more than once - "
            + "combine it into one entry");
      }
      concerns.add(concern);
    }

    // The coverage rule: a concern the agent investigated can't silently
    // drop out of the handoff.
    Set<ConcernType> omitted = session.investigatedConcerns();
    omitted.removeAll(covered);
    for (ConcernType type : omitted) {
      problems.add("you investigated a " + type.jsonName() + " concern (" + type.investigationTool()
          + ") but the handoff has no entry for it");
    }

    if (!problems.isEmpty()) {
      throw new IllegalArgumentException("HANDOFF REJECTED: the human agent receives nothing "
          + "but this handoff, so it must cover every concern, each specific and complete. "
          + "Fix these problems and call escalate_to_human again: " + String.join("; ", problems));
    }
    return new HandoffSummary(customerId, conversationSummary, List.copyOf(concerns));
  }

  // Compiles one entry of the concerns array, adding a problem (prefixed with
  // where it is) for every missing, empty, or placeholder field. Returns
  // null if the entry's type is unusable.
  private static Concern compileConcern(Map<String, JsonValue> raw, String where,
      List<String> problems) {
    List<String> concernProblems = new ArrayList<>();

    ConcernType type = null;
    String typeName = stringArg(raw, "type");
    if (typeName == null) {
      concernProblems.add("type is missing");
    } else {
      try {
        type = ConcernType.valueOf(typeName.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        concernProblems.add("type \"" + typeName + "\" is not one of return, billing_dispute, "
            + "account_update");
      }
    }
    String findings = requireText(raw, "findings", concernProblems);
    String rootCause = requireText(raw, "root_cause", concernProblems);
    String recommendedAction = requireText(raw, "recommended_action", concernProblems);

    double refundAmount = 0;
    JsonValue amount = raw.get("refund_amount");
    if (amount == null) {
      concernProblems.add("refund_amount is missing");
    } else {
      refundAmount = amount.convert(Double.class);
      if (refundAmount < 0) {
        concernProblems.add("refund_amount must not be negative, got: " + refundAmount);
      }
    }

    String label = type == null ? where : where + " (" + type.jsonName() + ")";
    concernProblems.forEach(problem -> problems.add(label + ": " + problem));
    return type == null ? null
        : new Concern(type, findings, rootCause, refundAmount, recommendedAction);
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
   * tool_result blocks the API expects as the next turn. When Claude
   * investigates several concerns in parallel, they all arrive here in one
   * response - the stubs are instant, so running them one by one is fine.
   *
   * @param response the assistant response whose stop_reason was tool_use
   * @param turn     the loop iteration this response came from
   * @param session  the current session's state
   * @return a user MessageParam carrying one tool_result block per tool_use block
   */
  private static MessageParam executeToolCalls(Message response, int turn,
      SessionState session) {
    List<ContentBlockParam> toolResults = new ArrayList<>();
    for (ToolUseBlock toolUse : response.content().stream()
        .flatMap(block -> block.toolUse().stream())
        .toList()) {
      ToolResultBlockParam result = executeTool(toolUse, session);
      session.recordToolCall(
          new ToolCallRecord(turn, toolUse.name(), result.isError().orElse(false)));
      toolResults.add(ContentBlockParam.ofToolResult(result));
    }

    return MessageParam.builder()
        .role(MessageParam.Role.USER)
        .contentOfBlockParams(toolResults)
        .build();
  }

  /**
   * Routes a tool_use block to its handler. A handler that can't complete
   * the call - a lookup miss, an incomplete handoff - returns an is_error
   * tool_result for the model to react to, rather than throwing.
   *
   * @param toolUse the tool_use block requesting execution
   * @param session the current session's state
   * @return the tool_result block for this call
   * @throws IllegalArgumentException if the tool name is not one of the five tools
   */
  static ToolResultBlockParam executeTool(ToolUseBlock toolUse, SessionState session) {
    Map<String, JsonValue> input =
        toolUse._input().convert(new TypeReference<Map<String, JsonValue>>() {});

    return switch (toolUse.name()) {
      case "get_customer" -> handleGetCustomer(toolUse.id(), input, session);
      case "lookup_order" -> lookupResult(toolUse.id(),
          () -> lookupOrder(stringArg(input, "order_id")));
      case "get_billing_history" -> lookupResult(toolUse.id(),
          () -> getBillingHistory(stringArg(input, "customer_id")));
      case "get_account" -> lookupResult(toolUse.id(),
          () -> getAccount(stringArg(input, "customer_id")));
      case "escalate_to_human" -> handleEscalateToHuman(toolUse.id(), input, session);
      default -> throw new IllegalArgumentException("Unknown tool: " + toolUse.name());
    };
  }

  private static ToolResultBlockParam handleGetCustomer(String toolUseId,
      Map<String, JsonValue> input, SessionState session) {
    CustomerLookup customer;
    try {
      customer = getCustomer(stringArg(input, "name"), stringArg(input, "email"));
    } catch (IllegalArgumentException e) {
      return errorResult(toolUseId, e.getMessage());
    }

    session.recordIdentifiedCustomer(customer.customerId());
    return successResult(toolUseId, toJson(customer));
  }

  // Runs one of the three read-only lookups, turning a miss into an error result.
  private static ToolResultBlockParam lookupResult(String toolUseId,
      Supplier<Object> lookup) {
    try {
      return successResult(toolUseId, toJson(lookup.get()));
    } catch (IllegalArgumentException e) {
      return errorResult(toolUseId, e.getMessage());
    }
  }

  // Only a handoff that passes compileHandoff is recorded and "sent" to a human.
  private static ToolResultBlockParam handleEscalateToHuman(String toolUseId,
      Map<String, JsonValue> input, SessionState session) {
    HandoffSummary handoff;
    try {
      handoff = compileHandoff(input, session);
    } catch (IllegalArgumentException e) {
      System.out.println("[handoff] REJECTED - " + e.getMessage());
      return errorResult(toolUseId, e.getMessage());
    }

    session.recordHandoff(handoff);
    System.out.println("[handoff] ACCEPTED for " + handoff.customerId() + " with "
        + handoff.concerns().size() + " concern(s)");
    return successResult(toolUseId, toJson(new HandoffConfirmation("HO-" + handoff.customerId(),
        handoff.customerId(), handoff.concerns().size(), "queued_for_human_agent")));
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
            Call this first, before any other action on a customer's account, \
            whenever the customer has not yet been identified in this \
            conversation. Looks up a customer by name or email (provide at \
            least one) and returns their customer_id and a verified flag \
            (true/false). The other lookups and the handoff need this \
            customer_id.""")
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
   * The lookup_order tool definition - investigates a return.
   *
   * @return the lookup_order tool definition
   */
  static Tool getLookupOrderTool() {
    return Tool.builder()
        .name("lookup_order")
        .description("""
            Call this to investigate a return, or whenever the customer refers \
            to a specific order, rather than relying on what the customer says \
            the order contained. Returns the order's details: order_id, the \
            customer_id it belongs to, items, amount_paid, status, \
            delivered_on, and return_window_ends_on.""")
        .inputSchema(Tool.InputSchema.builder()
            .properties(Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("order_id", JsonValue.from(Map.of(
                    "type", "string",
                    "description", "The order ID to look up, e.g. 'ORD-5004'.")))
                .build())
            .required(List.of("order_id"))
            .build())
        .build();
  }

  /**
   * The get_billing_history tool definition - investigates a billing dispute.
   *
   * @return the get_billing_history tool definition
   */
  static Tool getBillingHistoryTool() {
    return Tool.builder()
        .name("get_billing_history")
        .description("""
            Call this to investigate a billing dispute - e.g. a duplicate, \
            unexpected, or incorrect charge - before escalating it. Returns \
            every charge on the customer's account: charge_id, order_id, \
            amount, and charged_on.""")
        .inputSchema(Tool.InputSchema.builder()
            .properties(Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("customer_id", JsonValue.from(Map.of(
                    "type", "string",
                    "description", "The customer ID returned by get_customer.")))
                .build())
            .required(List.of("customer_id"))
            .build())
        .build();
  }

  /**
   * The get_account tool definition - investigates an account update.
   *
   * @return the get_account tool definition
   */
  static Tool getAccountTool() {
    return Tool.builder()
        .name("get_account")
        .description("""
            Call this to investigate an account update request - e.g. a new \
            shipping address or email - so the handoff can state both the \
            current and the requested value. Returns the customer's current \
            account details: customer_id, email, and shipping_address.""")
        .inputSchema(Tool.InputSchema.builder()
            .properties(Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("customer_id", JsonValue.from(Map.of(
                    "type", "string",
                    "description", "The customer ID returned by get_customer.")))
                .build())
            .required(List.of("customer_id"))
            .build())
        .build();
  }

  /**
   * The escalate_to_human tool definition - the handoff of Exercise 1.4.4
   * with one entry per concern. {@link #compileHandoff} additionally
   * rejects empty and placeholder values and omitted concerns, which a
   * schema can't express.
   *
   * @return the escalate_to_human tool definition
   */
  static Tool getEscalateToHumanTool() {
    Map<String, Object> concernSchema = Map.of(
        "type", "object",
        "properties", Map.of(
            "type", Map.of(
                "type", "string",
                "enum", List.of("return", "billing_dispute", "account_update"),
                "description", "Which kind of concern this entry covers."),
            "findings", Map.of(
                "type", "string",
                "description", "What your investigation found for this concern, with the "
                    + "specific IDs, dates, amounts, or addresses involved."),
            "root_cause", Map.of(
                "type", "string",
                "description", "Why this concern needs a human agent, e.g. the return "
                    + "window has passed."),
            "refund_amount", Map.of(
                "type", "number",
                "minimum", 0,
                "description", "The refund amount in question for this concern in USD - "
                    + "0 if it involves no refund."),
            "recommended_action", Map.of(
                "type", "string",
                "description", "The concrete next step you recommend for this concern.")),
        "required", List.of("type", "findings", "root_cause", "refund_amount",
            "recommended_action"));

    return Tool.builder()
        .name("escalate_to_human")
        .description("""
            Call this once, after you have investigated every concern in the \
            customer's request, to hand the whole case to a human agent - one \
            handoff covering all concerns, not one per concern. The human agent \
            has NO access to this conversation: this handoff is all they \
            receive, so include one entry per concern, each specific and \
            self-contained, with no placeholders like "N/A" or "TBD". Returns a \
            confirmation: handoff_id, customer_id, concern_count, and status.""")
        .inputSchema(Tool.InputSchema.builder()
            .properties(Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("customer_id", JsonValue.from(Map.of(
                    "type", "string",
                    "description", "The customer ID returned by get_customer, e.g. 'CUST-1001'.")))
                .putAdditionalProperty("conversation_summary", JsonValue.from(Map.of(
                    "type", "string",
                    "description", "What the customer asked for overall, written for someone "
                        + "who has not seen this conversation.")))
                .putAdditionalProperty("concerns", JsonValue.from(Map.of(
                    "type", "array",
                    "minItems", 1,
                    "items", concernSchema,
                    "description", "One entry per distinct concern the customer raised.")))
                .build())
            .required(List.of("customer_id", "conversation_summary", "concerns"))
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

  // get_billing_history's stub implementation.
  private static BillingHistory getBillingHistory(String customerId) {
    List<Charge> charges = CHARGES.get(customerId);
    if (charges == null) {
      throw new IllegalArgumentException("No billing history found for " + customerId);
    }
    return new BillingHistory(customerId, charges);
  }

  // get_account's stub implementation.
  private static AccountDetails getAccount(String customerId) {
    AccountDetails account = ACCOUNTS.get(customerId);
    if (account == null) {
      throw new IllegalArgumentException("No account found for " + customerId);
    }
    return account;
  }
}
