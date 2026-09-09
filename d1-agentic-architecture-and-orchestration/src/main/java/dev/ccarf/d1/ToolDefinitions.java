package dev.ccarf.d1;

import java.util.Map;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;

import dev.ccarf.common.Config;

/**
 * Exercise 1.1
 * Set up a Claude API client with two tools: 
 * - a calculator tool (accepts expression, returns result) and 
 * - a web search stub (accepts query, returns mock results)
 */
public class ToolDefinitions {

  public static void main(String[] args) {

    AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    Tool calculator = getCalculator();
    Tool webSearch = getWebSearch();

    MessageCreateParams params = getParams(calculator, webSearch);

    System.out.println("Tools registered on the request: "
        + params.tools().orElseThrow().size());
    System.out.println("- " + calculator.name() + ": " + calculator.description());
    System.out.println("- " + webSearch.name() + ": " + webSearch.description());
    System.out.println("Client ready for model " + Config.modelMain()
        + " (" + client.getClass().getSimpleName() + "), no request sent yet.");
  }
  
  // Helper methods to create the tools and the message parameters
  private static MessageCreateParams getParams(Tool calculator, Tool webSearch) {
    return MessageCreateParams.builder()
        .model(Config.modelMain())
        .maxTokens(Config.maxTokens())
        .addTool(calculator)
        .addTool(webSearch)
        .addUserMessage("What tools do you have available?")
        .build();
  }

  static Tool getWebSearch() {
    Tool webSearch = Tool.builder()
        .name("web_search")
        .description("Searches the web for a query and returns mock results (stub - no real network call).")
        .inputSchema(Tool.InputSchema.builder()
            .properties(Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("query", com.anthropic.core.JsonValue.from(Map.of(
                    "type", "string",
                    "description", "The search query to look up on the web.")))
                .build())
            .build())
        .build();
    return webSearch;
  }

  static Tool getCalculator() {
    Tool calculator = Tool.builder()
        .name("calculator")
        .description("Evaluates a basic arithmetic expression and returns the numeric result.")
        .inputSchema(Tool.InputSchema.builder()
            .properties(Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("expression", com.anthropic.core.JsonValue.from(Map.of(
                    "type", "string",
                    "description", "The arithmetic expression to evaluate, e.g. '12 * (3 + 4)'")))
                .build())
            .build())
        .build();
    return calculator;
  }
}
