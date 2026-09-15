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
 * Exercise 1.2.5
 * Implement an iterative refinement loop: if the coordinator identifies
 * coverage gaps, re-delegate to subagents with targeted queries scoped to just
 * the gap subtopics, and re-invoke until coverage is sufficient or a maximum
 * iteration count is reached. This is what distinguishes a coordinator from a
 * simple single-shot dispatcher.
 */
public class IterativeRefinementLoop {

  private static final int MIN_SUBTOPICS = 5;
  private static final int MAX_REFINEMENT_ITERATIONS = 3;
  private static final String FIELD_SEPARATOR = " :: ";

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

  public enum CoverageLevel {
    WELL_COVERED, PARTIALLY_COVERED, MISSING
  }

  public record CoverageEntry(String subtopic, CoverageLevel level, String reason) {
  }

  public static void main(String[] args) {

    AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    List<CoverageEntry> finalCoverage =
        researchWithRefinement(client, "The future of renewable energy technologies");
    finalCoverage.forEach(entry -> System.out.println(
        entry.subtopic() + " -> " + entry.level() + " (" + entry.reason() + ")"));
  }

  /**
   * Runs the full decompose -> delegate -> aggregate pipeline, then refines:
   * while coverage gaps remain and the iteration cap hasn't been hit, re-invokes
   * both subagents with a targeted query scoped to just the gap subtopics (not
   * the whole topic again), folds the new findings into what's already known,
   * and re-aggregates. Unlike a stuck tool-use loop, exhausting the iteration
   * cap here is an expected outcome, not a bug - so this returns the
   * best-effort coverage reached rather than throwing.
   *
   * @param client the Anthropic client to send requests through
   * @param topic  the broad research topic to research
   * @return the coverage assessment once every subtopic is well-covered, or the
   *         best-effort assessment after MAX_REFINEMENT_ITERATIONS rounds
   */
  static List<CoverageEntry> researchWithRefinement(AnthropicClient client, String topic) {
    List<String> subtopics = decompose(client, topic);

    String webSearchFindings = 
        invokeWebSearchSubagent(client, topic, subtopics, null);
    String documentAnalysisFindings = 
        invokeDocumentAnalysisSubagent(client, topic, subtopics, null);
    List<CoverageEntry> coverage = 
        aggregate(client, subtopics, webSearchFindings, documentAnalysisFindings);

    int iteration = 0;
    while (hasGaps(coverage) && iteration < MAX_REFINEMENT_ITERATIONS) {
      iteration++;
      List<String> gapSubtopics = gapSubtopics(coverage);
      System.out.println(
          "[refinement " + iteration + "] re-delegating gap subtopics: " + gapSubtopics);

      String followUpWebSearchFindings =
          invokeWebSearchSubagent(client, topic, gapSubtopics, webSearchFindings);
      String followUpDocumentAnalysisFindings =
          invokeDocumentAnalysisSubagent(client, topic, gapSubtopics, documentAnalysisFindings);

      webSearchFindings = webSearchFindings + "\n" + followUpWebSearchFindings;
      documentAnalysisFindings = documentAnalysisFindings + "\n" + followUpDocumentAnalysisFindings;

      coverage = aggregate(client, subtopics, webSearchFindings, documentAnalysisFindings);
    }

    if (hasGaps(coverage)) {
      System.err.println("WARNING: coverage gaps remain after " + MAX_REFINEMENT_ITERATIONS
          + " refinement iteration(s) - returning best-effort coverage: " + coverage);
    }

    return coverage;
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

  /**
   * Sends topic to Claude under the decomposition system prompt and returns the
   * subtopics it lists, one per response line. Throws if fewer than
   * MIN_SUBTOPICS came back, since narrow decomposition here silently limits
   * everything downstream.
   *
   * @param client the Anthropic client to send the request through
   * @param topic  the broad research topic to decompose
   * @return at least MIN_SUBTOPICS distinct subtopics covering the topic
   */
  private static List<String> decompose(AnthropicClient client, String topic) {
    Message response = sendSingleTurn(client, Config.modelMain(), DECOMPOSITION_SYSTEM_PROMPT, topic);
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

  // Assembles the one prompt a subagent sees for this turn: its research goal,
  // the subtopic(s) assigned this round (the full list on the first call, just
  // the gap subtopics on a refinement call), and its own prior findings if any
  // - nothing is implicit or carried over automatically.
  private static String buildSubagentPrompt(String researchGoal, List<String> assignedSubtopics,
      String priorFindings) {
    String subtopicList =
        assignedSubtopics.stream().map(subtopic -> "- " + subtopic).collect(Collectors.joining("\n"));

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

  /**
   * Combines both subagents' findings so far against the full subtopic list and
   * asks the coordinator to classify coverage of every subtopic, so gaps a
   * subagent silently omitted are surfaced rather than lost.
   *
   * @param client                   the Anthropic client to send the request through
   * @param subtopics                the full list of subtopics from decomposition
   * @param webSearchFindings        the web-search subagent's findings so far
   * @param documentAnalysisFindings the document-analysis subagent's findings so far
   * @return one coverage entry per subtopic
   */
  private static List<CoverageEntry> aggregate(AnthropicClient client, List<String> subtopics,
      String webSearchFindings, String documentAnalysisFindings) {
    Message response = sendSingleTurn(client, Config.modelMain(), AGGREGATION_SYSTEM_PROMPT,
        buildAggregationPrompt(subtopics, webSearchFindings, documentAnalysisFindings));
    return parseCoverageEntries(response);
  }

  private static String buildAggregationPrompt(List<String> subtopics,
      String webSearchFindings, String documentAnalysisFindings) {
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
        .map(IterativeRefinementLoop::parseCoverageEntry)
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

  // Sends one isolated, non-tool turn and returns the response once
  // stop_reason is end_turn - every call in this pipeline (decompose, each
  // subagent, aggregate) shares this same shape.
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
