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
 * Exercise 1.2.3
 * Spawn two subagents (web search and document analysis) with explicit context
 * passing - each subagent prompt includes all the information it needs, since
 * subagent isolation means no shared memory and no inherited context.
 */
public class SubagentInvocation {

  static final String WEB_SEARCH_SUBAGENT_SYSTEM_PROMPT = """
      You are a web-search subagent in a hub-and-spoke multi-agent research system. \
      You have no memory of any other conversation and no access to the \
      coordinator's context beyond what is explicitly included in this prompt - \
      subagent isolation means nothing is inherited. Your only job is to research \
      the single subtopic assigned below and report factual findings; you do not \
      decompose topics, select other subagents, or aggregate results - that is the \
      coordinator's job.

      Report your findings as plain prose, with no meta-commentary about your role.""";

  static final String DOCUMENT_ANALYSIS_SUBAGENT_SYSTEM_PROMPT = """
      You are a document-analysis subagent in a hub-and-spoke multi-agent research \
      system. You have no memory of any other conversation and no access to the \
      coordinator's context beyond what is explicitly included in this prompt - \
      subagent isolation means nothing is inherited. Your only job is to analyze \
      the single subtopic assigned below, in light of any material handed to you, \
      and report your analysis; you do not decompose topics, select other \
      subagents, or aggregate results - that is the coordinator's job.

      Report your analysis as plain prose, with no meta-commentary about your role.""";

  public static void main(String[] args) {

    AnthropicClient client = AnthropicOkHttpClient.fromEnv();
    
    String researchGoal = "Produce a comprehensive research report on renewable energy technologies.";
    String subtopic = "Solar power adoption trends";

    String webSearchFindings = 
        invokeWebSearchSubagent(client, subtopic, researchGoal);
    System.out.println("[web_search subagent]\n" + webSearchFindings + "\n");

    // The document-analysis subagent has no way to know what the web-search
    // subagent found unless we explicitly hand it those findings here - that's
    // the whole point of subagent isolation.
    String documentAnalysisFindings = 
        invokeDocumentAnalysisSubagent(client, subtopic, researchGoal, webSearchFindings);
    System.out.println("[document_analysis subagent]\n" + documentAnalysisFindings);
  }

  /**
   * Invokes the web-search subagent as the first agent for a subtopic, with no
   * prior context to pass along.
   *
   * @param client       the Anthropic client to send the request through
   * @param subtopic     the single subtopic this subagent is assigned
   * @param researchGoal the overall research goal the coordinator is pursuing
   * @return the subagent's findings
   */
  static String invokeWebSearchSubagent(AnthropicClient client, String subtopic,
      String researchGoal) {
    return invokeSubagent(client, WEB_SEARCH_SUBAGENT_SYSTEM_PROMPT,
        buildSubagentPrompt(subtopic, researchGoal, null));
  }

  /**
   * Invokes the document-analysis subagent, explicitly forwarding whatever a
   * prior subagent found - the document-analysis subagent has no other way to
   * see it, since subagents share no memory.
   *
   * @param client       the Anthropic client to send the request through
   * @param subtopic     the single subtopic this subagent is assigned
   * @param researchGoal the overall research goal the coordinator is pursuing
   * @param priorContext relevant findings from a prior subagent, or null/blank
   *                     if this is the first subagent invoked for the subtopic
   * @return the subagent's analysis
   */
  static String invokeDocumentAnalysisSubagent(AnthropicClient client, String subtopic,
      String researchGoal, String priorContext) {
    return invokeSubagent(client, DOCUMENT_ANALYSIS_SUBAGENT_SYSTEM_PROMPT,
        buildSubagentPrompt(subtopic, researchGoal, priorContext));
  }

  // Assembles the one prompt a subagent will ever see: its assigned subtopic,
  // the overall research goal, and any prior context - nothing is implicit.
  private static String buildSubagentPrompt(String subtopic, String researchGoal,
      String priorContext) {
    StringBuilder prompt = new StringBuilder()
        .append("Overall research goal: ").append(researchGoal).append("\n\n")
        .append("Your assigned subtopic: ").append(subtopic).append("\n\n");

    if (priorContext == null || priorContext.isBlank()) {
      prompt.append("No prior subagent context - you are the first subagent invoked "
          + "for this subtopic.");
    } else {
      prompt.append("Relevant context from a prior subagent (this is the only way you "
          + "can know about it - you share no memory with it):\n").append(priorContext);
    }

    return prompt.toString();
  }

  private static String invokeSubagent(AnthropicClient client, String systemPrompt,
      String userPrompt) {
    MessageCreateParams params = MessageCreateParams.builder()
        .model(Config.modelWorker())
        .maxTokens(Config.maxTokens())
        .system(systemPrompt)
        .messages(List.of(MessageParam.builder()
            .role(MessageParam.Role.USER)
            .content(userPrompt)
            .build()))
        .build();

    Message response = client.messages().create(params);
    StopReason stopReason = response.stopReason().get();

    if (stopReason.equals(StopReason.END_TURN)) {
      return response.content().stream()
          .flatMap(block -> block.text().stream())
          .map(TextBlock::text)
          .collect(Collectors.joining("\n"));
    }

    throw new IllegalStateException("Unhandled stop_reason: " + stopReason);
  }
}
