package dev.ccarf.d1.subagentinvocationandcontextpassing;

import java.util.List;
import java.util.Map;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

/**
 * Exercise 1.3.2
 * Define two subagents - web search and document analysis - each with its
 * own description, system prompt, and a tool set restricted to only what
 * that role needs: the web-search subagent gets a search tool, the
 * document-analysis subagent gets a document-reading tool, and neither can
 * reach the other's tool.
 *
 * <p>Note on translation: this mirrors what {@code options.agents} defines
 * in the Claude Agent SDK - a name mapped to an {@code AgentDefinition} of
 * {@code description}/{@code prompt}/{@code tools} - which has no Java SDK
 * equivalent (see the "Note on translation" on {@link TaskToolCoordinatorAgent},
 * Exercise 1.3.1, for why). {@link AgentDefinition} here is a plain record
 * standing in for that shape.</p>
 */
public class SubagentDefinitions {

  static final String WEB_SEARCH_SUBAGENT_SYSTEM_PROMPT = """
      You are a web-search subagent in a hub-and-spoke multi-agent research \
      system. You have no memory of any other conversation and no access to \
      the coordinator's context beyond what is explicitly included in your \
      prompt - subagent isolation means nothing is inherited. Your only tool \
      is web search; you cannot read documents or spawn other subagents - \
      that is not your role.

      For every finding you report, cite the source URL and the page/article \
      title it came from - a finding with no traceable source is not usable \
      by the coordinator or by whichever subagent synthesizes your work \
      later.""";

  static final String DOCUMENT_ANALYSIS_SUBAGENT_SYSTEM_PROMPT = """
      You are a document-analysis subagent in a hub-and-spoke multi-agent \
      research system. You have no memory of any other conversation and no \
      access to the coordinator's context beyond what is explicitly included \
      in your prompt - subagent isolation means nothing is inherited. Your \
      only tool reads documents; you cannot search the web or spawn other \
      subagents - that is not your role.

      For every claim you report, cite the document name and page number it \
      came from - a claim with no traceable page reference is not usable by \
      the coordinator or by whichever subagent synthesizes your work later.""";

  /**
   * The raw-SDK stand-in for an Agent SDK {@code AgentDefinition}: a
   * subagent's description (when the coordinator should spawn it), its
   * system prompt, and the tool set it is restricted to.
   *
   * @param description when the coordinator should spawn this subagent
   * @param systemPrompt the subagent's own system prompt
   * @param tools        the tool set this subagent is restricted to - a
   *                     subagent is never given a tool outside this list
   */
  public record AgentDefinition(String description, String systemPrompt, List<Tool> tools) {
  }

  public static void main(String[] args) {

    Map<String, AgentDefinition> subagents = getSubagentDefinitions();

    subagents.forEach((name, agent) -> {
      System.out.println(name + ":");
      System.out.println("  description: " + agent.description());
      System.out.println("  tools: " + agent.tools().stream().map(Tool::name).toList());
    });
  }

  /**
   * The two subagent definitions available to the coordinator's "task" tool
   * (see {@link TaskToolCoordinatorAgent}), keyed by subagent name - the
   * raw-SDK equivalent of {@code options.agents}.
   *
   * @return the web-search and document-analysis agent definitions
   */
  static Map<String, AgentDefinition> getSubagentDefinitions() {
    return Map.of(
        "web_search_agent", getWebSearchAgentDefinition(),
        "document_analysis_agent", getDocumentAnalysisAgentDefinition());
  }

  /**
   * The web-search subagent's definition - restricted to the web-search
   * tool only.
   *
   * @return the web-search agent definition
   */
  static AgentDefinition getWebSearchAgentDefinition() {
    return new AgentDefinition(
        "Spawn this subagent to research a subtopic on the open web. It has "
            + "no access to documents - use the document-analysis subagent for that.",
        WEB_SEARCH_SUBAGENT_SYSTEM_PROMPT,
        List.of(getWebSearchTool()));
  }

  /**
   * The document-analysis subagent's definition - restricted to the
   * document-reading tool only.
   *
   * @return the document-analysis agent definition
   */
  static AgentDefinition getDocumentAnalysisAgentDefinition() {
    return new AgentDefinition(
        "Spawn this subagent to analyze a specific document for a subtopic. "
            + "It cannot search the web - use the web-search subagent for that.",
        DOCUMENT_ANALYSIS_SUBAGENT_SYSTEM_PROMPT,
        List.of(getReadDocumentTool()));
  }

  // The web-search subagent's only tool - scoped so it can search, and
  // nothing else.
  static Tool getWebSearchTool() {
    return Tool.builder()
        .name("web_search")
        .description("""
            Call this whenever you need to find information on the open web \
            for your assigned subtopic - do not guess or rely on memory for \
            facts you have not looked up. Searches the web for a query and \
            returns mock results, each with a title and source URL \
            (stub - no real network call).""")
        .inputSchema(Tool.InputSchema.builder()
            .properties(Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("query", JsonValue.from(Map.of(
                    "type", "string",
                    "description", "The search query to look up on the web.")))
                .build())
            .build())
        .build();
  }

  // The document-analysis subagent's only tool - scoped so it can read
  // documents, and nothing else.
  static Tool getReadDocumentTool() {
    return Tool.builder()
        .name("read_document")
        .description("""
            Call this whenever you need the contents of a specific document \
            for your assigned subtopic - do not guess or rely on memory for \
            facts you have not read from the document itself. Returns the \
            document's mock contents by page (stub - no real file access).""")
        .inputSchema(Tool.InputSchema.builder()
            .properties(Tool.InputSchema.Properties.builder()
                .putAdditionalProperty("document_name", JsonValue.from(Map.of(
                    "type", "string",
                    "description", "The name of the document to read.")))
                .build())
            .build())
        .build();
  }
}
