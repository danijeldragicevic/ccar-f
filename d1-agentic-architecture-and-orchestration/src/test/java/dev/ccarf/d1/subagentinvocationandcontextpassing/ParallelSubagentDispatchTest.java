package dev.ccarf.d1.subagentinvocationandcontextpassing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.ClientOptions;
import com.anthropic.core.JsonValue;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCountTokensParams;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageTokensCount;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.RefusalStopDetails;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.messages.BatchService;
import dev.ccarf.common.Config;
import dev.ccarf.d1.subagentinvocationandcontextpassing.ParallelSubagentDispatch.Finding;
import org.junit.jupiter.api.Test;

class ParallelSubagentDispatchTest {

  @Test
  void dispatchCoordinatorSendsCorrectRequestAndReturnsBothTaskCalls() {
    StubMessageService messageService = new StubMessageService(messageWithToolUse(
        toolUseBlock("call_1", "task", Map.of("subagent_type", "web_search", "prompt", "Research X")),
        toolUseBlock("call_2", "task", Map.of("subagent_type", "document_analysis", "prompt", "Analyze X"))));
    AnthropicClient client = new StubAnthropicClient(messageService);

    List<ToolUseBlock> taskCalls =
        ParallelSubagentDispatch.dispatchCoordinator(client, "Solar power adoption trends");

    assertEquals(2, taskCalls.size());
    MessageCreateParams sentParams = messageService.requests.get(0);
    assertEquals(Config.modelMain(), sentParams.model().toString());
    assertEquals(ParallelSubagentDispatch.COORDINATOR_SYSTEM_PROMPT,
        sentParams.system().orElseThrow().string().orElseThrow());
    assertTrue(sentParams.messages().get(0).content().string().orElseThrow()
        .contains("Solar power adoption trends"));
  }

