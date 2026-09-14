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
 * Exercise 1.2.4
 * Aggregate results from both subagents and evaluate coverage completeness -
 * this is where iterative refinement starts, since gaps detected here are what
 * trigger re-delegation to subagents.
 */
public class ResultAggregation {

  private static final String FIELD_SEPARATOR = " :: ";

  static final String SYSTEM_PROMPT = """
      You are the coordinator agent in a hub-and-spoke multi-agent research system. \
      You are the central hub: you own task decomposition, subagent selection, and \
      result aggregation. You never perform the research yourself and you are not a \
      subagent - your job is to orchestrate.

      You have been given the full list of subtopics the research was decomposed \
      into, and the raw findings reported back by two subagents. Evaluate, for \
      every single subtopic in the list, whether the combined findings cover it \
      well, partially, or not at all - do not skip a subtopic just because neither \
      subagent mentioned it; an unmentioned subtopic is exactly the kind of gap \
      this assessment exists to catch.

      Respond with exactly one line per subtopic, in this exact format, with no \
      other commentary: <subtopic> :: <WELL_COVERED|PARTIALLY_COVERED|MISSING> :: \
      <one-sentence reason>""";

  public enum CoverageLevel {
    WELL_COVERED, PARTIALLY_COVERED, MISSING
  }

  public record CoverageEntry(String subtopic, CoverageLevel level, String reason) {
  }

  public static void main(String[] args) {

    AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    List<String> subtopics =
            List.of("Solar", "Wind", "Geothermal", "Tidal", "Biomass", "Fusion");
    
    // Simulated findings from web-search sub-agent
    String webSearchFindings = "Solar panel efficiency has improved steadily year over year; "
            + "offshore wind capacity is the fastest-growing segment of renewable investment.";
    
    // Simulated findings from document-analysis sub-agent
    String documentAnalysisFindings = "Industry reports show solar and wind dominate current "
        + "renewable energy investment and policy attention.";

    List<CoverageEntry> coverage = aggregate(client, subtopics, webSearchFindings, documentAnalysisFindings);
    coverage.forEach(entry -> System.out.println(
            entry.subtopic() + " -> " + entry.level() + " (" + entry.reason() + ")"));
  }

  /**
   * Combines both subagents' findings against the full subtopic list and asks
   * the coordinator to classify coverage of every subtopic, so gaps a
   * subagent silently omitted are surfaced rather than lost.
   *
   * @param client                   the Anthropic client to send the request through
   * @param subtopics                the full list of subtopics from decomposition
   * @param webSearchFindings        the web-search subagent's reported findings
   * @param documentAnalysisFindings the document-analysis subagent's reported findings
   * @return one coverage entry per subtopic
   */
  static List<CoverageEntry> aggregate(AnthropicClient client, List<String> subtopics,
      String webSearchFindings, String documentAnalysisFindings) {
    MessageCreateParams params = MessageCreateParams.builder()
        .model(Config.modelMain())
        .maxTokens(Config.maxTokens())
        .system(SYSTEM_PROMPT)
        .messages(List.of(MessageParam.builder()
            .role(MessageParam.Role.USER)
            .content(buildAggregationPrompt(subtopics, webSearchFindings, documentAnalysisFindings))
            .build()))
        .build();

    Message response = client.messages().create(params);
    StopReason stopReason = response.stopReason().get();

    if (stopReason.equals(StopReason.END_TURN)) {
      return parseCoverageEntries(response);
    }

    throw new IllegalStateException("Unhandled stop_reason: " + stopReason);
  }

  // Bundles the subtopic list and both subagents' findings into the one prompt
  // the coordinator sees - nothing about prior turns is inherited here either.
  private static String buildAggregationPrompt(List<String> subtopics,
      String webSearchFindings, String documentAnalysisFindings) {
    String subtopicList =
        subtopics.stream().map(subtopic -> "- " + subtopic).collect(Collectors.joining("\n"));

    return "Subtopics from decomposition:\n" + subtopicList
        + "\n\nWeb-search subagent findings:\n" + webSearchFindings
        + "\n\nDocument-analysis subagent findings:\n" + documentAnalysisFindings;
  }

  // Parses one CoverageEntry per non-blank response line.
  private static List<CoverageEntry> parseCoverageEntries(Message response) {
    String text = response.content().stream()
        .flatMap(block -> block.text().stream())
        .map(TextBlock::text)
        .collect(Collectors.joining("\n"));

    return text.lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .map(ResultAggregation::parseCoverageEntry)
        .toList();
  }

  private static CoverageEntry parseCoverageEntry(String line) {
    String[] parts = line.split(FIELD_SEPARATOR, 3);
    if (parts.length != 3) {
      throw new IllegalArgumentException("Malformed coverage line (expected \"subtopic"
          + FIELD_SEPARATOR + "LEVEL" + FIELD_SEPARATOR + "reason\"): " + line);
    }

    CoverageLevel level;
    try {
      level = CoverageLevel.valueOf(parts[1].trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Unknown coverage level \"" + parts[1].trim() + "\" in line: " + line, e);
    }

    return new CoverageEntry(parts[0].trim(), level, parts[2].trim());
  }
}
