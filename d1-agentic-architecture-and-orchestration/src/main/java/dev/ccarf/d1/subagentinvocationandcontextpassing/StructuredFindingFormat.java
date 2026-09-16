package dev.ccarf.d1.subagentinvocationandcontextpassing;

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
 * Exercise 1.3.3
 * Design a structured output format that separates a finding's content from
 * its metadata: content fields (claim, analysis) capture what a subagent
 * found; metadata fields (source_url, document_name, page_number,
 * confidence, retrieved_by) capture where it came from and how reliable it
 * is. Keeping these separate is what lets a later synthesis subagent
 * attribute every claim back to a specific source, instead of receiving
 * pre-flattened prose it has no way to cite - the exact failure mode this
 * design exists to prevent. To see the format actually produced by an
 * agent (not just hand-built), the web-search subagent below is instructed
 * to report its findings in a parseable line format and its response is
 * parsed straight into {@link Finding} records.
 */
public class StructuredFindingFormat {

  private static final String FIELD_SEPARATOR = " :: ";

  static final String WEB_SEARCH_SUBAGENT_SYSTEM_PROMPT = """
      You are a web-search subagent in a hub-and-spoke multi-agent research \
      system. You have no memory of any other conversation and no access to \
      the coordinator's context beyond what is explicitly included in this \
      prompt - subagent isolation means nothing is inherited. Your only job \
      is to research the subtopic assigned below and report your findings.

      Respond with exactly one line per finding, in this exact format, with \
      no other commentary: <claim> :: <source_url> :: <confidence 0.0-1.0>""";

  /**
   * A single finding reported by a research subagent - content and metadata
   * kept as separate fields rather than folded into one string.
   */
  public record Finding(
      String claim,
      String analysis,
      String sourceUrl,
      String documentName,
      Integer pageNumber,
      double confidence,
      String retrievedBy) {
  }

  public static void main(String[] args) {

    // Finding webSearchFinding = new Finding(
    //     "Global solar capacity grew 32% year-over-year in 2024.",
    //     "This growth is attributed to falling panel costs rather than new subsidy programs.",
    //     "https://example.com/renewables/solar-2024",
    //     "Global Renewable Energy Outlook 2024",
    //     17,
    //     0.9,
    //     "web_search_agent");

    // Finding documentAnalysisFinding = new Finding(
    //     "Global solar capacity grew 32% year-over-year in 2024.",
    //     "The report attributes most of this growth to falling panel costs "
    //         + "rather than new subsidy programs.",
    //     null,
    //     "Global Renewable Energy Outlook 2024",
    //     17,
    //     0.75,
    //     "document_analysis_agent");

    // System.out.println("[hand-built Findings, illustrating the shape]");
    // System.out.println(webSearchFinding);
    // System.out.println(documentAnalysisFinding);

    AnthropicClient client = AnthropicOkHttpClient.fromEnv();
    List<Finding> findings = invokeWebSearchSubagent(client, "Solar power adoption trends");

    System.out.println("\n[Findings parsed from the web-search subagent's response]");
    findings.forEach(System.out::println);
  }

  /**
   * Invokes the web-search subagent for a subtopic and parses its response
   * straight into structured {@link Finding} records - this is the format
   * "in use," not just declared.
   *
   * @param client   the Anthropic client to send the request through
   * @param subtopic the subtopic to research
   * @return one Finding per line the subagent reported
   */
  static List<Finding> invokeWebSearchSubagent(AnthropicClient client, String subtopic) {
    MessageCreateParams params = MessageCreateParams.builder()
        .model(Config.modelWorker())
        .maxTokens(Config.maxTokens())
        .system(WEB_SEARCH_SUBAGENT_SYSTEM_PROMPT)
        .messages(List.of(MessageParam.builder()
            .role(MessageParam.Role.USER)
            .content("Your assigned subtopic: " + subtopic)
            .build()))
        .build();

    Message response = client.messages().create(params);
    StopReason stopReason = response.stopReason().get();

    if (stopReason.equals(StopReason.END_TURN)) {
      return parseFindings(response);
    }

    throw new IllegalStateException("Unhandled stop_reason: " + stopReason);
  }

  // Parses one Finding per non-blank response line.
  private static List<Finding> parseFindings(Message response) {
    String text = response.content().stream()
        .flatMap(block -> block.text().stream())
        .map(TextBlock::text)
        .collect(Collectors.joining("\n"));

    return text.lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .map(StructuredFindingFormat::parseFinding)
        .toList();
  }

  private static Finding parseFinding(String line) {
    String[] parts = line.split(FIELD_SEPARATOR, 3);
    if (parts.length != 3) {
      throw new IllegalArgumentException("Malformed finding line (expected \"claim"
          + FIELD_SEPARATOR + "source_url" + FIELD_SEPARATOR + "confidence\"): " + line);
    }

    double confidence;
    try {
      confidence = Double.parseDouble(parts[2].trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "Malformed confidence value \"" + parts[2].trim() + "\" in line: " + line, e);
    }

    return new Finding(parts[0].trim(), null, parts[1].trim(), null, null, confidence,
        "web_search_agent");
  }
}
