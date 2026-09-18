  package dev.ccarf.d1.subagentinvocationandcontextpassing;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.type.TypeReference;

import dev.ccarf.common.Config;

/**
 * Exercise 1.3.6
 * Refactor the coordinator to spawn both research subagents in parallel: it
 * must emit two "task" tool_use blocks in a single response - one per
 * subagent - rather than spawning one, waiting on it, then spawning the
 * other. Sequential spawning of independent subagents wastes wall-clock
 * time for no benefit; this dispatches both task calls concurrently and
 * waits for both to complete before handing the combined, metadata-intact
 * findings to the synthesis subagent.
 */
public class ParallelSubagentDispatch {

  private static final int EXPECTED_PARALLEL_TASK_CALLS = 2;
  private static final String FIELD_SEPARATOR = " :: ";

  static final String COORDINATOR_SYSTEM_PROMPT = """
      You are the coordinator agent in a hub-and-spoke multi-agent research \
      system. You are the central hub: you own subagent selection and \
      dispatch. You never perform research yourself and you are not a \
      subagent - your job is to orchestrate.

      You have access to a "task" tool that spawns a named subagent \
      (web_search or document_analysis) with a self-contained prompt you \
      write yourself - the subagent has no memory of this conversation, so \
      state its full assignment in that prompt.

      When a subtopic needs both a web search and a document analysis, they \
      are independent of each other - neither depends on the other's \
      output - so call the task tool twice, once per subagent_type, in this \
      same response. Do not call it once, wait for a result, and then call \
      it again in a later turn: that is sequential dispatch, and it wastes \
      time on work that could have run at the same time.

      Write each subagent's prompt as a research question or topic to report \
      findings on, not as an action to carry out - the subagent has no tools \
      of its own (it cannot browse, fetch pages, or open files) and will \
      always answer directly with structured findings.""";

  static final String WEB_SEARCH_SUBAGENT_SYSTEM_PROMPT = """
      You are a web-search subagent in a hub-and-spoke multi-agent research \
      system. You have no memory of any other conversation and no access to \
      the coordinator's context beyond what is explicitly included in this \
      prompt - subagent isolation means nothing is inherited. You have no \
      tools of your own - you cannot browse the web, fetch a page, or take \
      any action - so no matter how the assignment below is phrased, answer \
      it directly from what you already know, real or your best plausible \
      estimate for this exercise. Never respond with a plan, an action, or \
      an offer to look something up - only with your findings.

      Respond with exactly one line per finding, in this exact format, with \
      no other commentary: <claim> :: <source_url> :: <confidence 0.0-1.0>""";

