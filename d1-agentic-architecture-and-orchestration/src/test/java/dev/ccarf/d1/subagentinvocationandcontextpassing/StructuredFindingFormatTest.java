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
import dev.ccarf.d1.subagentinvocationandcontextpassing.StructuredFindingFormat.Finding;
import org.junit.jupiter.api.Test;

class StructuredFindingFormatTest {

  @Test
  void sendsSubtopicUnderTheWebSearchSubagentSystemPrompt() {
    StubMessageService messageService = new StubMessageService(
        messageWithText(StopReason.END_TURN, "Solar adoption is accelerating. :: https://example.com/solar :: 0.8"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    StructuredFindingFormat.invokeWebSearchSubagent(client, "Solar power adoption trends");

    MessageCreateParams sentParams = messageService.requests.get(0);
    assertEquals(Config.modelWorker(), sentParams.model().toString());
    assertEquals(StructuredFindingFormat.WEB_SEARCH_SUBAGENT_SYSTEM_PROMPT,
        sentParams.system().orElseThrow().string().orElseThrow());

    String userPrompt = sentParams.messages().get(0).content().string().orElseThrow();
    assertTrue(userPrompt.contains("Solar power adoption trends"));
  }

  @Test
  void parsesASingleFindingLineIntoAFindingRecord() {
    StubMessageService messageService = new StubMessageService(messageWithText(StopReason.END_TURN,
        "Global solar capacity grew 32% in 2024. :: https://example.com/solar-2024 :: 0.9"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    List<Finding> findings = StructuredFindingFormat.invokeWebSearchSubagent(client, "Solar adoption");

    assertEquals(1, findings.size());
    Finding finding = findings.get(0);
    assertEquals("Global solar capacity grew 32% in 2024.", finding.claim());
    assertNull(finding.analysis());
    assertEquals("https://example.com/solar-2024", finding.sourceUrl());
    assertNull(finding.documentName());
    assertNull(finding.pageNumber());
    assertEquals(0.9, finding.confidence());
    assertEquals("web_search_agent", finding.retrievedBy());
  }

  @Test
  void parsesOneFindingPerNonBlankResponseLine() {
    StubMessageService messageService = new StubMessageService(messageWithText(StopReason.END_TURN,
        "Claim one. :: https://example.com/one :: 0.8\n"
            + "\n"
            + "Claim two. :: https://example.com/two :: 0.6"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    List<Finding> findings = StructuredFindingFormat.invokeWebSearchSubagent(client, "Solar adoption");

    assertEquals(2, findings.size());
    assertEquals("Claim one.", findings.get(0).claim());
    assertEquals("Claim two.", findings.get(1).claim());
  }

  @Test
  void throwsForUnhandledStopReason() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.MAX_TOKENS, "truncated..."));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalStateException.class,
        () -> StructuredFindingFormat.invokeWebSearchSubagent(client, "Solar adoption"));
  }

  @Test
  void throwsForAMalformedFindingLineMissingFields() {
    StubMessageService messageService =
        new StubMessageService(messageWithText(StopReason.END_TURN, "Claim with no separators at all"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalArgumentException.class,
        () -> StructuredFindingFormat.invokeWebSearchSubagent(client, "Solar adoption"));
  }

  @Test
  void throwsForANonNumericConfidenceValue() {
    StubMessageService messageService = new StubMessageService(
        messageWithText(StopReason.END_TURN, "Claim. :: https://example.com :: not-a-number"));
    AnthropicClient client = new StubAnthropicClient(messageService);

    assertThrows(IllegalArgumentException.class,
        () -> StructuredFindingFormat.invokeWebSearchSubagent(client, "Solar adoption"));
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
