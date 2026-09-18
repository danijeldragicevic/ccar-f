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
import dev.ccarf.d1.subagentinvocationandcontextpassing.AttributionVerification.AttributedClaim;
import dev.ccarf.d1.subagentinvocationandcontextpassing.AttributionVerification.Finding;
import org.junit.jupiter.api.Test;

class AttributionVerificationTest {

  @Test
  void webSearchSubagentParsesClaimSourceUrlAndConfidence() {
    StubMessageService messageService = new StubMessageService(messageWithText(StopReason.END_TURN,
        "Global solar capacity grew 32% in 2024. :: https://example.com/solar :: 0.8"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    List<Finding> findings =
        AttributionVerification.invokeWebSearchSubagent(client, "Solar adoption");

    assertEquals(1, findings.size());
    Finding finding = findings.get(0);
    assertEquals("Global solar capacity grew 32% in 2024.", finding.claim());
    assertEquals("https://example.com/solar", finding.sourceUrl());
    assertEquals(0.8, finding.confidence());
    assertEquals("web_search_agent", finding.retrievedBy());
  }

  @Test
  void documentAnalysisSubagentParsesAnalysisDocumentNamePageNumberAndConfidence() {
    StubMessageService messageService = new StubMessageService(messageWithText(StopReason.END_TURN,
        "Cost declines are the main driver of adoption. :: Global Renewable Energy Outlook 2024 :: 17 :: 0.75"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    List<Finding> findings =
        AttributionVerification.invokeDocumentAnalysisSubagent(client, "Solar adoption");

    assertEquals(1, findings.size());
    Finding finding = findings.get(0);
    assertEquals("Cost declines are the main driver of adoption.", finding.analysis());
    assertEquals("Global Renewable Energy Outlook 2024", finding.documentName());
    assertEquals(17, finding.pageNumber());
    assertEquals("document_analysis_agent", finding.retrievedBy());
  }

  @Test
  void throwsForUnhandledStopReasonInWebSearchSubagent() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.MAX_TOKENS, "truncated..."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalStateException.class,
        () -> AttributionVerification.invokeWebSearchSubagent(client, "Solar adoption"));
  }

  @Test
  void throwsForUnhandledStopReasonInDocumentAnalysisSubagent() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.MAX_TOKENS, "truncated..."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalStateException.class,
        () -> AttributionVerification.invokeDocumentAnalysisSubagent(client, "Solar adoption"));
  }

  @Test
  void throwsForAMalformedWebSearchFindingLine() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.END_TURN, "Claim with no separators at all"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalArgumentException.class,
        () -> AttributionVerification.invokeWebSearchSubagent(client, "Solar adoption"));
  }

  @Test
  void throwsForANonNumericConfidenceInAWebSearchFindingLine() {
    StubMessageService messageService = new StubMessageService(
        messageWithText(StopReason.END_TURN, "Claim. :: https://example.com :: not-a-number"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalArgumentException.class,
        () -> AttributionVerification.invokeWebSearchSubagent(client, "Solar adoption"));
  }

  @Test
  void throwsForAMalformedDocumentAnalysisFindingLine() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.END_TURN, "Analysis :: Doc Name only"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalArgumentException.class,
        () -> AttributionVerification.invokeDocumentAnalysisSubagent(client, "Solar adoption"));
  }

  @Test
  void throwsForANonNumericPageNumberInADocumentAnalysisFindingLine() {
    StubMessageService messageService = new StubMessageService(
        messageWithText(StopReason.END_TURN, "Analysis. :: Doc Name :: not-a-page :: 0.6"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalArgumentException.class,
        () -> AttributionVerification.invokeDocumentAnalysisSubagent(client, "Solar adoption"));
  }

  @Test
  void parseAttributedClaimsExtractsClaimAndCitationFromACitedLine() {
    List<AttributedClaim> claims = AttributionVerification.parseAttributedClaims(
        "Solar capacity grew 32% in 2024. [https://example.com/solar-2024]\n"
            + "Cost declines drove growth. [Global Renewable Energy Outlook 2024, p.17]");

    assertEquals(2, claims.size());
    assertEquals(new AttributedClaim("Solar capacity grew 32% in 2024.",
        "https://example.com/solar-2024"), claims.get(0));
    assertEquals(new AttributedClaim("Cost declines drove growth.",
        "Global Renewable Energy Outlook 2024, p.17"), claims.get(1));
  }

  @Test
  void parseAttributedClaimsProducesNullCitationForAnUncitedLine() {
    List<AttributedClaim> claims = AttributionVerification.parseAttributedClaims(
        "An unattributed claim with no citation at all");

    assertEquals(1, claims.size());
    assertEquals("An unattributed claim with no citation at all", claims.get(0).claim());
    assertNull(claims.get(0).citation());
  }

  @Test
  void findOrphanedClaimsReturnsOnlyTheUncitedOnes() {
    List<AttributedClaim> claims = List.of(
        new AttributedClaim("Cited claim.", "https://example.com"),
        new AttributedClaim("Orphaned claim.", null),
        new AttributedClaim("Another cited claim.", "Some Document, p.3"));

    List<AttributedClaim> orphaned = AttributionVerification.findOrphanedClaims(claims);

    assertEquals(1, orphaned.size());
    assertEquals("Orphaned claim.", orphaned.get(0).claim());
  }

  @Test
  void verifyAllClaimsAttributedPassesWhenEveryClaimIsCited() {
    List<AttributedClaim> claims = List.of(
        new AttributedClaim("Cited claim.", "https://example.com"),
        new AttributedClaim("Another cited claim.", "Some Document, p.3"));

    assertTrue(AttributionVerification.findOrphanedClaims(claims).isEmpty());
    AttributionVerification.verifyAllClaimsAttributed(claims); // does not throw
  }

  @Test
  void verifyAllClaimsAttributedThrowsAndListsOrphanedClaimsWhenSomeAreNotCited() {
    List<AttributedClaim> claims = List.of(
        new AttributedClaim("Cited claim.", "https://example.com"),
        new AttributedClaim("Orphaned claim.", null));

    IllegalStateException exception = assertThrows(IllegalStateException.class,
        () -> AttributionVerification.verifyAllClaimsAttributed(claims));
    assertTrue(exception.getMessage().contains("Orphaned claim."));
  }

  @Test
  void researchPassesCompleteFindingsToSynthesisUnderTheCitationRequiringPromptAndProducesFullyCitedClaims() {
    StubMessageService messageService = new StubMessageService(
        messageWithText(StopReason.END_TURN, "Claim A. :: https://a.example.com :: 0.8"),
        messageWithText(StopReason.END_TURN, "Analysis B. :: Doc Name :: 5 :: 0.6"),
        messageWithText(StopReason.END_TURN,
            "Claim A restated. [https://a.example.com]\nAnalysis B restated. [Doc Name, p.5]"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    String report = AttributionVerification.research(client, "Solar adoption");

    assertEquals(3, messageService.requests.size());
    MessageCreateParams synthesisParams = messageService.requests.get(2);
    assertEquals(AttributionVerification.SYNTHESIS_SUBAGENT_SYSTEM_PROMPT,
        synthesisParams.system().orElseThrow().string().orElseThrow());

    List<AttributedClaim> claims = AttributionVerification.parseAttributedClaims(report);
    assertEquals(2, claims.size());
    assertTrue(AttributionVerification.findOrphanedClaims(claims).isEmpty());
    AttributionVerification.verifyAllClaimsAttributed(claims); // does not throw
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
