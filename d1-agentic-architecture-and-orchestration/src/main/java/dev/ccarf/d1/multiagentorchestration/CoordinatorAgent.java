package dev.ccarf.d1.multiagentorchestration;

import java.util.List;
import java.util.stream.Collectors;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;

import dev.ccarf.common.Config;

/**
 * Exercise 1.2.1
 * Create a coordinator agent that accepts a broad research topic as input.
 */
public class CoordinatorAgent {

  static final String SYSTEM_PROMPT = """
      You are the coordinator agent in a hub-and-spoke multi-agent research system. \
      You are the central hub: you own task decomposition, subagent selection, and \
      result aggregation. You never perform the research yourself and you are not a \
      subagent - your job is to orchestrate.

      Given a broad research topic, produce a structured research report on it, \
      organized into clearly labeled sections.""";

  public static void main(String[] args) {

    AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    String report = research(client, "The future of renewable energy technologies");
    System.out.println(report);
  }

  /**
   * Sends topic to Claude under the coordinator's system prompt and returns the
   * resulting structured research report. This exercise registers no tools and
   * spawns no subagents yet, so stop_reason is only ever expected to be
   * end_turn - anything else (tool_use, max_tokens, refusal, pause_turn,
   * stop_sequence) is treated as unhandled rather than silently returned as a
   * normal answer.
   *
   * @param client the Anthropic client to send the request through
   * @param topic  the broad research topic to report on
   * @return the structured research report text
   */
  static String research(AnthropicClient client, String topic) {
    MessageCreateParams params = MessageCreateParams.builder()
        .model(Config.modelMain())
        .maxTokens(Config.maxTokens())
        .system(SYSTEM_PROMPT)
        .messages(List.of(MessageParam.builder()
            .role(MessageParam.Role.USER)
            .content(topic)
            .build()))
        .build();

    Message response = client.messages().create(params);
    StopReason stopReason = response.stopReason().get();

    if (stopReason.equals(StopReason.END_TURN)) {
      return response.content().stream()
          .flatMap(block -> block.text().stream())
          .map(TextBlock::text)
          .collect(Collectors.joining("\n"));
    }

    throw new IllegalStateException("Unhandled stop_reason: " + stopReason);
  }
}
