package dev.ccarf.d1.agenticloops;

import java.util.ArrayList;
import java.util.List;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;

import dev.ccarf.common.Config;

/**
 * Exercise 1.2
 * Implement the agentic loop that sends requests to Claude
 * and inspects stop_reason after each response
 */
public class AgenticLoop {

  private static final int MAX_ITERATIONS = 5;

  public static void main(String[] args) {

    AnthropicClient client = AnthropicOkHttpClient.fromEnv();
    
    Message response = runLoop(client, "Say hello");
    response.content().stream()
        .flatMap(block -> block.text().stream())
        .forEach(textBlock -> System.out.println(textBlock.text()));
  }

  /**
   * Sends userMessage to Claude and inspects the stop_reason of each response.
   * While it is tool_use, sends the same request again.
   * Once it is anything else (e.g. end_turn), returns the final response.
   *
   * @param client      the Anthropic client to send requests through
   * @param userMessage the user turn to send
   * @return the final Message once stop_reason is no longer tool_use
   */
  static Message runLoop(AnthropicClient client, String userMessage) {
    List<MessageParam> messages = new ArrayList<>();
    messages.add(MessageParam.builder()
        .role(MessageParam.Role.USER)
        .content(userMessage)
        .build());

    for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
      MessageCreateParams params = MessageCreateParams.builder()
          .model(Config.modelMain())
          .maxTokens(Config.maxTokens())
          .messages(messages)
          .build();

      Message response = client.messages().create(params);
      StopReason stopReason = response.stopReason().get();
      System.out.println("[turn " + iteration + "] stop_reason: " + stopReason);

      if (!stopReason.equals(StopReason.TOOL_USE)) {
        return response;
      }
    }

    throw new IllegalStateException(
        "Exceeded " + MAX_ITERATIONS + " tool-use iterations without reaching end_turn");
  }
}
