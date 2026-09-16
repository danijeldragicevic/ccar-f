package dev.ccarf.d1.subagentinvocationandcontextpassing;

/**
 * Exercise 1.3.3
 * Design a structured output format that separates a finding's content from
 * its metadata: content fields (claim, analysis) capture what a subagent
 * found; metadata fields (source_url, document_name, page_number,
 * confidence, retrieved_by) capture where it came from and how reliable it
 * is. Keeping these separate is what lets a later synthesis subagent
 * attribute every claim back to a specific source, instead of receiving
 * pre-flattened prose it has no way to cite - the exact failure mode this
 * design exists to prevent.
 */
public class StructuredFindingFormat {

  /**
   * A single finding reported by a research subagent - content and metadata
   * kept as separate fields rather than folded into one string, so
   * provenance survives being passed on to a synthesis subagent.
   *
   * @param claim        a short factual statement, reported by the
   *                     web-search subagent, or null if this finding came
   *                     from document analysis instead
   * @param analysis     a longer analytical statement, reported by the
   *                     document-analysis subagent, or null if this finding
   *                     came from web search instead
   * @param sourceUrl    the web source for this finding, or null unless
   *                     retrievedBy is the web-search subagent
   * @param documentName the document this finding was drawn from, or null
   *                     unless retrievedBy is the document-analysis subagent
   * @param pageNumber   the page within documentName, or null unless
   *                     retrievedBy is the document-analysis subagent
   * @param confidence   the subagent's self-reported confidence in this
   *                     finding, in [0.0, 1.0]
   * @param retrievedBy  which subagent produced this finding - one of the
   *                     names {@link SubagentDefinitions#getSubagentDefinitions()}
   *                     returns, e.g. "web_search_agent"
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

    Finding webSearchFinding = exampleWebSearchFinding();
    Finding documentAnalysisFinding = exampleDocumentAnalysisFinding();

    System.out.println(webSearchFinding);
    System.out.println(documentAnalysisFinding);
  }

  // A hand-built Finding shaped like one the web-search subagent would report:
  // content lives in claim, metadata points at a web source.
  static Finding exampleWebSearchFinding() {
    return new Finding(
        "Global solar capacity grew 32% year-over-year in 2024.",
        null,
        "https://example.com/renewables/solar-2024",
        null,
        null,
        0.9,
        "web_search_agent");
  }

  // A hand-built Finding shaped like one the document-analysis subagent would
  // report: content lives in analysis, metadata points at a document/page.
  static Finding exampleDocumentAnalysisFinding() {
    return new Finding(
        null,
        "The report attributes most of this growth to falling panel costs "
            + "rather than new subsidy programs.",
        null,
        "Global Renewable Energy Outlook 2024",
        17,
        0.75,
        "document_analysis_agent");
  }
}