  @Test
  void dispatchCoordinatorThrowsWhenStopReasonIsNotToolUse() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.END_TURN, "I'll get started."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalStateException.class,
        () -> ParallelSubagentDispatch.dispatchCoordinator(client, "Solar power adoption trends"));
  }

  @Test
  void dispatchCoordinatorThrowsWhenOnlyOneTaskCallIsSpawned() {
    StubMessageService messageService = new StubMessageService(messageWithToolUse(
        toolUseBlock("call_1", "task", Map.of("subagent_type", "web_search", "prompt", "Research X"))));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalStateException.class,
        () -> ParallelSubagentDispatch.dispatchCoordinator(client, "Solar power adoption trends"));
  }

  @Test
  void dispatchOneRoutesAWebSearchTaskCallToTheWebSearchSubagent() {
    StubMessageService messageService = new StubMessageService(messageWithText(StopReason.END_TURN,
        "Solar capacity grew 32%. :: https://example.com/solar :: 0.8"));
    AnthropicClient client = new StubAnthropicClient(messageService);
    ToolUseBlock taskCall =
        toolUseBlock("call_1", "task", Map.of("subagent_type", "web_search", "prompt", "Research solar"));

    List<Finding> findings = ParallelSubagentDispatch.dispatchOne(client, taskCall);

    assertEquals(1, findings.size());
    assertEquals("web_search_agent", findings.get(0).retrievedBy());
    assertEquals(ParallelSubagentDispatch.WEB_SEARCH_SUBAGENT_SYSTEM_PROMPT,
        messageService.requests.get(0).system().orElseThrow().string().orElseThrow());
  }

  @Test
  void dispatchOneRoutesADocumentAnalysisTaskCallToTheDocumentAnalysisSubagent() {
    StubMessageService messageService = new StubMessageService(messageWithText(StopReason.END_TURN,
        "Cost declines drove adoption. :: Solar Outlook 2024 :: 12 :: 0.7"));
    AnthropicClient client = new StubAnthropicClient(messageService);
    ToolUseBlock taskCall = toolUseBlock("call_1", "task",
        Map.of("subagent_type", "document_analysis", "prompt", "Analyze solar report"));

    List<Finding> findings = ParallelSubagentDispatch.dispatchOne(client, taskCall);

    assertEquals(1, findings.size());
    assertEquals("document_analysis_agent", findings.get(0).retrievedBy());
    assertEquals(ParallelSubagentDispatch.DOCUMENT_ANALYSIS_SUBAGENT_SYSTEM_PROMPT,
        messageService.requests.get(0).system().orElseThrow().string().orElseThrow());
  }

  @Test
  void dispatchOneThrowsForAnUnknownSubagentType() {
    ToolUseBlock taskCall =
        toolUseBlock("call_1", "task", Map.of("subagent_type", "unknown", "prompt", "Do something"));

    assertThrows(IllegalArgumentException.class,
        () -> ParallelSubagentDispatch.dispatchOne(null, taskCall));
  }

  @Test
  void dispatchSubagentsInParallelCombinesFindingsFromBothSubagentTypes() {
    // Routes by system prompt rather than call order, since the two task
    // calls are genuinely dispatched on separate threads and the order in
    // which they reach the stub is not guaranteed.
    RoutingMessageService messageService = new RoutingMessageService(
        messageWithText(StopReason.END_TURN, "Solar capacity grew 32%. :: https://example.com/solar :: 0.8"),
        messageWithText(StopReason.END_TURN, "Cost declines drove adoption. :: Solar Outlook 2024 :: 12 :: 0.7"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    List<ToolUseBlock> taskCalls = List.of(
        toolUseBlock("call_1", "task", Map.of("subagent_type", "web_search", "prompt", "Research solar")),
        toolUseBlock("call_2", "task",
            Map.of("subagent_type", "document_analysis", "prompt", "Analyze solar report")));

    List<Finding> findings = ParallelSubagentDispatch.dispatchSubagentsInParallel(client, taskCalls);

    assertEquals(2, findings.size());
    assertTrue(findings.stream().anyMatch(finding -> finding.retrievedBy().equals("web_search_agent")
        && finding.claim().equals("Solar capacity grew 32%.")));
    assertTrue(findings.stream().anyMatch(finding -> finding.retrievedBy().equals("document_analysis_agent")
        && finding.analysis().equals("Cost declines drove adoption.")));
    assertEquals(2, messageService.requests.size());
  }

  @Test
  void dispatchSubagentsInParallelWrapsAFailedSubagentInACompletionException() {
    ToolUseBlock taskCall =
        toolUseBlock("call_1", "task", Map.of("subagent_type", "unknown", "prompt", "Do something"));

    CompletionException exception = assertThrows(CompletionException.class, () -> ParallelSubagentDispatch
        .dispatchSubagentsInParallel(new StubAnthropicClient(new StubMessageService()), List.of(taskCall)));
    assertInstanceOf(IllegalArgumentException.class, exception.getCause());
  }

  @Test
  void invokeSynthesisSubagentPassesEveryMetadataFieldForEveryFinding() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.END_TURN, "Final synthesized report."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    Finding webSearchFinding = new Finding("Claim A.", null, "https://a.example.com", null, null, 0.8,
        "web_search_agent");
    Finding documentAnalysisFinding =
        new Finding(null, "Analysis B.", null, "Doc Name", 5, 0.6, "document_analysis_agent");

    String report = ParallelSubagentDispatch.invokeSynthesisSubagent(client, "Solar adoption",
        List.of(webSearchFinding, documentAnalysisFinding));

    assertEquals("Final synthesized report.", report);
    MessageCreateParams sentParams = messageService.requests.get(0);
    assertEquals(Config.modelWorker(), sentParams.model().toString());
    assertEquals(ParallelSubagentDispatch.SYNTHESIS_SUBAGENT_SYSTEM_PROMPT,
        sentParams.system().orElseThrow().string().orElseThrow());

    String synthesisPrompt = sentParams.messages().get(0).content().string().orElseThrow();
    assertTrue(synthesisPrompt.contains("Claim A."));
    assertTrue(synthesisPrompt.contains("source_url: https://a.example.com"));
    assertTrue(synthesisPrompt.contains("document_name: none"));
    assertTrue(synthesisPrompt.contains("retrieved_by: web_search_agent"));
    assertTrue(synthesisPrompt.contains("Analysis B."));
    assertTrue(synthesisPrompt.contains("source_url: none"));
    assertTrue(synthesisPrompt.contains("document_name: Doc Name"));
    assertTrue(synthesisPrompt.contains("page_number: 5"));
    assertTrue(synthesisPrompt.contains("retrieved_by: document_analysis_agent"));
  }

  @Test
  void throwsForUnhandledStopReasonInWebSearchSubagent() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.MAX_TOKENS, "truncated..."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalStateException.class,
        () -> ParallelSubagentDispatch.invokeWebSearchSubagent(client, "Research solar"));
  }

  @Test
  void throwsForUnhandledStopReasonInDocumentAnalysisSubagent() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.MAX_TOKENS, "truncated..."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalStateException.class,
        () -> ParallelSubagentDispatch.invokeDocumentAnalysisSubagent(client, "Analyze solar report"));
  }

  @Test
  void throwsForAMalformedWebSearchFindingLine() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.END_TURN, "Claim with no separators at all"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalArgumentException.class,
        () -> ParallelSubagentDispatch.invokeWebSearchSubagent(client, "Research solar"));
  }

  @Test
  void throwsForANonNumericConfidenceInAWebSearchFindingLine() {
    StubMessageService messageService = new StubMessageService(
        messageWithText(StopReason.END_TURN, "Claim. :: https://example.com :: not-a-number"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalArgumentException.class,
        () -> ParallelSubagentDispatch.invokeWebSearchSubagent(client, "Research solar"));
  }

  @Test
  void throwsForAMalformedDocumentAnalysisFindingLine() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.END_TURN, "Analysis :: Doc Name only"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalArgumentException.class,
        () -> ParallelSubagentDispatch.invokeDocumentAnalysisSubagent(client, "Analyze solar report"));
  }

  @Test
  void throwsForANonNumericPageNumberInADocumentAnalysisFindingLine() {
    StubMessageService messageService = new StubMessageService(
        messageWithText(StopReason.END_TURN, "Analysis. :: Doc Name :: not-a-page :: 0.6"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalArgumentException.class,
        () -> ParallelSubagentDispatch.invokeDocumentAnalysisSubagent(client, "Analyze solar report"));
  }

  // Builds a tool_use content block as Claude would emit it when requesting a tool call.
  private static ToolUseBlock toolUseBlock(String id, String name, Map<String, Object> input) {
    return ToolUseBlock.builder()
        .id(id)
        .name(name)
        .caller(DirectCaller.builder().build())
        .input(JsonValue.from(input))
        .build();
  }

  // Helper method to create a Message whose content is one or more tool_use blocks.
  private static Message messageWithToolUse(ToolUseBlock... toolUseBlocks) {
    List<ContentBlock> content = Arrays.stream(toolUseBlocks).map(ContentBlock::ofToolUse).toList();
    return Message.builder()
        .id("msg_test")
        .content(content)
        .model(Model.of("claude-test-model"))
        .stopReason(StopReason.TOOL_USE)
        .stopSequence((String) null)
        .stopDetails((RefusalStopDetails) null)
        .usage(testUsage())
        .build();
  }

  // Helper method to create a Message with a given StopReason and one text block per string.
  private static Message messageWithText(StopReason stopReason, String... texts) {
    List<ContentBlock> content = Arrays.stream(texts)
        .map(text -> ContentBlock.ofText(TextBlock.builder().text(text).citations(List.of()).build()))
        .toList();
    return Message.builder()
        .id("msg_test")
        .content(content)
        .model(Model.of("claude-test-model"))
        .stopReason(stopReason)
        .stopSequence((String) null)
        .stopDetails((RefusalStopDetails) null)
        .usage(testUsage())
        .build();
  }

  private static Usage testUsage() {
    return Usage.builder()
        .inputTokens(1)
        .outputTokens(1)
        .outputTokensDetails((OutputTokensDetails) null)
        .cacheCreation((CacheCreation) null)
        .cacheCreationInputTokens(0L)
        .cacheReadInputTokens(0L)
        .inferenceGeo((String) null)
        .serverToolUse((ServerToolUsage) null)
        .serviceTier((Usage.ServiceTier) null)
        .build();
  }

  // A thread-safe MessageService that routes each request to a canned
  // response by matching its system prompt, rather than by call order -
  // needed because dispatchSubagentsInParallel genuinely runs its calls on
  // separate threads, so the order they arrive in is not deterministic.
  private static final class RoutingMessageService implements MessageService {
    final List<MessageCreateParams> requests = Collections.synchronizedList(new ArrayList<>());
    private final Message webSearchResponse;
    private final Message documentAnalysisResponse;

    RoutingMessageService(Message webSearchResponse, Message documentAnalysisResponse) {
      this.webSearchResponse = webSearchResponse;
      this.documentAnalysisResponse = documentAnalysisResponse;
    }

    @Override
    public Message create(MessageCreateParams params, RequestOptions requestOptions) {
      requests.add(params);
      String systemPrompt = params.system().orElseThrow().string().orElseThrow();
      if (systemPrompt.equals(ParallelSubagentDispatch.WEB_SEARCH_SUBAGENT_SYSTEM_PROMPT)) {
        return webSearchResponse;
      }
      if (systemPrompt.equals(ParallelSubagentDispatch.DOCUMENT_ANALYSIS_SUBAGENT_SYSTEM_PROMPT)) {
        return documentAnalysisResponse;
      }
      throw new IllegalArgumentException("Unexpected system prompt: " + systemPrompt);
    }

    @Override
    public MessageService.WithRawResponse withRawResponse() {
      throw new UnsupportedOperationException();
    }

    @Override
    public MessageService withOptions(Consumer<ClientOptions.Builder> modifier) {
      throw new UnsupportedOperationException();
    }

    @Override
    public BatchService batches() {
      throw new UnsupportedOperationException();
    }

    @Override
    public StreamResponse<RawMessageStreamEvent> createStreaming(
        MessageCreateParams params, RequestOptions requestOptions) {
      throw new UnsupportedOperationException();
    }

    @Override
    public MessageTokensCount countTokens(
        MessageCountTokensParams params, RequestOptions requestOptions) {
      throw new UnsupportedOperationException();
    }
  }
}
