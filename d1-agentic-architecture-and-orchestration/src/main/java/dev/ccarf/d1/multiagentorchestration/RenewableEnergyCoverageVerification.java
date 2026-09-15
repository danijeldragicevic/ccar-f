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
 * Exercise 1.2.6
 * Test with the topic "renewable energy technologies" and verify the final
 * output covers solar, wind, geothermal, tidal, biomass, and fusion. If
 * coverage is incomplete, the root-cause diagnosis this exercise is built to
 * teach is: check the coordinator's decomposition first, not the subagents -
 * a subtopic the coordinator never created can never be well-covered.
 */
public class RenewableEnergyCoverageVerification {

  private static final int MIN_SUBTOPICS = 5;
  private static final int MAX_REFINEMENT_ITERATIONS = 3;
  private static final String FIELD_SEPARATOR = " :: ";
  private static final String RENEWABLE_ENERGY_TOPIC =
      "The future of renewable energy technologies";
  private static final List<String> REQUIRED_ENERGY_TYPES =
      List.of("solar", "wind", "geothermal", "tidal", "biomass", "fusion");

  static final String DECOMPOSITION_SYSTEM_PROMPT = """
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

  static final String WEB_SEARCH_SUBAGENT_SYSTEM_PROMPT = """
      You are a web-search subagent in a hub-and-spoke multi-agent research system. \
      You have no memory of any other conversation and no access to the \
      coordinator's context beyond what is explicitly included in this prompt - \
      subagent isolation means nothing is inherited. Your only job is to research \
      the subtopic(s) assigned below and report factual findings; you do not \
      decompose topics, select other subagents, or aggregate results - that is the \
      coordinator's job.

      Report your findings as plain prose, with no meta-commentary about your role.""";

  static final String DOCUMENT_ANALYSIS_SUBAGENT_SYSTEM_PROMPT = """
      You are a document-analysis subagent in a hub-and-spoke multi-agent research \
      system. You have no memory of any other conversation and no access to the \
      coordinator's context beyond what is explicitly included in this prompt - \
      subagent isolation means nothing is inherited. Your only job is to analyze \
      the subtopic(s) assigned below, in light of any material handed to you, and \
      report your analysis; you do not decompose topics, select other subagents, \
      or aggregate results - that is the coordinator's job.

      Report your analysis as plain prose, with no meta-commentary about your role.""";

  static final String AGGREGATION_SYSTEM_PROMPT = """
      You are the coordinator agent in a hub-and-spoke multi-agent research system. \
      You are the central hub: you own task decomposition, subagent selection, and \
      result aggregation. You never perform the research yourself and you are not a \
      subagent - your job is to orchestrate.

      You have been given the full list of subtopics the research was decomposed \
      into, and the raw findings reported back by two subagents so far (possibly \
      across more than one round of delegation). Evaluate, for every single \
      subtopic in the list, whether the combined findings cover it well, partially, \
      or not at all - do not skip a subtopic just because neither subagent \
      mentioned it; an unmentioned subtopic is exactly the kind of gap this \
      assessment exists to catch.

      Respond with exactly one line per subtopic, in this exact format, with no \
      other commentary: <subtopic> :: <WELL_COVERED|PARTIALLY_COVERED|MISSING> :: \
      <one-sentence reason>""";

  static final String REPORT_SYNTHESIS_SYSTEM_PROMPT = """
      You are the coordinator agent in a hub-and-spoke multi-agent research system. \
      You are the central hub: you own task decomposition, subagent selection, and \
      result aggregation. You never perform the research yourself and you are not a \
      subagent - your job is to orchestrate.

