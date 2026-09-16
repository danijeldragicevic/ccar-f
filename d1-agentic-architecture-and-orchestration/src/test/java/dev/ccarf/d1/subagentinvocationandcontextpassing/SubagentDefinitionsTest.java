package dev.ccarf.d1.subagentinvocationandcontextpassing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.anthropic.models.messages.Tool;
import dev.ccarf.d1.subagentinvocationandcontextpassing.SubagentDefinitions.AgentDefinition;
import org.junit.jupiter.api.Test;

class SubagentDefinitionsTest {

  @Test
  void getSubagentDefinitionsReturnsExactlyWebSearchAndDocumentAnalysis() {
    Map<String, AgentDefinition> subagents = SubagentDefinitions.getSubagentDefinitions();

    assertEquals(2, subagents.size());
    assertTrue(subagents.containsKey("web_search_agent"));
    assertTrue(subagents.containsKey("document_analysis_agent"));
  }

  @Test
  void webSearchAgentHasNonBlankDescriptionAndSystemPrompt() {
    AgentDefinition webSearchAgent = SubagentDefinitions.getWebSearchAgentDefinition();

    assertTrue(!webSearchAgent.description().isBlank());
    assertTrue(!webSearchAgent.systemPrompt().isBlank());
  }

  @Test
  void webSearchAgentIsRestrictedToWebSearchToolOnly() {
    AgentDefinition webSearchAgent = SubagentDefinitions.getWebSearchAgentDefinition();

    assertEquals(1, webSearchAgent.tools().size());
    assertEquals("web_search", webSearchAgent.tools().get(0).name());
  }

  @Test
  void documentAnalysisAgentHasNonBlankDescriptionAndSystemPrompt() {
    AgentDefinition documentAnalysisAgent = SubagentDefinitions.getDocumentAnalysisAgentDefinition();

    assertTrue(!documentAnalysisAgent.description().isBlank());
    assertTrue(!documentAnalysisAgent.systemPrompt().isBlank());
  }

  @Test
  void documentAnalysisAgentIsRestrictedToReadDocumentToolOnly() {
    AgentDefinition documentAnalysisAgent = SubagentDefinitions.getDocumentAnalysisAgentDefinition();

    assertEquals(1, documentAnalysisAgent.tools().size());
    assertEquals("read_document", documentAnalysisAgent.tools().get(0).name());
  }

  @Test
  void webSearchToolDeclaresNameDescriptionAndQueryProperty() {
    Tool webSearchTool = SubagentDefinitions.getWebSearchTool();

    assertEquals("web_search", webSearchTool.name());
    assertTrue(webSearchTool.description().isPresent() && !webSearchTool.description().get().isBlank());
    assertTrue(webSearchTool.inputSchema().properties().isPresent());
    assertTrue(webSearchTool.inputSchema().properties().get()
        ._additionalProperties().containsKey("query"));
  }

  @Test
  void readDocumentToolDeclaresNameDescriptionAndDocumentNameProperty() {
    Tool readDocumentTool = SubagentDefinitions.getReadDocumentTool();

    assertEquals("read_document", readDocumentTool.name());
    assertTrue(readDocumentTool.description().isPresent() && !readDocumentTool.description().get().isBlank());
    assertTrue(readDocumentTool.inputSchema().properties().isPresent());
    assertTrue(readDocumentTool.inputSchema().properties().get()
        ._additionalProperties().containsKey("document_name"));
  }
}
