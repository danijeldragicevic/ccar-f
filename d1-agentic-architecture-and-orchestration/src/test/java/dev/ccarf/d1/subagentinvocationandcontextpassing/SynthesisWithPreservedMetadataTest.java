package dev.ccarf.d1.subagentinvocationandcontextpassing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.RefusalStopDetails;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import dev.ccarf.common.Config;
import dev.ccarf.d1.subagentinvocationandcontextpassing.SynthesisWithPreservedMetadata.Finding;
import org.junit.jupiter.api.Test;

class SynthesisWithPreservedMetadataTest {

  @Test
  void webSearchSubagentParsesClaimSourceUrlAndConfidence() {
    StubMessageService messageService = new StubMessageService(messageWithText(StopReason.END_TURN,
        "Global solar capacity grew 32% in 2024. :: https://example.com/solar :: 0.8"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    List<Finding> findings =
        SynthesisWithPreservedMetadata.invokeWebSearchSubagent(client, "Solar adoption");

    assertEquals(1, findings.size());
    Finding finding = findings.get(0);
    assertEquals("Global solar capacity grew 32% in 2024.", finding.claim());
    assertNull(finding.analysis());
    assertEquals("https://example.com/solar", finding.sourceUrl());
    assertNull(finding.documentName());
    assertNull(finding.pageNumber());
    assertEquals(0.8, finding.confidence());
    assertEquals("web_search_agent", finding.retrievedBy());

    MessageCreateParams sentParams = messageService.requests.get(0);
    assertEquals(Config.modelWorker(), sentParams.model().toString());
    assertEquals(SynthesisWithPreservedMetadata.WEB_SEARCH_SUBAGENT_SYSTEM_PROMPT,
        sentParams.system().orElseThrow().string().orElseThrow());
  }

  @Test
  void documentAnalysisSubagentParsesAnalysisDocumentNamePageNumberAndConfidence() {
    StubMessageService messageService = new StubMessageService(messageWithText(StopReason.END_TURN,
        "Cost declines are the main driver of adoption. :: Global Renewable Energy Outlook 2024 :: 17 :: 0.75"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    List<Finding> findings =
        SynthesisWithPreservedMetadata.invokeDocumentAnalysisSubagent(client, "Solar adoption");

    assertEquals(1, findings.size());
    Finding finding = findings.get(0);
    assertNull(finding.claim());
    assertEquals("Cost declines are the main driver of adoption.", finding.analysis());
    assertNull(finding.sourceUrl());
    assertEquals("Global Renewable Energy Outlook 2024", finding.documentName());
    assertEquals(17, finding.pageNumber());
    assertEquals(0.75, finding.confidence());
    assertEquals("document_analysis_agent", finding.retrievedBy());

    MessageCreateParams sentParams = messageService.requests.get(0);
    assertEquals(SynthesisWithPreservedMetadata.DOCUMENT_ANALYSIS_SUBAGENT_SYSTEM_PROMPT,
        sentParams.system().orElseThrow().string().orElseThrow());
  }

  @Test
  void researchCombinesBothSubagentsFindingsAndPassesEveryMetadataFieldToSynthesis() {
    StubMessageService messageService = new StubMessageService(
        messageWithText(StopReason.END_TURN, "Claim A. :: https://a.example.com :: 0.8"),
        messageWithText(StopReason.END_TURN, "Analysis B. :: Doc Name :: 5 :: 0.6"),
        messageWithText(StopReason.END_TURN, "Final synthesized report."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    String report = SynthesisWithPreservedMetadata.research(client, "Solar adoption");

    assertEquals("Final synthesized report.", report);
    assertEquals(3, messageService.requests.size());

    MessageCreateParams synthesisParams = messageService.requests.get(2);
    assertEquals(SynthesisWithPreservedMetadata.SYNTHESIS_SUBAGENT_SYSTEM_PROMPT,
        synthesisParams.system().orElseThrow().string().orElseThrow());

    String synthesisPrompt = synthesisParams.messages().get(0).content().string().orElseThrow();

    // Web-search finding: content, its own metadata, and the null fields
    // explicitly labeled "none" rather than silently omitted.
    assertTrue(synthesisPrompt.contains("Claim A."));
    assertTrue(synthesisPrompt.contains("source_url: https://a.example.com"));
    assertTrue(synthesisPrompt.contains("document_name: none"));
    assertTrue(synthesisPrompt.contains("page_number: none"));
    assertTrue(synthesisPrompt.contains("confidence: 0.8"));
    assertTrue(synthesisPrompt.contains("retrieved_by: web_search_agent"));

    // Document-analysis finding: same completeness check.
    assertTrue(synthesisPrompt.contains("Analysis B."));
    assertTrue(synthesisPrompt.contains("source_url: none"));
    assertTrue(synthesisPrompt.contains("document_name: Doc Name"));
    assertTrue(synthesisPrompt.contains("page_number: 5"));
    assertTrue(synthesisPrompt.contains("confidence: 0.6"));
    assertTrue(synthesisPrompt.contains("retrieved_by: document_analysis_agent"));
  }

  @Test
  void throwsForUnhandledStopReasonInWebSearchSubagent() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.MAX_TOKENS, "truncated..."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalStateException.class,
        () -> SynthesisWithPreservedMetadata.invokeWebSearchSubagent(client, "Solar adoption"));
  }

  @Test
  void throwsForUnhandledStopReasonInDocumentAnalysisSubagent() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.MAX_TOKENS, "truncated..."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalStateException.class,
        () -> SynthesisWithPreservedMetadata.invokeDocumentAnalysisSubagent(client, "Solar adoption"));
  }

  @Test
  void throwsForAMalformedWebSearchFindingLine() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.END_TURN, "Claim with no separators at all"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalArgumentException.class,
        () -> SynthesisWithPreservedMetadata.invokeWebSearchSubagent(client, "Solar adoption"));
  }

  @Test
  void throwsForANonNumericConfidenceInAWebSearchFindingLine() {
    StubMessageService messageService = new StubMessageService(
        messageWithText(StopReason.END_TURN, "Claim. :: https://example.com :: not-a-number"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalArgumentException.class,
        () -> SynthesisWithPreservedMetadata.invokeWebSearchSubagent(client, "Solar adoption"));
  }

  @Test
  void throwsForAMalformedDocumentAnalysisFindingLine() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.END_TURN, "Analysis :: Doc Name only"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalArgumentException.class,
        () -> SynthesisWithPreservedMetadata.invokeDocumentAnalysisSubagent(client, "Solar adoption"));
  }

  @Test
  void throwsForANonNumericPageNumberInADocumentAnalysisFindingLine() {
    StubMessageService messageService = new StubMessageService(
        messageWithText(StopReason.END_TURN, "Analysis. :: Doc Name :: not-a-page :: 0.6"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalArgumentException.class,
        () -> SynthesisWithPreservedMetadata.invokeDocumentAnalysisSubagent(client, "Solar adoption"));
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
}
