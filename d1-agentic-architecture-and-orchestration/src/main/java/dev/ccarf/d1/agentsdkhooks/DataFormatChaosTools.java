package dev.ccarf.d1.agentsdkhooks;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import dev.ccarf.common.AnthropicClients;
import dev.ccarf.common.Config;

/**
 * Exercise 1.5.1
 * Create an agent with three MCP tools that return data in different formats:
 * - Tool A (get_customer) returns Unix timestamps and numeric status codes
 * - Tool B (lookup_order) returns ISO 8601 dates and string statuses
 * - Tool C (check_shipping) returns DD/MM/YYYY dates and single-character
 *   status codes
 *
 * <p>This recreates the exam's "data format chaos" example. The three tools
 * stand in for three MCP servers backed by different systems, each with its
 * own conventions. Without normalization, the model has to interpret three
 * date formats and three status representations on every iteration - e.g.
 * is "04/03/2026" the 4th of March or April 3rd, and does "P" mean
 * "pending" or "processed"? - which leads to inconsistent parsing across
 * iterations. The PostToolUse hook that fixes this comes in the next
 * exercise; at this step the formats are deliberately left as-is.</p>
 *
 * <p>Note on MCP: the tools are defined here as plain Messages API tools
 * with stub implementations, since what matters for this section is the
 * shape of the data they return, not the transport it arrives over. Each
 * tool's output contract lives in its description and in a Java record
 * ({@link CustomerRecord}, {@link OrderRecord}, {@link ShipmentRecord}),
 * as in Exercise 1.4.1.</p>
 */
public class DataFormatChaosTools {

  private static final ObjectMapper JSON = new ObjectMapper()
      .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

  static final String SUPPORT_AGENT_SYSTEM_PROMPT = """
      You are a customer support agent for an online store. You help \
      customers with questions about their account, orders, and deliveries \
      using the tools available to you.""";

  /**
   * get_customer's output, from the CRM system.
   *
   * @param createdAt Unix epoch seconds, e.g. 1709294400
   * @param status    numeric account status: 1 = active, 2 = suspended,
   *                  3 = closed
   */
  public record CustomerRecord(String customerId, String name, long createdAt, int status) {
  }

  /**
   * lookup_order's output, from the order system.
   *
   * @param orderedAt ISO 8601 UTC timestamp, e.g. "2026-03-02T09:30:00Z"
   * @param status    English status string, e.g. "shipped", "processing"
   */
  public record OrderRecord(String orderId, String customerId, String orderedAt, String status) {
  }

  /**
   * check_shipping's output, from the shipping carrier.
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
  // status "P" (pending) - easy to conflate.
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

    Tool getCustomer = getGetCustomerTool();
    Tool lookupOrder = getLookupOrderTool();
    Tool checkShipping = getCheckShippingTool();

    MessageCreateParams params = getParams(getCustomer, lookupOrder, checkShipping);

    System.out.println("Tools registered on the support agent's request: " + params.tools().orElseThrow().size());
    System.out.println("- " + getCustomer.name() + ": " + getCustomer.description().orElse(""));
    System.out.println("- " + lookupOrder.name() + ": " + lookupOrder.description().orElse(""));
    System.out.println("- " + checkShipping.name() + ": " + checkShipping.description().orElse(""));

    System.out.println("\nCalling each tool's local stub directly (no API call) - this is the raw JSON the model would get back in a tool_result:");
    System.out.println("- get_customer(customer_id=CUST-1001) -> " + toJson(getCustomer("CUST-1001")));
    System.out.println("- lookup_order(order_id=ORD-5002) -> " + toJson(lookupOrder("ORD-5002")));
    System.out.println("- check_shipping(order_id=ORD-5002) -> " + toJson(checkShipping("ORD-5002")));

    System.out.println("\nSupport agent ready for model " + Config.modelMain()
        + " (" + client.getClass().getSimpleName() + "), no request sent yet.");
  }

  // Helper to build the support agent's request carrying all three tools.
  private static MessageCreateParams getParams(Tool getCustomer, Tool lookupOrder, Tool checkShipping) {
    return MessageCreateParams.builder()
        .model(Config.modelMain())
        .maxTokens(Config.maxTokens())
        .system(SUPPORT_AGENT_SYSTEM_PROMPT)
        .addTool(getCustomer)
        .addTool(lookupOrder)
        .addTool(checkShipping)
        .addUserMessage("What can you help me with?")
        .build();
  }

  /**
   * Tool A, get_customer - looks a customer up by ID. Returns epoch-second
   * timestamps and numeric status codes.
   *
   * @return the get_customer tool definition
   */
  static Tool getGetCustomerTool() {
    return Tool.builder()
        .name("get_customer")
        .description("""
            Call this whenever the customer asks about their account - e.g. \
            when it was opened or whether it is active - and you have their \
            customer ID. Returns customer_id, name, created_at (Unix epoch \
            seconds) and status (1 = active, 2 = suspended, 3 = closed).""")
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
   * Tool B, lookup_order - returns an order's details by its ID. Returns ISO
   * 8601 timestamps and English status strings.
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
   * Tool C, check_shipping - returns an order's shipment status and
   * estimated delivery date. Returns DD/MM/YYYY dates and single-character
   * status codes.
   *
   * @return the check_shipping tool definition
   */
  static Tool getCheckShippingTool() {
    return Tool.builder()
        .name("check_shipping")
        .description("""
            Call this whenever the customer asks when an order will arrive or \
            whether it has shipped. Returns order_id, tracking_number, \
            estimated_delivery (DD/MM/YYYY) and status (S = shipped, \
            P = pending, D = delivered).""")
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
   * @param customerId the customer ID to look up
   * @return the customer's record, in the CRM's native format
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
   * @param orderId the order ID to look up
   * @return the order's record, in the order system's native format
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
   * @param orderId the order ID whose shipment to check
   * @return the shipment's record, in the carrier's native format
   * @throws IllegalArgumentException if no shipment exists for that order ID
   */
  static ShipmentRecord checkShipping(String orderId) {
    ShipmentRecord shipment = SHIPMENTS.get(orderId);
    if (shipment == null) {
      throw new IllegalArgumentException("No shipment found for order ID " + orderId);
    }
    return shipment;
  }

  // Serializes a tool's record the way the tool_result would carry it.
  static String toJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }
}
