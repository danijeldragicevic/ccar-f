package dev.ccarf.d1;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import dev.ccarf.common.Config;

import java.util.List;
import java.util.Map;

/**
 * Exercise 1.1: 
 * Set up a Claude API client with two tools: 
 * - a calculator tool (accepts expression, returns result) and 
 * - a web search stub (accepts query, returns mock results)
 */
public final class ToolDefinitions {

    private static final String CALCULATOR_NAME = "calculator";
    private static final String CALCULATOR_DESCRIPTION =
            "Evaluates a basic arithmetic expression and returns the numeric result.";

    private static final String WEB_SEARCH_NAME = "web_search";
    private static final String WEB_SEARCH_DESCRIPTION =
            "Searches the web for a query and returns mock results (stub - no real network call).";

    public static void main(String[] args) {
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();

        Tool calculator = calculatorTool();
        Tool webSearch = webSearchTool();

        MessageCreateParams params = MessageCreateParams.builder()
                .model(Config.modelMain())
                .maxTokens(Config.maxTokens())
                .addTool(calculator)
                .addTool(webSearch)
                .addUserMessage("What tools do you have available?")
                .build();

        System.out.println("Tools registered on the request: "
                + params.tools().orElseThrow().size());
        System.out.println("- " + CALCULATOR_NAME + ": " + CALCULATOR_DESCRIPTION);
        System.out.println("- " + WEB_SEARCH_NAME + ": " + WEB_SEARCH_DESCRIPTION);

        System.out.println("Client ready for model " + Config.modelMain()
                + " (" + client.getClass().getSimpleName() + "), no request sent yet.");
    }

    private static Tool calculatorTool() {
        return Tool.builder()
                .name(CALCULATOR_NAME)
                .description(CALCULATOR_DESCRIPTION)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("expression", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "The arithmetic expression to evaluate, e.g. '12 * (3 + 4)'")))
                                .build())
                        .required(List.of("expression"))
                        .build())
                .build();
    }

    private static Tool webSearchTool() {
        return Tool.builder()
                .name(WEB_SEARCH_NAME)
                .description(WEB_SEARCH_DESCRIPTION)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("query", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "The search query.")))
                                .build())
                        .required(List.of("query"))
                        .build())
                .build();
    }
}