      You have been given the full list of subtopics the research was decomposed \
      into and the cumulative findings reported back by two subagents, possibly \
      across more than one round of delegation. Synthesize this into the final \
      research report: one substantive, clearly labeled section per subtopic, \
      grounded only in the findings provided - do not invent facts no subagent \
      reported.""";

  public enum CoverageLevel {
    WELL_COVERED, PARTIALLY_COVERED, MISSING
  }

  public record CoverageEntry(String subtopic, CoverageLevel level, String reason) {
  }

  /**
   * Whether the final report and coverage assessment together demonstrate
   * complete coverage of the required renewable energy types, and - when they
   * don't - whether the gap traces back to decomposition (a required type was
   * never even made a subtopic) or to the report synthesis (the subtopic
   * existed and was well-covered, but the final report text omitted it).
   */
  public record VerificationResult(boolean coverageComplete,
      List<String> missingFromDecomposition, List<String> missingFromReport) {
  }

  public record ResearchOutcome(String report, List<CoverageEntry> coverage,
      VerificationResult verification) {
  }

  public static void main(String[] args) {

    AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    ResearchOutcome outcome = researchAndVerify(client, RENEWABLE_ENERGY_TOPIC);

    System.out.println("=== Final Research Report ===");
    System.out.println(outcome.report());

    System.out.println("\n=== Coverage Assessment ===");
    outcome.coverage().forEach(entry -> System.out.println(
        entry.subtopic() + " -> " + entry.level() + " (" + entry.reason() + ")"));

    System.out.println("\n=== Renewable Energy Type Verification ===");
    VerificationResult verification = outcome.verification();
    if (verification.coverageComplete()) {
      System.out.println("PASS: coverage evaluation shows 100% completeness, and the final "
          + "report substantively covers solar, wind, geothermal, tidal, biomass, and fusion.");
    } else {
      System.out.println("FAIL: coverage is not 100% complete.");
      if (!verification.missingFromDecomposition().isEmpty()) {
        System.out.println("DIAGNOSIS: the coordinator's decomposition never produced a "
            + "subtopic for " + verification.missingFromDecomposition() + " - the root cause "
            + "is decomposition breadth, not the subagents.");
      }
      if (!verification.missingFromReport().isEmpty()) {
        System.out.println("Missing from the final report text: "
            + verification.missingFromReport());
      }
    }
  }

  /**
   * Runs the full decompose -> delegate -> refine -> synthesize pipeline for
   * topic, then verifies the result against the six required renewable energy
   * types this exercise is testing for.
   *
   * @param client the Anthropic client to send requests through
   * @param topic  the broad research topic to research
   * @return the final report, its coverage assessment, and the verification result
   */
  static ResearchOutcome researchAndVerify(AnthropicClient client, String topic) {
    List<String> subtopics = decompose(client, topic);

    String webSearchFindings = invokeWebSearchSubagent(client, topic, subtopics, null);
    String documentAnalysisFindings =
        invokeDocumentAnalysisSubagent(client, topic, subtopics, null);
    List<CoverageEntry> coverage =
        aggregate(client, subtopics, webSearchFindings, documentAnalysisFindings);

    int iteration = 0;
    while (hasGaps(coverage) && iteration < MAX_REFINEMENT_ITERATIONS) {
      iteration++;
      List<String> gapSubtopics = gapSubtopics(coverage);

      String followUpWebSearchFindings =
          invokeWebSearchSubagent(client, topic, gapSubtopics, webSearchFindings);
      String followUpDocumentAnalysisFindings =
          invokeDocumentAnalysisSubagent(client, topic, gapSubtopics, documentAnalysisFindings);

      webSearchFindings = webSearchFindings + "\n" + followUpWebSearchFindings;
      documentAnalysisFindings = documentAnalysisFindings + "\n" + followUpDocumentAnalysisFindings;

      coverage = aggregate(client, subtopics, webSearchFindings, documentAnalysisFindings);
    }

    String report =
        synthesizeReport(client, topic, subtopics, webSearchFindings, documentAnalysisFindings);
    VerificationResult verification = verifyRenewableEnergyCoverage(subtopics, coverage, report);

    return new ResearchOutcome(report, coverage, verification);
  }

  /**
   * Checks the required renewable energy types against both the decomposition
   * (subtopics) and the final report text, so a gap can be traced to its real
   * source rather than blamed on whichever subagent happened to be invoked
   * last.
   *
   * @param subtopics the subtopics the coordinator decomposed the topic into
   * @param coverage  the final coverage assessment
   * @param report    the synthesized final report text
   * @return whether coverage is complete, and which required types are
   *         missing from decomposition and/or from the report text
   */
  static VerificationResult verifyRenewableEnergyCoverage(List<String> subtopics,
      List<CoverageEntry> coverage, String report) {
    boolean allSubtopicsWellCovered =
        coverage.stream().allMatch(entry -> entry.level() == CoverageLevel.WELL_COVERED);

    List<String> missingFromDecomposition = REQUIRED_ENERGY_TYPES.stream()
        .filter(type -> subtopics.stream()
            .noneMatch(subtopic -> subtopic.toLowerCase().contains(type)))
        .toList();

    List<String> missingFromReport = REQUIRED_ENERGY_TYPES.stream()
        .filter(type -> !report.toLowerCase().contains(type))
        .toList();

    boolean coverageComplete =
        allSubtopicsWellCovered && missingFromDecomposition.isEmpty() && missingFromReport.isEmpty();

    return new VerificationResult(coverageComplete, missingFromDecomposition, missingFromReport);
  }

  private static boolean hasGaps(List<CoverageEntry> coverage) {
    return coverage.stream().anyMatch(entry -> entry.level() != CoverageLevel.WELL_COVERED);
  }

  private static List<String> gapSubtopics(List<CoverageEntry> coverage) {
    return coverage.stream()
        .filter(entry -> entry.level() != CoverageLevel.WELL_COVERED)
        .map(CoverageEntry::subtopic)
        .toList();
  }

  private static List<String> decompose(AnthropicClient client, String topic) {
    Message response =
        sendSingleTurn(client, Config.modelMain(), DECOMPOSITION_SYSTEM_PROMPT, topic);
    List<String> subtopics = extractText(response).lines()
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

  private static String invokeWebSearchSubagent(AnthropicClient client, String researchGoal,
      List<String> assignedSubtopics, String priorFindings) {
    Message response = sendSingleTurn(client, Config.modelWorker(),
        WEB_SEARCH_SUBAGENT_SYSTEM_PROMPT,
        buildSubagentPrompt(researchGoal, assignedSubtopics, priorFindings));
    return extractText(response);
  }

  private static String invokeDocumentAnalysisSubagent(AnthropicClient client,
      String researchGoal, List<String> assignedSubtopics, String priorFindings) {
    Message response = sendSingleTurn(client, Config.modelWorker(),
        DOCUMENT_ANALYSIS_SUBAGENT_SYSTEM_PROMPT,
        buildSubagentPrompt(researchGoal, assignedSubtopics, priorFindings));
    return extractText(response);
  }

  private static String buildSubagentPrompt(String researchGoal, List<String> assignedSubtopics,
      String priorFindings) {
    String subtopicList = assignedSubtopics.stream()
        .map(subtopic -> "- " + subtopic)
        .collect(Collectors.joining("\n"));

    StringBuilder prompt = new StringBuilder()
        .append("Overall research goal: ").append(researchGoal).append("\n\n")
        .append("Your assigned subtopic(s) for this turn:\n").append(subtopicList).append("\n\n");

    if (priorFindings == null || priorFindings.isBlank()) {
      prompt.append("No prior findings - this is the first time you are being invoked.");
    } else {
      prompt.append("Your own findings from a prior turn (this is the only way you know what "
          + "you already reported, since you share no memory with it - do not repeat it, focus "
          + "only on the subtopic(s) assigned above):\n").append(priorFindings);
    }

    return prompt.toString();
  }

  private static List<CoverageEntry> aggregate(AnthropicClient client, List<String> subtopics,
      String webSearchFindings, String documentAnalysisFindings) {
    Message response = sendSingleTurn(client, Config.modelMain(), AGGREGATION_SYSTEM_PROMPT,
        buildFindingsPrompt(subtopics, webSearchFindings, documentAnalysisFindings));
    return parseCoverageEntries(response);
  }

  private static String synthesizeReport(AnthropicClient client, String topic,
      List<String> subtopics, String webSearchFindings, String documentAnalysisFindings) {
    Message response = sendSingleTurn(client, Config.modelMain(), REPORT_SYNTHESIS_SYSTEM_PROMPT,
        "Research topic: " + topic + "\n\n"
            + buildFindingsPrompt(subtopics, webSearchFindings, documentAnalysisFindings));
    return extractText(response);
  }

  private static String buildFindingsPrompt(List<String> subtopics, String webSearchFindings,
      String documentAnalysisFindings) {
    String subtopicList =
        subtopics.stream().map(subtopic -> "- " + subtopic).collect(Collectors.joining("\n"));

    return "Subtopics from decomposition:\n" + subtopicList
        + "\n\nWeb-search subagent findings so far:\n" + webSearchFindings
        + "\n\nDocument-analysis subagent findings so far:\n" + documentAnalysisFindings;
  }

  private static List<CoverageEntry> parseCoverageEntries(Message response) {
    return extractText(response).lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .map(RenewableEnergyCoverageVerification::parseCoverageEntry)
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

  private static Message sendSingleTurn(AnthropicClient client, String model,
      String systemPrompt, String userMessage) {
    MessageCreateParams params = MessageCreateParams.builder()
        .model(model)
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
