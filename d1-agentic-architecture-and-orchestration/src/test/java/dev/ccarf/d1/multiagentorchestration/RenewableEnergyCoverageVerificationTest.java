package dev.ccarf.d1.multiagentorchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.RefusalStopDetails;
import com.anthropic.models.messages.ServerToolUsage;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;

import dev.ccarf.d1.multiagentorchestration.RenewableEnergyCoverageVerification.CoverageEntry;
import dev.ccarf.d1.multiagentorchestration.RenewableEnergyCoverageVerification.CoverageLevel;
import dev.ccarf.d1.multiagentorchestration.RenewableEnergyCoverageVerification.ResearchOutcome;
import dev.ccarf.d1.multiagentorchestration.RenewableEnergyCoverageVerification.VerificationResult;

class RenewableEnergyCoverageVerificationTest {

    private static final String SIX_SUBTOPICS =
            "Solar\nWind\nGeothermal\nTidal\nBiomass\nFusion";
    private static final String ALL_WELL_COVERED =
            "Solar :: WELL_COVERED :: covered\n"
                    + "Wind :: WELL_COVERED :: covered\n"
                    + "Geothermal :: WELL_COVERED :: covered\n"
                    + "Tidal :: WELL_COVERED :: covered\n"
                    + "Biomass :: WELL_COVERED :: covered\n"
                    + "Fusion :: WELL_COVERED :: covered";
    private static final String FULL_REPORT =
            "Solar: ...\nWind: ...\nGeothermal: ...\nTidal: ...\nBiomass: ...\nFusion: ...";

    @Test
    void researchAndVerifyPassesWhenAllSixEnergyTypesAreCovered() {
        StubMessageService messageService = new StubMessageService(
                messageWithText(StopReason.END_TURN, SIX_SUBTOPICS),
                messageWithText(StopReason.END_TURN, "web findings"),
                messageWithText(StopReason.END_TURN, "doc findings"),
                messageWithText(StopReason.END_TURN, ALL_WELL_COVERED),
                messageWithText(StopReason.END_TURN, FULL_REPORT));
        AnthropicClient client = new StubAnthropicClient(messageService);

        ResearchOutcome outcome = RenewableEnergyCoverageVerification.researchAndVerify(client,
                "The future of renewable energy technologies");

        assertTrue(outcome.verification().coverageComplete());
        assertTrue(outcome.verification().missingFromDecomposition().isEmpty());
        assertTrue(outcome.verification().missingFromReport().isEmpty());
        assertEquals(5, messageService.requests.size());
    }

    @Test
    void researchAndVerifyRunsRefinementBeforeSynthesizingReport() {
        String fusionMissing =
                "Solar :: WELL_COVERED :: covered\n"
                        + "Wind :: WELL_COVERED :: covered\n"
                        + "Geothermal :: WELL_COVERED :: covered\n"
                        + "Tidal :: WELL_COVERED :: covered\n"
                        + "Biomass :: WELL_COVERED :: covered\n"
                        + "Fusion :: MISSING :: not mentioned";
        StubMessageService messageService = new StubMessageService(
                messageWithText(StopReason.END_TURN, SIX_SUBTOPICS),
                messageWithText(StopReason.END_TURN, "web findings"),
                messageWithText(StopReason.END_TURN, "doc findings"),
                messageWithText(StopReason.END_TURN, fusionMissing),
                messageWithText(StopReason.END_TURN, "fusion web follow-up"),
                messageWithText(StopReason.END_TURN, "fusion doc follow-up"),
                messageWithText(StopReason.END_TURN, ALL_WELL_COVERED),
                messageWithText(StopReason.END_TURN, FULL_REPORT));
        AnthropicClient client = new StubAnthropicClient(messageService);

        ResearchOutcome outcome = RenewableEnergyCoverageVerification.researchAndVerify(client,
                "The future of renewable energy technologies");

        assertTrue(outcome.verification().coverageComplete());
        assertEquals(8, messageService.requests.size());
    }

    @Test
    void verificationDiagnosesMissingDecompositionSeparatelyFromMissingReportText() {
        List<String> subtopics = List.of("Solar", "Wind", "Geothermal", "Tidal", "Biomass");
        List<CoverageEntry> coverage = List.of(
                new CoverageEntry("Solar", CoverageLevel.WELL_COVERED, "covered"),
                new CoverageEntry("Wind", CoverageLevel.WELL_COVERED, "covered"),
                new CoverageEntry("Geothermal", CoverageLevel.WELL_COVERED, "covered"),
                new CoverageEntry("Tidal", CoverageLevel.WELL_COVERED, "covered"),
                new CoverageEntry("Biomass", CoverageLevel.WELL_COVERED, "covered"));
        String report = "Solar: ...\nWind: ...\nGeothermal: ...\nBiomass: ...\nFusion: ...";

        VerificationResult result = RenewableEnergyCoverageVerification
                .verifyRenewableEnergyCoverage(subtopics, coverage, report);

        assertFalse(result.coverageComplete());
        assertEquals(List.of("fusion"), result.missingFromDecomposition());
        assertEquals(List.of("tidal"), result.missingFromReport());
    }

    @Test
    void verificationIsCaseInsensitive() {
        List<String> subtopics = List.of("SOLAR power", "Wind Energy", "Geothermal Systems",
                "Tidal Power", "Biomass Conversion", "Nuclear FUSION research");
        List<CoverageEntry> coverage = subtopics.stream()
                .map(subtopic -> new CoverageEntry(subtopic, CoverageLevel.WELL_COVERED, "ok"))
                .toList();
        String report = "SOLAR ... wind ... Geothermal ... TIDAL ... biomass ... FUSION ...";

        VerificationResult result = RenewableEnergyCoverageVerification
                .verifyRenewableEnergyCoverage(subtopics, coverage, report);

        assertTrue(result.coverageComplete());
    }

    @Test
    void throwsForUnhandledStopReasonDuringReportSynthesis() {
        StubMessageService messageService = new StubMessageService(
                messageWithText(StopReason.END_TURN, SIX_SUBTOPICS),
                messageWithText(StopReason.END_TURN, "web findings"),
                messageWithText(StopReason.END_TURN, "doc findings"),
                messageWithText(StopReason.END_TURN, ALL_WELL_COVERED),
                messageWithText(StopReason.MAX_TOKENS, "truncated..."));
        AnthropicClient client = new StubAnthropicClient(messageService);

        assertThrows(IllegalStateException.class,
                () -> RenewableEnergyCoverageVerification.researchAndVerify(client,
                        "The future of renewable energy technologies"));
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
