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
 * Exercise 1.2.2
 * Implement task decomposition logic that breaks a broad research topic into
 * at least 5 distinct subtopics covering the full breadth of the subject.
 */
public class TaskDecompositionAgent {

  private static final int MIN_SUBTOPICS = 5;

  static final String SYSTEM_PROMPT = """
      You are the coordinator agent in a hub-and-spoke multi-agent research system. \
      You are the central hub: you own task decomposition, subagent selection, and \
      result aggregation. You never perform the research yourself and you are not a \
      subagent - your job is to orchestrate.

      Given a broad research topic, decompose it into at least five distinct \
      subtopics that together cover the full breadth of the subject. Narrow \
      decomposition is the most common failure here: do not settle for only the two \
      or three most obvious subtopics (e.g. solar and wind for renewable energy) - \
      that silently omits entire categories (e.g. geothermal, tidal, biomass, fusion) \
      from everything downstream.

      Respond with exactly one subtopic per line, with no numbering, bullets, \
      headers, or additional commentary.""";

  public static void main(String[] args) {

    AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    List<String> subtopics = decompose(client, "The future of renewable energy technologies");
    subtopics.forEach(subtopic -> System.out.println("- " + subtopic));
  }

  /**
   * Sends topic to Claude under the decomposition system prompt and returns the
   * subtopics it lists, one per response line. Throws if fewer than
   * MIN_SUBTOPICS came back, since narrow decomposition here silently limits
   * everything a coordinator does downstream (subagent selection, aggregation).
   *
   * @param client the Anthropic client to send the request through
   * @param topic  the broad research topic to decompose
   * @return at least MIN_SUBTOPICS distinct subtopics covering the topic
   */
  static List<String> decompose(AnthropicClient client, String topic) {
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
      return parseSubtopics(response);
    }

    throw new IllegalStateException("Unhandled stop_reason: " + stopReason);
  }

  /**
   * Extracts one subtopic per non-blank line of the response text and verifies
   * the decomposition met the minimum breadth requirement.
   *
   * @param response the assistant response whose stop_reason was end_turn
   * @return the trimmed, non-blank subtopic lines
   */
  private static List<String> parseSubtopics(Message response) {
    String text = response.content().stream()
        .flatMap(block -> block.text().stream())
        .map(TextBlock::text)
        .collect(Collectors.joining("\n"));

    List<String> subtopics = text.lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .toList();

    if (subtopics.size() < MIN_SUBTOPICS) {
      throw new IllegalStateException("Decomposition produced only " + subtopics.size()
          + " subtopic(s), fewer than the required minimum of " + MIN_SUBTOPICS
          + ": " + subtopics);
    }

    return subtopics;
  }
}
