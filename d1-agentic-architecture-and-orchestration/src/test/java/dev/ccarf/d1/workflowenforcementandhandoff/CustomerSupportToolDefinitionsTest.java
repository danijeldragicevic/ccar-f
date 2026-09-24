package dev.ccarf.d1.workflowenforcementandhandoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.ccarf.d1.workflowenforcementandhandoff.CustomerSupportToolDefinitions.CustomerLookup;
import dev.ccarf.d1.workflowenforcementandhandoff.CustomerSupportToolDefinitions.OrderDetails;
import dev.ccarf.d1.workflowenforcementandhandoff.CustomerSupportToolDefinitions.RefundConfirmation;
import org.junit.jupiter.api.Test;

class CustomerSupportToolDefinitionsTest {

  @Test
  void getCustomerDeclaresNameAndEmailWithNeitherRequired() {
    Tool getCustomer = CustomerSupportToolDefinitions.getGetCustomerTool();

    assertEquals("get_customer", getCustomer.name());
    assertTrue(getCustomer.description().isPresent() && !getCustomer.description().get().isBlank());
    Map<String, JsonValue> properties = properties(getCustomer);
    assertEquals(2, properties.size());
    assertTrue(properties.containsKey("name"));
    assertTrue(properties.containsKey("email"));
    assertTrue(getCustomer.inputSchema().required().orElse(List.of()).isEmpty());
  }

  @Test
  void lookupOrderDeclaresARequiredOrderId() {
    Tool lookupOrder = CustomerSupportToolDefinitions.getLookupOrderTool();

    assertEquals("lookup_order", lookupOrder.name());
    assertTrue(lookupOrder.description().isPresent() && !lookupOrder.description().get().isBlank());
    Map<String, JsonValue> properties = properties(lookupOrder);
    assertEquals(1, properties.size());
    assertTrue(properties.containsKey("order_id"));
    assertEquals(List.of("order_id"), lookupOrder.inputSchema().required().orElseThrow());
  }

  @Test
  void processRefundDeclaresARequiredCustomerIdAndNumericAmount() {
    Tool processRefund = CustomerSupportToolDefinitions.getProcessRefundTool();

    assertEquals("process_refund", processRefund.name());
    assertTrue(processRefund.description().isPresent() && !processRefund.description().get().isBlank());
    Map<String, JsonValue> properties = properties(processRefund);
    assertEquals(2, properties.size());
    assertTrue(properties.containsKey("customer_id"));
    assertEquals("number", properties.get("amount")
        .convert(new TypeReference<Map<String, Object>>() {})
        .get("type"));
    assertEquals(List.of("customer_id", "amount"), processRefund.inputSchema().required().orElseThrow());
  }

  @Test
  void processRefundDescriptionStatesTheGetCustomerPrerequisite() {
    Tool processRefund = CustomerSupportToolDefinitions.getProcessRefundTool();

    assertTrue(processRefund.description().orElseThrow().contains("get_customer"));
  }

  @Test
  void getCustomerReturnsCustomerIdAndVerificationStatusByEmailOrName() {
    CustomerLookup byEmail = CustomerSupportToolDefinitions.getCustomer(null, "JANE@example.com");
    CustomerLookup byName = CustomerSupportToolDefinitions.getCustomer("John Smith", null);

    assertEquals("CUST-1001", byEmail.customerId());
    assertTrue(byEmail.verified());
    assertEquals("CUST-1002", byName.customerId());
    assertFalse(byName.verified());
  }

  @Test
  void getCustomerThrowsWhenNoIdentifierIsGivenOrNoCustomerMatches() {
    assertThrows(IllegalArgumentException.class,
        () -> CustomerSupportToolDefinitions.getCustomer(null, null));
    assertThrows(IllegalArgumentException.class,
        () -> CustomerSupportToolDefinitions.getCustomer(null, "nobody@example.com"));
  }

  @Test
  void lookupOrderReturnsOrderDetails() {
    OrderDetails order = CustomerSupportToolDefinitions.lookupOrder("ORD-5001");

    assertEquals("CUST-1001", order.customerId());
    assertEquals(List.of("Wireless headphones"), order.items());
    assertEquals(89.99, order.amountPaid());
    assertEquals("delivered", order.status());
  }

  @Test
  void lookupOrderThrowsForAnUnknownOrderId() {
    assertThrows(IllegalArgumentException.class,
        () -> CustomerSupportToolDefinitions.lookupOrder("ORD-0000"));
  }

  @Test
  void processRefundReturnsAConfirmationForTheGivenCustomerAndAmount() {
    RefundConfirmation confirmation = CustomerSupportToolDefinitions.processRefund("CUST-1001", 89.99);

    assertEquals("CUST-1001", confirmation.customerId());
    assertEquals(89.99, confirmation.amount());
    assertEquals("processed", confirmation.status());
    assertFalse(confirmation.refundId().isBlank());
  }

  @Test
  void processRefundThrowsForANonPositiveAmount() {
    assertThrows(IllegalArgumentException.class,
        () -> CustomerSupportToolDefinitions.processRefund("CUST-1001", 0));
  }

  private static Map<String, JsonValue> properties(Tool tool) {
    return tool.inputSchema().properties().orElseThrow()._additionalProperties();
  }
}
