package dev.ccarf.d1.workflowenforcementandhandoff;

import java.util.List;
import java.util.Map;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;

import dev.ccarf.common.Config;

/**
 * Exercise 1.4.1
 * Create a customer support agent with three tools, each with a proper JSON
 * Schema input_schema:
 * - get_customer (accepts a name or email, returns customer ID and
 *   verification status)
 * - lookup_order (accepts an order ID, returns order details)
 * - process_refund (accepts a customer ID and amount, processes a refund
 *   for that amount)
 *
 * <p>Note on outputs: a custom tool in the Messages API declares only an
 * input_schema - there is no output schema field on {@link Tool}. What a
 * tool "returns" is whatever the caller's code puts into the tool_result
 * block. So each tool's output contract lives in two places here: its
 * description (the only way the model learns what it will get back) and a
 * Java record ({@link CustomerLookup}, {@link OrderDetails},
 * {@link RefundConfirmation}) returned by the tool's stub implementation.</p>
 *
 * <p>These three tools have a workflow dependency - a refund must only be
 * processed for a customer get_customer has returned as verified. At this
 * step that dependency lives only in the tool descriptions and the system
 * prompt, i.e. it is prompt-based guidance, which the model follows most of
 * the time but not deterministically. Exercise 1.4.2 adds the programmatic
 * prerequisite gate that actually enforces it.</p>
 */
public class CustomerSupportToolDefinitions {

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

    AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    Tool getCustomer = getGetCustomerTool();
    Tool lookupOrder = getLookupOrderTool();
    Tool processRefund = getProcessRefundTool();

    MessageCreateParams params = getParams(getCustomer, lookupOrder, processRefund);

    System.out.println("Tools registered on the support agent's request: "
        + params.tools().orElseThrow().size());
    System.out.println("- " + getCustomer.name() + ": " + getCustomer.description().orElse(""));
    System.out.println("- " + lookupOrder.name() + ": " + lookupOrder.description().orElse(""));
    System.out.println("- " + processRefund.name() + ": " + processRefund.description().orElse(""));

    System.out.println("\nSample tool outputs (stub implementations, no request sent):");
    System.out.println("- get_customer(email=jane@example.com) -> "
        + getCustomer(null, "jane@example.com"));
    System.out.println("- lookup_order(order_id=ORD-5001) -> " + lookupOrder("ORD-5001"));
    System.out.println("- process_refund(customer_id=CUST-1001, amount=89.99) -> "
        + processRefund("CUST-1001", 89.99));

    System.out.println("\nSupport agent ready for model " + Config.modelMain()
        + " (" + client.getClass().getSimpleName() + "), no request sent yet.");
  }

  // Helper to build the support agent's request carrying all three tools.
  private static MessageCreateParams getParams(Tool getCustomer, Tool lookupOrder, Tool processRefund) {
    return MessageCreateParams.builder()
        .model(Config.modelMain())
        .maxTokens(Config.maxTokens())
        .system(SUPPORT_AGENT_SYSTEM_PROMPT)
        .addTool(getCustomer)
        .addTool(lookupOrder)
        .addTool(processRefund)
        .addUserMessage("What can you help me with?")
        .build();
  }

  /**
   * The get_customer tool - looks a customer up by name or email and
   * returns their customer ID and verification status. Neither property is
   * individually required, since either one is enough to identify a
   * customer.
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
   * The lookup_order tool - returns an order's details by its ID.
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
   * The process_refund tool - issues a refund of the given amount to the
   * given customer. Both properties are required.
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

  /**
   * get_customer's stub implementation - matches on name or email
   * (case-insensitive) against the mock customer data.
   *
   * @param name  the customer's name, or null
   * @param email the customer's email, or null
   * @return the matching customer's ID and verification status
   * @throws IllegalArgumentException if neither name nor email is given, or
   *                                  no customer matches
   */
  static CustomerLookup getCustomer(String name, String email) {
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

  /**
   * lookup_order's stub implementation.
   *
   * @param orderId the order ID to look up
   * @return the order's details
   * @throws IllegalArgumentException if no order has that ID
   */
  static OrderDetails lookupOrder(String orderId) {
    OrderDetails order = ORDERS.get(orderId);
    if (order == null) {
      throw new IllegalArgumentException("No order found with ID " + orderId);
    }
    return order;
  }

  /**
   * process_refund's stub implementation - no real payment call. Note that
   * nothing here checks whether the customer was verified first: at this
   * step that rule is prompt guidance only (see the class Javadoc).
   *
   * @param customerId the customer to refund
   * @param amount     the refund amount in USD
   * @return a confirmation of the issued refund
   * @throws IllegalArgumentException if the amount is not positive
   */
  static RefundConfirmation processRefund(String customerId, double amount) {
    if (amount <= 0) {
      throw new IllegalArgumentException("Refund amount must be positive, got: " + amount);
    }
    return new RefundConfirmation("REF-" + customerId + "-" + Math.round(amount * 100),
        customerId, amount, "processed");
  }
}
