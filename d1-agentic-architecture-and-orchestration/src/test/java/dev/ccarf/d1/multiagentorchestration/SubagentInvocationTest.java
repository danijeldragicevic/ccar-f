package dev.ccarf.d1.multiagentorchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

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

class SubagentInvocationTest {

    @Test
    void webSearchSubagentSendsSubtopicAndGoalUnderItsOwnSystemPrompt() {
        StubMessageService messageService =
                new StubMessageService(messageWithText(StopReason.END_TURN, "Solar findings."));
        AnthropicClient client = new StubAnthropicClient(messageService);

        String result = SubagentInvocation.invokeWebSearchSubagent(
                client, "Solar adoption trends", "Comprehensive renewable energy report");

        assertEquals("Solar findings.", result);
        MessageCreateParams sentParams = messageService.requests.get(0);
        assertEquals(Config.modelWorker(), sentParams.model().toString());
        assertEquals(SubagentInvocation.WEB_SEARCH_SUBAGENT_SYSTEM_PROMPT,
                sentParams.system().orElseThrow().string().orElseThrow());

        String userPrompt = sentParams.messages().get(0).content().string().orElseThrow();
        assertTrue(userPrompt.contains("Solar adoption trends"));
        assertTrue(userPrompt.contains("Comprehensive renewable energy report"));
        assertTrue(userPrompt.contains("No prior subagent context"));
    }

    @Test
    void documentAnalysisSubagentForwardsPriorContextExplicitly() {
        StubMessageService messageService =
                new StubMessageService(messageWithText(StopReason.END_TURN, "Analysis."));
        AnthropicClient client = new StubAnthropicClient(messageService);

        String result = SubagentInvocation.invokeDocumentAnalysisSubagent(client,
                "Solar adoption trends", "Comprehensive renewable energy report",
                "Solar findings from the web-search subagent.");

        assertEquals("Analysis.", result);
        MessageCreateParams sentParams = messageService.requests.get(0);
        assertEquals(Config.modelWorker(), sentParams.model().toString());
        assertEquals(SubagentInvocation.DOCUMENT_ANALYSIS_SUBAGENT_SYSTEM_PROMPT,
                sentParams.system().orElseThrow().string().orElseThrow());

        String userPrompt = sentParams.messages().get(0).content().string().orElseThrow();
        assertTrue(userPrompt.contains("Solar adoption trends"));
        assertTrue(userPrompt.contains("Comprehensive renewable energy report"));
        assertTrue(userPrompt.contains("Solar findings from the web-search subagent."));
    }

    @Test
    void documentAnalysisSubagentNotesAbsenceOfPriorContextWhenNull() {
        StubMessageService messageService =
                new StubMessageService(messageWithText(StopReason.END_TURN, "Analysis."));
        AnthropicClient client = new StubAnthropicClient(messageService);

        SubagentInvocation.invokeDocumentAnalysisSubagent(client, "Solar adoption trends",
                "Comprehensive renewable energy report", null);

        String userPrompt = messageService.requests.get(0).messages().get(0).content().string()
                .orElseThrow();
        assertTrue(userPrompt.contains("No prior subagent context"));
    }

    @Test
    void throwsForUnhandledStopReason() {
        StubMessageService messageService =
                new StubMessageService(messageWithText(StopReason.MAX_TOKENS, "truncated..."));
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalStateException.class,
                () -> SubagentInvocation.invokeWebSearchSubagent(client, "Solar adoption trends",
                        "Comprehensive renewable energy report"));
    }

    @Test
    void throwsForUnexpectedToolUseStopReason() {
        StubMessageService messageService =
                new StubMessageService(messageWithText(StopReason.TOOL_USE, "unexpected"));
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalStateException.class,
                () -> SubagentInvocation.invokeDocumentAnalysisSubagent(client,
                        "Solar adoption trends", "Comprehensive renewable energy report", null));
    }

    // Helper method to create a Message with a given StopReason and one text block per string.
    private static Message messageWithText(StopReason stopReason, String... texts) {
        List<ContentBlock> content = Arrays.stream(texts)
                .map(text -> ContentBlock.ofText(
                        TextBlock.builder().text(text).citations(List.of()).build()))
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
