package dev.ccarf.d1.subagentinvocationandcontextpassing;

import java.util.ArrayList;
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
 * Exercise 1.3.4
 * Pass the complete structured results from both the web-search and
 * document-analysis subagents to a synthesis subagent, with every metadata
 * field intact - source_url, document_name, page_number, confidence, and
 * retrieved_by all survive into the synthesis prompt, not just the claim or
 * analysis text. Stripping metadata before this handoff is the root cause of
 * a synthesis agent producing unsourced claims; keeping it is the fix.
 */
public class SynthesisWithPreservedMetadata {

  private static final String FIELD_SEPARATOR = " :: ";

  static final String WEB_SEARCH_SUBAGENT_SYSTEM_PROMPT = """
      You are a web-search subagent in a hub-and-spoke multi-agent research \
      system. You have no memory of any other conversation and no access to \
      the coordinator's context beyond what is explicitly included in this \
      prompt - subagent isolation means nothing is inherited. Your only job \
      is to research the subtopic assigned below and report your findings.

      Respond with exactly one line per finding, in this exact format, with \
      no other commentary: <claim> :: <source_url> :: <confidence 0.0-1.0>""";

  static final String DOCUMENT_ANALYSIS_SUBAGENT_SYSTEM_PROMPT = """
      You are a document-analysis subagent in a hub-and-spoke multi-agent \
      research system. You have no memory of any other conversation and no \
      access to the coordinator's context beyond what is explicitly included \
      in this prompt - subagent isolation means nothing is inherited. Your \
      only job is to analyze the subtopic assigned below and report your \
      findings, citing a document name and page number for each of them - \
      invent a plausible document name and page if you have no real one to \
      cite, since this exercise stubs real document access.

      Respond with exactly one line per finding, in this exact format, with \
      no other commentary: <analysis> :: <document_name> :: <page_number> \
      :: <confidence 0.0-1.0>""";

  static final String SYNTHESIS_SUBAGENT_SYSTEM_PROMPT = """
      You are a synthesis subagent in a hub-and-spoke multi-agent research \
      system. You have no memory of any other conversation and no access to \
      the coordinator's context beyond what is explicitly included in this \
      prompt - subagent isolation means nothing is inherited. Your job is to \
      synthesize the structured findings handed to you below into a short \
      report; you do not search the web, read documents, or spawn other \
      subagents.

      Every finding below carries both its content and its full metadata - \
      source URL or document name/page number, a confidence score, and which \
      subagent retrieved it. Use that metadata: every statement in your \
      report must be traceable back to a specific finding's source.""";

  /**
   * A single finding reported by a research subagent - content and metadata
   * kept as separate fields (see {@link StructuredFindingFormat.Finding},
   * Exercise 1.3.3) so provenance survives being passed on to the synthesis
   * subagent below.
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

    AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    String subtopic = "Solar power adoption trends";
    String report = research(client, subtopic);

    System.out.println(report);
  }

  /**
   * Invokes both research subagents for a subtopic, combines their findings
   * into one complete list, and passes that complete list - every metadata
   * field intact - to the synthesis subagent.
   *
   * @param client   the Anthropic client to send requests through
   * @param subtopic the subtopic to research and synthesize
   * @return the synthesis subagent's report
   */
  static String research(AnthropicClient client, String subtopic) {
    List<Finding> findings = new ArrayList<>();
    findings.addAll(invokeWebSearchSubagent(client, subtopic));
    findings.addAll(invokeDocumentAnalysisSubagent(client, subtopic));

    return invokeSynthesisSubagent(client, subtopic, findings);
  }

  /**
   * Invokes the web-search subagent for a subtopic and parses its response
   * into structured Findings.
   *
   * @param client   the Anthropic client to send the request through
   * @param subtopic the subtopic to research
   * @return one Finding per line the subagent reported
   */
  static List<Finding> invokeWebSearchSubagent(AnthropicClient client, String subtopic) {
    Message response = sendSingleTurn(client, WEB_SEARCH_SUBAGENT_SYSTEM_PROMPT,
        "Your assigned subtopic: " + subtopic);
    return extractText(response).lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .map(SynthesisWithPreservedMetadata::parseWebSearchFinding)
        .toList();
  }

  /**
   * Invokes the document-analysis subagent for a subtopic and parses its
   * response into structured Findings.
   *
   * @param client   the Anthropic client to send the request through
   * @param subtopic the subtopic to research
   * @return one Finding per line the subagent reported
   */
  static List<Finding> invokeDocumentAnalysisSubagent(AnthropicClient client, String subtopic) {
    Message response = sendSingleTurn(client, DOCUMENT_ANALYSIS_SUBAGENT_SYSTEM_PROMPT,
        "Your assigned subtopic: " + subtopic);
    return extractText(response).lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .map(SynthesisWithPreservedMetadata::parseDocumentAnalysisFinding)
        .toList();
  }

