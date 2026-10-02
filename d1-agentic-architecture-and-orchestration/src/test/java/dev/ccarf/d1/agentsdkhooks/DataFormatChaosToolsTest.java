package dev.ccarf.d1.agentsdkhooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import org.junit.jupiter.api.Test;

class DataFormatChaosToolsTest {

  @Test
  void eachToolDeclaresOneRequiredIdInput() {
    assertRequiredSingleInput(DataFormatChaosTools.getGetCustomerTool(), "get_customer", "customer_id");
    assertRequiredSingleInput(DataFormatChaosTools.getLookupOrderTool(), "lookup_order", "order_id");
    assertRequiredSingleInput(DataFormatChaosTools.getCheckShippingTool(), "check_shipping", "order_id");
  }

  @Test
  void eachToolDescriptionStatesItsOwnOutputFormat() {
    assertTrue(DataFormatChaosTools.getGetCustomerTool().description().orElseThrow()
        .contains("Unix epoch seconds"));
    assertTrue(DataFormatChaosTools.getLookupOrderTool().description().orElseThrow()
        .contains("ISO 8601"));
    assertTrue(DataFormatChaosTools.getCheckShippingTool().description().orElseThrow()
        .contains("DD/MM/YYYY"));
  }

  @Test
  void toolAReturnsEpochSecondsAndANumericStatusCode() {
    assertEquals("{\"customer_id\":\"CUST-1001\",\"name\":\"Jane Doe\","
            + "\"created_at\":1709294400,\"status\":1}",
        DataFormatChaosTools.toJson(DataFormatChaosTools.getCustomer("CUST-1001")));
  }

  @Test
  void toolBReturnsAnIsoTimestampAndAnEnglishStatus() {
    assertEquals("{\"order_id\":\"ORD-5002\",\"customer_id\":\"CUST-1001\","
            + "\"ordered_at\":\"2026-03-03T14:05:00Z\",\"status\":\"processing\"}",
        DataFormatChaosTools.toJson(DataFormatChaosTools.lookupOrder("ORD-5002")));
  }

  @Test
  void toolCReturnsADayMonthYearDateAndASingleCharacterStatus() {
    assertEquals("{\"order_id\":\"ORD-5002\",\"tracking_number\":\"TRK-88232\","
            + "\"estimated_delivery\":\"12/03/2026\",\"status\":\"P\"}",
        DataFormatChaosTools.toJson(DataFormatChaosTools.checkShipping("ORD-5002")));
  }

  @Test
  void lookupsThrowForUnknownIds() {
    assertThrows(IllegalArgumentException.class,
        () -> DataFormatChaosTools.getCustomer("CUST-0000"));
    assertThrows(IllegalArgumentException.class,
        () -> DataFormatChaosTools.lookupOrder("ORD-0000"));
    assertThrows(IllegalArgumentException.class,
        () -> DataFormatChaosTools.checkShipping("ORD-0000"));
  }

  private static void assertRequiredSingleInput(Tool tool, String name, String input) {
    assertEquals(name, tool.name());
    Map<String, JsonValue> properties = tool.inputSchema().properties().orElseThrow()._additionalProperties();
    assertEquals(List.of(input), List.copyOf(properties.keySet()));
    assertEquals(List.of(input), tool.inputSchema().required().orElseThrow());
  }
}