  static final String DOCUMENT_ANALYSIS_SUBAGENT_SYSTEM_PROMPT = """
      You are a document-analysis subagent in a hub-and-spoke multi-agent \
      research system. You have no memory of any other conversation and no \
      access to the coordinator's context beyond what is explicitly included \
      in this prompt - subagent isolation means nothing is inherited. You \
      have no tools of your own - you cannot open a file or fetch a document \
      - so no matter how the assignment below is phrased, answer it directly, \
      inventing a plausible document name and page number to cite since this \
      exercise stubs real document access. Never respond with a plan, an \
      action, or an offer to look something up - only with your findings.

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

  public static void main(String[] args) {

    AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    String subtopic = "Solar power adoption trends";
    String report = research(client, subtopic);

    System.out.println("\n" + report);
  }

  /**
   * Runs the full pipeline: the coordinator spawns both research subagents
   * in parallel via a single response carrying two "task" tool_use blocks,
   * both are dispatched concurrently and awaited, and their complete,
   * metadata-intact findings are handed to the synthesis subagent.
   *
   * @param client   the Anthropic client to send requests through
   * @param subtopic the subtopic to research and synthesize
   * @return the synthesis subagent's report
   */
  static String research(AnthropicClient client, String subtopic) {
    List<ToolUseBlock> taskCalls = dispatchCoordinator(client, subtopic);
    List<Finding> findings = dispatchSubagentsInParallel(client, taskCalls);

    return invokeSynthesisSubagent(client, subtopic, findings);
  }

  /**
   * Sends the coordinator a request with the "task" tool registered and
   * verifies it spawned both subagents in one response - not one now and
   * one in a follow-up turn.
   *
   * @param client   the Anthropic client to send the request through
   * @param subtopic the subtopic to research
   * @return the "task" tool_use blocks from the coordinator's single response
   * @throws IllegalStateException if the coordinator did not respond with
   *                                tool_use, or spawned fewer than both
   *                                subagents in this one response
   */
  static List<ToolUseBlock> dispatchCoordinator(AnthropicClient client, String subtopic) {
    MessageCreateParams params = MessageCreateParams.builder()
        .model(Config.modelMain())
        .maxTokens(Config.maxTokens())
        .system(COORDINATOR_SYSTEM_PROMPT)
        .addTool(getTaskTool())
        .messages(List.of(MessageParam.builder()
            .role(MessageParam.Role.USER)
            .content("Research the following subtopic using both web search and "
                + "document analysis: " + subtopic)
            .build()))
        .build();

    Message response = client.messages().create(params);
    StopReason stopReason = response.stopReason().get();

    if (!stopReason.equals(StopReason.TOOL_USE)) {
      throw new IllegalStateException(
          "Expected the coordinator to respond with tool_use, got: " + stopReason);
    }

    List<ToolUseBlock> taskCalls = response.content().stream()
        .flatMap(block -> block.toolUse().stream())
        .filter(toolUse -> toolUse.name().equals("task"))
        .toList();

    if (taskCalls.size() < EXPECTED_PARALLEL_TASK_CALLS) {
      throw new IllegalStateException("Expected the coordinator to spawn both subagents in "
          + "parallel (" + EXPECTED_PARALLEL_TASK_CALLS + " task tool_use blocks in one "
          + "response), but got only " + taskCalls.size() + " - sequential spawning defeats "
          + "the point of parallel dispatch.");
    }

    return taskCalls;
  }

  /**
   * Dispatches every task call concurrently and waits for all of them to
   * complete before returning - this is the actual parallel execution, not
   * just the coordinator's intent to parallelize.
   *
   * @param client    the Anthropic client to send requests through
   * @param taskCalls the "task" tool_use blocks to dispatch
   * @return every finding gathered across all dispatched subagents
   */
  static List<Finding> dispatchSubagentsInParallel(AnthropicClient client, List<ToolUseBlock> taskCalls) {
    List<CompletableFuture<List<Finding>>> pending = taskCalls.stream()
        .map(taskCall -> CompletableFuture.supplyAsync(() -> {
          long startedAt = System.currentTimeMillis();
          List<Finding> findings = dispatchOne(client, taskCall);
          System.out.println("[parallel dispatch] " + taskCall._input()
              .convert(new TypeReference<Map<String, JsonValue>>() {}).get("subagent_type")
              + " finished in " + (System.currentTimeMillis() - startedAt) + "ms");
          return findings;
        }))
        .toList();

    // Waiting on allOf blocks until every subagent has finished - this is
    // the coordinator "waiting for both to complete before proceeding".
    CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();

    return pending.stream().flatMap(future -> future.join().stream()).toList();
  }

  static List<Finding> dispatchOne(AnthropicClient client, ToolUseBlock taskCall) {
    Map<String, JsonValue> input =
        taskCall._input().convert(new TypeReference<Map<String, JsonValue>>() {});
    String subagentType = input.get("subagent_type").convert(String.class);
    String prompt = input.get("prompt").convert(String.class);

    return switch (subagentType) {
      case "web_search" -> invokeWebSearchSubagent(client, prompt);
      case "document_analysis" -> invokeDocumentAnalysisSubagent(client, prompt);
      default -> throw new IllegalArgumentException("Unknown subagent_type: " + subagentType);
    };
  }

  /**
   * The "task" tool definition - see {@link TaskToolCoordinatorAgent},
   * Exercise 1.3.1, for the full design note on why this stands in for an
   * Agent SDK coordinator's {@code allowedTools} containing {@code "Task"}.
   *
   * @return the task tool definition
   */
  static Tool getTaskTool() {
    return Tool.builder()
        .name("task")
        .description("""
            Call this whenever a subtask should be delegated to a specialized \
            subagent rather than answered directly yourself - e.g. researching \
            a subtopic on the web, or analyzing a specific document. Spawns the \
            named subagent with the prompt you provide and returns its \
            findings. The subagent has no memory of this conversation, so the \
            prompt must be a complete, self-contained assignment. When you need \
            more than one independent subagent, call this tool once per \
            subagent in the same response so they run in parallel.""")
        .inputSchema(Tool.InputSchema.builder()
            .properties(Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("subagent_type", JsonValue.from(Map.of(
                    "type", "string",
                    "enum", List.of("web_search", "document_analysis"),
                    "description", "Which subagent to spawn for this task.")))
                .putAdditionalProperty("prompt", JsonValue.from(Map.of(
                    "type", "string",
                    "description", "The complete, self-contained assignment for the subagent.")))
                .build())
            .build())
        .build();
  }

  static List<Finding> invokeWebSearchSubagent(AnthropicClient client, String assignment) {
    Message response = sendSingleTurn(client, Config.modelWorker(),
        WEB_SEARCH_SUBAGENT_SYSTEM_PROMPT, assignment);
    return extractText(response).lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .map(ParallelSubagentDispatch::parseWebSearchFinding)
        .toList();
  }

  static List<Finding> invokeDocumentAnalysisSubagent(AnthropicClient client, String assignment) {
    Message response = sendSingleTurn(client, Config.modelWorker(),
        DOCUMENT_ANALYSIS_SUBAGENT_SYSTEM_PROMPT, assignment);
    return extractText(response).lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .map(ParallelSubagentDispatch::parseDocumentAnalysisFinding)
        .toList();
  }

  static String invokeSynthesisSubagent(AnthropicClient client, String subtopic,
      List<Finding> findings) {
    Message response = sendSingleTurn(client, Config.modelWorker(),
        SYNTHESIS_SUBAGENT_SYSTEM_PROMPT, buildSynthesisPrompt(subtopic, findings));
    return extractText(response);
  }

  private static String buildSynthesisPrompt(String subtopic, List<Finding> findings) {
    String findingsBlock = findings.stream()
        .map(ParallelSubagentDispatch::formatFinding)
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
