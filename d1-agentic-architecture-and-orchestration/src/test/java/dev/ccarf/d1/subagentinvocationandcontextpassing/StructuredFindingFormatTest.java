package dev.ccarf.d1.subagentinvocationandcontextpassing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import dev.ccarf.d1.subagentinvocationandcontextpassing.StructuredFindingFormat.Finding;

class StructuredFindingFormatTest {

  @Test
  void webSearchFindingCarriesContentInClaimAndMetadataPointingAtAWebSource() {
    Finding finding = StructuredFindingFormat.exampleWebSearchFinding();

    assertEquals("Global solar capacity grew 32% year-over-year in 2024.", finding.claim());
    assertNull(finding.analysis());
    assertEquals("https://example.com/renewables/solar-2024", finding.sourceUrl());
    assertNull(finding.documentName());
    assertNull(finding.pageNumber());
    assertEquals(0.9, finding.confidence());
    assertEquals("web_search_agent", finding.retrievedBy());
  }

  @Test
  void documentAnalysisFindingCarriesContentInAnalysisAndMetadataPointingAtADocumentPage() {
    Finding finding = StructuredFindingFormat.exampleDocumentAnalysisFinding();

    assertNull(finding.claim());
    assertEquals("The report attributes most of this growth to falling panel costs "
        + "rather than new subsidy programs.", finding.analysis());
    assertNull(finding.sourceUrl());
    assertEquals("Global Renewable Energy Outlook 2024", finding.documentName());
    assertEquals(17, finding.pageNumber());
    assertEquals(0.75, finding.confidence());
    assertEquals("document_analysis_agent", finding.retrievedBy());
  }
}