  /**
   * Invokes the synthesis subagent with the complete list of findings from
   * both research subagents - every metadata field serialized into the
   * prompt alongside the content, nothing stripped or summarized away.
   *
   * @param client   the Anthropic client to send the request through
   * @param subtopic the subtopic these findings were researched for
   * @param findings every finding gathered across both research subagents
   * @return the synthesis subagent's report
   */
  static String invokeSynthesisSubagent(AnthropicClient client, String subtopic, List<Finding> findings) {
    Message response =
        sendSingleTurn(client, SYNTHESIS_SUBAGENT_SYSTEM_PROMPT, buildSynthesisPrompt(subtopic, findings));
    return extractText(response);
  }

  // Serializes the complete findings list into the synthesis prompt - every
  // field of every Finding is written out explicitly and labeled, so nothing
  // is stripped or folded away before the synthesis subagent sees it.
  private static String buildSynthesisPrompt(String subtopic, List<Finding> findings) {
    String findingsBlock = findings.stream()
        .map(SynthesisWithPreservedMetadata::formatFinding)
        .collect(Collectors.joining("\n\n"));

    return "Subtopic: " + subtopic + "\n\n"
        + "Complete findings from both research subagents (" + findings.size()
        + " total, full metadata included):\n\n" + findingsBlock;
  }

  private static String formatFinding(Finding finding) {
    String content = finding.claim() != null ? finding.claim() : finding.analysis();
    return "- content: " + content
        + "\n  source_url: " + (finding.sourceUrl() != null ? finding.sourceUrl() : "none")
        + "\n  document_name: " + (finding.documentName() != null ? finding.documentName() : "none")
        + "\n  page_number: " + (finding.pageNumber() != null ? finding.pageNumber() : "none")
        + "\n  confidence: " + finding.confidence()
        + "\n  retrieved_by: " + finding.retrievedBy();
  }

  private static Finding parseWebSearchFinding(String line) {
    String[] parts = line.split(FIELD_SEPARATOR, 3);
    if (parts.length != 3) {
      throw new IllegalArgumentException("Malformed web-search finding line (expected \"claim"
          + FIELD_SEPARATOR + "source_url" + FIELD_SEPARATOR + "confidence\"): " + line);
    }

    return new Finding(parts[0].trim(), null, parts[1].trim(), null, null,
        parseConfidence(parts[2], line), "web_search_agent");
  }

  private static Finding parseDocumentAnalysisFinding(String line) {
    String[] parts = line.split(FIELD_SEPARATOR, 4);
    if (parts.length != 4) {
      throw new IllegalArgumentException("Malformed document-analysis finding line (expected "
          + "\"analysis" + FIELD_SEPARATOR + "document_name" + FIELD_SEPARATOR + "page_number"
          + FIELD_SEPARATOR + "confidence\"): " + line);
    }

    int pageNumber;
    try {
      pageNumber = Integer.parseInt(parts[2].trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "Malformed page_number value \"" + parts[2].trim() + "\" in line: " + line, e);
    }

    return new Finding(null, parts[0].trim(), null, parts[1].trim(), pageNumber,
        parseConfidence(parts[3], line), "document_analysis_agent");
  }

  private static double parseConfidence(String rawValue, String line) {
    try {
      return Double.parseDouble(rawValue.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "Malformed confidence value \"" + rawValue.trim() + "\" in line: " + line, e);
    }
  }

  // Sends one isolated, non-tool turn and returns the response once
  // stop_reason is end_turn.
  private static Message sendSingleTurn(AnthropicClient client, String systemPrompt,
      String userMessage) {
    MessageCreateParams params = MessageCreateParams.builder()
        .model(Config.modelWorker())
        .maxTokens(Config.maxTokens())
        .system(systemPrompt)
        .messages(List.of(MessageParam.builder()
            .role(MessageParam.Role.USER)
            .content(userMessage)
            .build()))
        .build();

    Message response = client.messages().create(params);
    StopReason stopReason = response.stopReason().get();

    if (stopReason.equals(StopReason.END_TURN)) {
      return response;
    }

    throw new IllegalStateException("Unhandled stop_reason: " + stopReason);
  }

  private static String extractText(Message response) {
    return response.content().stream()
        .flatMap(block -> block.text().stream())
        .map(TextBlock::text)
        .collect(Collectors.joining("\n"));
  }
}
