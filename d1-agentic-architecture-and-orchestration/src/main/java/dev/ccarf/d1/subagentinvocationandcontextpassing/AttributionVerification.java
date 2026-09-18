package dev.ccarf.d1.subagentinvocationandcontextpassing;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * Exercise 1.3.5
 * Verify that the synthesis subagent attributes every claim in its output to
 * a specific source. This is a check on the whole pipeline, not on the
 * synthesis prompt in isolation: if the synthesis subagent produces an
 * orphaned claim, the fix is to trace back to whether the metadata actually
 * reached it (Exercise 1.3.4) - blaming or tweaking the synthesis prompt
 * treats the symptom, not the cause.
 */
public class AttributionVerification {

  private static final String FIELD_SEPARATOR = " :: ";
  private static final Pattern CITED_CLAIM_PATTERN = Pattern.compile("^(.*)\\[(.*)\\]\\s*$");

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
      synthesize the structured findings handed to you below; you do not \
      search the web, read documents, or spawn other subagents.

      Every finding below carries both its content and its full metadata - \
      source URL or document name/page number, a confidence score, and which \
      subagent retrieved it.

      Respond with exactly one claim per line, in this exact format, with no \
      other commentary: <claim> [<citation>] - where <citation> is the \
      finding's source_url if it has one, or "<document_name>, p.<page_number>" \
      if it came from a document instead. Every single line must end with a \
      citation in square brackets - a claim with no citation is not \
      acceptable, since it can no longer be traced back to the finding it \
      came from.""";

  /**
   * A single finding reported by a research subagent - content and metadata
   * kept as separate fields (see {@link StructuredFindingFormat.Finding},
   * Exercise 1.3.3).
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

  /**
   * One claim from the synthesis subagent's report, paired with the citation
   * it gave for that claim - {@code citation} is null when the synthesis
   * subagent produced a claim with no trailing {@code [citation]} at all,
   * i.e. an orphaned claim.
   *
   * @param claim    the claim text, without its citation
   * @param citation the source cited for this claim, or null if the line had
   *                 no citation
   */
  public record AttributedClaim(String claim, String citation) {
  }

  public static void main(String[] args) {

    AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    String subtopic = "Solar power adoption trends";
    String report = research(client, subtopic);

    List<AttributedClaim> claims = parseAttributedClaims(report);
    claims.forEach(claim -> System.out.println(claim.claim() + "  [" + claim.citation() + "]"));

    verifyAllClaimsAttributed(claims);
    System.out.println("\nVerified: every claim is attributed to a specific source.");
  }

  /**
   * Runs the full pipeline: both research subagents, then the synthesis
   * subagent over their complete, metadata-intact findings.
   *
   * @param client   the Anthropic client to send requests through
   * @param subtopic the subtopic to research and synthesize
   * @return the synthesis subagent's report, one cited claim per line
   */
  static String research(AnthropicClient client, String subtopic) {
    List<Finding> findings = new ArrayList<>();
    findings.addAll(invokeWebSearchSubagent(client, subtopic));
    findings.addAll(invokeDocumentAnalysisSubagent(client, subtopic));

    return invokeSynthesisSubagent(client, subtopic, findings);
  }

  /**
   * Parses the synthesis subagent's report into one {@link AttributedClaim}
   * per line - a line with no trailing {@code [citation]} still produces a
   * claim, just with a null citation, so it can be surfaced as orphaned
   * rather than silently dropped.
   *
   * @param report the synthesis subagent's report
   * @return one AttributedClaim per non-blank report line
   */
  static List<AttributedClaim> parseAttributedClaims(String report) {
    return report.lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .map(AttributionVerification::parseAttributedClaim)
        .toList();
  }

  private static AttributedClaim parseAttributedClaim(String line) {
    Matcher matcher = CITED_CLAIM_PATTERN.matcher(line);
    if (matcher.matches()) {
      return new AttributedClaim(matcher.group(1).trim(), matcher.group(2).trim());
    }
    return new AttributedClaim(line, null);
  }

  /**
   * Picks out the claims with no citation at all - the exact failure mode
   * this exercise exists to catch.
   *
   * @param claims every claim parsed from a synthesis report
   * @return the claims with a null (missing) citation
   */
  static List<AttributedClaim> findOrphanedClaims(List<AttributedClaim> claims) {
    return claims.stream()
        .filter(claim -> claim.citation() == null || claim.citation().isBlank())
        .toList();
  }

  /**
   * Verifies every claim is attributed. An orphaned claim here means the
   * context-passing pipeline (Exercise 1.3.4) failed to get metadata to the
   * synthesis subagent for that finding - not a synthesis-prompt defect - so
   * the message points back at the pipeline, not at prompt wording.
   *
   * @param claims every claim parsed from a synthesis report
   * @throws IllegalStateException if any claim has no citation
   */
  static void verifyAllClaimsAttributed(List<AttributedClaim> claims) {
    List<AttributedClaim> orphaned = findOrphanedClaims(claims);
    if (!orphaned.isEmpty()) {
      throw new IllegalStateException("Found " + orphaned.size() + " orphaned claim(s) with no "
          + "citation - trace this back to whether metadata actually reached the synthesis "
          + "subagent (Exercise 1.3.4), not to the synthesis prompt: " + orphaned);
    }
  }

  static List<Finding> invokeWebSearchSubagent(AnthropicClient client, String subtopic) {
    Message response = sendSingleTurn(client, WEB_SEARCH_SUBAGENT_SYSTEM_PROMPT,
        "Your assigned subtopic: " + subtopic);
    return extractText(response).lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .map(AttributionVerification::parseWebSearchFinding)
        .toList();
  }

  static List<Finding> invokeDocumentAnalysisSubagent(AnthropicClient client, String subtopic) {
    Message response = sendSingleTurn(client, DOCUMENT_ANALYSIS_SUBAGENT_SYSTEM_PROMPT,
        "Your assigned subtopic: " + subtopic);
    return extractText(response).lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .map(AttributionVerification::parseDocumentAnalysisFinding)
        .toList();
  }

  static String invokeSynthesisSubagent(AnthropicClient client, String subtopic,
      List<Finding> findings) {
    Message response =
        sendSingleTurn(client, SYNTHESIS_SUBAGENT_SYSTEM_PROMPT, buildSynthesisPrompt(subtopic, findings));
    return extractText(response);
  }

  // Serializes the complete findings list into the synthesis prompt - every
  // field of every Finding is written out explicitly and labeled, so nothing
  // is stripped or folded away before the synthesis subagent sees it.
  private static String buildSynthesisPrompt(String subtopic, List<Finding> findings) {
    String findingsBlock = findings.stream()
        .map(AttributionVerification::formatFinding)
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
