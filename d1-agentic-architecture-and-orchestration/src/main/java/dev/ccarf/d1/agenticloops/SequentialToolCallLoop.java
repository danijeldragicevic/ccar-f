package dev.ccarf.d1.agenticloops;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.type.TypeReference;

import dev.ccarf.common.Config;

/**
 * Exercise 1.1.5
 * Test with a prompt that requires multiple sequential tool calls (e.g., search for a value then calculate something with it) 
 * and verify the loop continues correctly through all iterations.
 */
public class SequentialToolCallLoop {

  private static final int MAX_ITERATIONS = 5;

  public static void main(String[] args) {

    AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    String finalResponse = runLoop(client,
        "Search for the current population of France. Once you have that number, "
            + "calculate 2% of it and tell me the result.");
    System.out.println(finalResponse);
  }

  /**
   * Sends userMessage to Claude and inspects the stop_reason of each response.
   * While it is tool_use, executes the requested tool(s), appends the assistant
   * turn and a user turn carrying the tool_result block(s) to the conversation,
   * and sends the request again. Once stop_reason is end_turn, extracts and
   * returns the text content of the final response.
   *
   * @param client      the Anthropic client to send requests through
   * @param userMessage the user turn to send
   * @return the text content of the final response once stop_reason is end_turn
   */
  static String runLoop(AnthropicClient client, String userMessage) {
    List<MessageParam> messages = new ArrayList<>();
    messages.add(MessageParam.builder()
        .role(MessageParam.Role.USER)
        .content(userMessage)
        .build());

    for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
      MessageCreateParams params = MessageCreateParams.builder()
          .model(Config.modelMain())
          .maxTokens(Config.maxTokens())
          .addTool(ToolDefinitions.getCalculator())
          .addTool(ToolDefinitions.getWebSearch())
          .messages(messages)
          .build();

      Message response = client.messages().create(params);
      StopReason stopReason = response.stopReason().get();
      System.out.println("[turn " + iteration + "] stop_reason: " + stopReason);

      if (!stopReason.equals(StopReason.TOOL_USE)) {
        return extractFinalResponse(response);
      }

      messages.add(response.toParam());
      messages.add(executeToolCalls(response));
    }

    throw new IllegalStateException(
        "Exceeded " + MAX_ITERATIONS + " tool-use iterations without reaching end_turn");
  }

  /**
   * Extracts the text content of the final response, joining every text block
   * in order.
   *
   * @param response the assistant response whose stop_reason was end_turn
   * @return the concatenated text of the response
   */
  private static String extractFinalResponse(Message response) {
    return response.content().stream()
        .flatMap(block -> block.text().stream())
        .map(TextBlock::text)
        .collect(Collectors.joining("\n"));
  }

  /**
   * Extracts every tool_use block from a response, runs the matching tool, and
   * packages the outputs into the single user message of tool_result blocks
   * that the API expects as the next turn.
   *
   * @param response the assistant response whose stop_reason was tool_use
   * @return a user MessageParam carrying one tool_result block per tool_use block
   */
  private static MessageParam executeToolCalls(Message response) {
    List<ContentBlockParam> toolResults = response.content().stream()
        .flatMap(block -> block.toolUse().stream())
        .map(toolUse -> ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
            .toolUseId(toolUse.id())
            .content(executeTool(toolUse))
            .build()))
        .toList();

    return MessageParam.builder()
        .role(MessageParam.Role.USER)
        .contentOfBlockParams(toolResults)
        .build();
  }

  /**
   * Runs the tool named by a tool_use block against its input and returns the
   * result as text, ready to embed in a tool_result block. Unlike the earlier
   * exercises, the web_search stub returns a plausible numeric value so that a
   * follow-up calculator call has something real to compute with.
   *
   * @param toolUse the tool_use block requesting execution
   * @return the tool's output
   */
  private static String executeTool(ToolUseBlock toolUse) {
    Map<String, JsonValue> input =
        toolUse._input().convert(new TypeReference<Map<String, JsonValue>>() {});

    return switch (toolUse.name()) {
      case "calculator" -> formatResult(evaluateExpression(
          input.get("expression").convert(String.class)));
      case "web_search" -> "Mock search results for \""
          + input.get("query").convert(String.class)
          + "\": reported value is 68170000 (stub - no real network call).";
      default -> throw new IllegalArgumentException("Unknown tool: " + toolUse.name());
    };
  }

  private static String formatResult(double value) {
    if (value == Math.rint(value) && !Double.isInfinite(value)) {
      return String.valueOf((long) value);
    }
    return String.valueOf(value);
  }

  private static double evaluateExpression(String expression) {
    return new ArithmeticParser(expression).parse();
  }

  // Minimal recursive-descent parser for +, -, *, /, parentheses and unary minus.
  private static final class ArithmeticParser {
    private final String expression;
    private int pos;

    ArithmeticParser(String expression) {
      this.expression = expression;
    }

    double parse() {
      double result = parseExpression();
      skipWhitespace();
      if (pos != expression.length()) {
        throw new IllegalArgumentException(
            "Unexpected character at position " + pos + " in: " + expression);
      }
      return result;
    }

    private double parseExpression() {
      double value = parseTerm();
      while (true) {
        skipWhitespace();
        if (consume('+')) {
          value += parseTerm();
        } else if (consume('-')) {
          value -= parseTerm();
        } else {
          return value;
        }
      }
    }

    private double parseTerm() {
      double value = parseFactor();
      while (true) {
        skipWhitespace();
        if (consume('*')) {
          value *= parseFactor();
        } else if (consume('/')) {
          value /= parseFactor();
        } else {
          return value;
        }
      }
    }

    private double parseFactor() {
      skipWhitespace();
      if (consume('-')) {
        return -parseFactor();
      }
      if (consume('+')) {
        return parseFactor();
      }
      if (consume('(')) {
        double value = parseExpression();
        skipWhitespace();
        if (!consume(')')) {
          throw new IllegalArgumentException("Missing closing parenthesis in: " + expression);
        }
        return value;
      }
      return parseNumber();
    }

    private double parseNumber() {
      int start = pos;
      while (pos < expression.length()
          && (Character.isDigit(expression.charAt(pos)) || expression.charAt(pos) == '.')) {
        pos++;
      }
      if (pos == start) {
        throw new IllegalArgumentException(
            "Expected a number at position " + pos + " in: " + expression);
      }
      return Double.parseDouble(expression.substring(start, pos));
    }

    private boolean consume(char c) {
      if (pos < expression.length() && expression.charAt(pos) == c) {
        pos++;
        return true;
      }
      return false;
    }

    private void skipWhitespace() {
      while (pos < expression.length() && Character.isWhitespace(expression.charAt(pos))) {
        pos++;
      }
    }
  }
}
