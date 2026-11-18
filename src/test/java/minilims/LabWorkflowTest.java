package minilims;

import static minilims.LabService.Decision.APPROVE;
import static minilims.LabService.Decision.REJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import minilims.LabService.AuditView;
import minilims.LabService.ImportError;
import minilims.LabService.ProductRequest;
import minilims.LabService.ResultView;
import minilims.LabService.SampleView;
import minilims.LabService.SpecRequest;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRPrintText;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@AutoConfigureMockMvc
class LabWorkflowTest {

    @Autowired
    LabService lab;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    SampleRepository samples;

    @Autowired
    CoaReport coaReport;

    @Test
    void flagsResultsOutsideTheSpecificationLimits() {
        var number = registeredSample();

        lab.enterResult(number, "ASSAY", new BigDecimal("105.0"), null, "analyst");
        lab.enterResult(number, "DISSOLUTION", new BigDecimal("79.9"), null, "analyst");
        var sample = lab.enterResult(number, "LOD", new BigDecimal("0.5"), null, "analyst");

        assertThat(result(sample, "ASSAY").outOfSpec()).isFalse();
        assertThat(result(sample, "DISSOLUTION").outOfSpec()).isTrue();
        assertThat(result(sample, "LOD").outOfSpec()).isFalse();

        sample = lab.enterResult(number, "ASSAY", new BigDecimal("105.1"), "Transcription error", "analyst");
        assertThat(result(sample, "ASSAY").outOfSpec()).isTrue();
    }

    @Test
    void changingAResultNeedsAReasonAndEveryChangeIsAudited() {
        var number = registeredSample();
        lab.enterResult(number, "ASSAY", new BigDecimal("98.0"), null, "analyst");

        assertThatThrownBy(() -> lab.enterResult(number, "ASSAY", new BigDecimal("99.0"), " ", "analyst"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("A reason is required to change an entered result");

        lab.enterResult(number, "ASSAY", new BigDecimal("99.0"), "Recalculated after a dilution error", "lead");

        assertThat(lab.audit(number))
                .extracting(AuditView::action, AuditView::oldValue, AuditView::newValue, AuditView::username,
                        AuditView::reason)
                .containsExactly(
                        tuple("SAMPLE_REGISTERED", null, null, "analyst", "Registered for batch B-2611"),
                        tuple("RESULT_ENTERED", null, "98.0", "analyst", "Initial entry"),
                        tuple("RESULT_CHANGED", "98.0", "99.0", "lead", "Recalculated after a dilution error"));
        assertThat(lab.audit(number)).allSatisfy(event -> assertThat(event.at()).isNotNull());
    }

    @Test
    void approvalNeedsEveryResultAndAReviewerWhoEnteredNone() {
        var number = registeredSample();
        lab.enterResult(number, "ASSAY", new BigDecimal("99.0"), null, "analyst");

        assertThatThrownBy(() -> lab.review(number, APPROVE, null, "reviewer"))
                .hasMessageContaining("2 of 3 results are still missing");

        lab.enterResult(number, "DISSOLUTION", new BigDecimal("88"), null, "lead");
        lab.enterResult(number, "LOD", new BigDecimal("0.2"), null, "analyst");

        assertThatThrownBy(() -> lab.review(number, APPROVE, null, "lead"))
                .hasMessageContaining("a different reviewer must review it");

        var approved = lab.review(number, APPROVE, null, "reviewer");
        assertThat(approved.status()).isEqualTo(Sample.Status.APPROVED);
        assertThat(approved.approvedBy()).isEqualTo("reviewer");

        assertThatThrownBy(() -> lab.enterResult(number, "LOD", new BigDecimal("0.3"), "Late correction", "analyst"))
                .hasMessageContaining("its results are locked");
    }

    @Test
    void rejectionNeedsAReasonAndReturnsTheResultsForCorrection() {
        var number = completedSample();

        assertThatThrownBy(() -> lab.review(number, REJECT, null, "reviewer"))
                .hasMessageContaining("A reason is required to reject results");

        var rejected = lab.review(number, REJECT, "Assay looks low; check the standard", "reviewer");
        assertThat(rejected.status()).isEqualTo(Sample.Status.IN_TESTING);

        lab.enterResult(number, "ASSAY", new BigDecimal("99.6"), "Re-tested with a fresh standard", "analyst");
        assertThat(lab.audit(number)).extracting(AuditView::action)
                .endsWith("RESULTS_REJECTED", "RESULT_CHANGED");
    }

    @Test
    void importsInstrumentResultsAndReportsEveryBadRow() {
        var number = registeredSample();
        var csv = """
                sample_number,test_code,value
                %1$s,ASSAY,99.2
                %1$s,DISSOLUTION,86
                %1$s,LOD,abc
                S-999999,ASSAY,100.1
                %1$s,HARDNESS,50
                %1$s,ASSAY,99.4
                %1$s,LOD
                """.formatted(number);

        var report = lab.importResults(csv, "analyst");

        assertThat(report.imported()).isEqualTo(2);
        assertThat(report.errors()).containsExactly(
                new ImportError(4, "'abc' is not a number"),
                new ImportError(5, "Unknown sample S-999999"),
                new ImportError(6, "Sample %s has no test HARDNESS".formatted(number)),
                new ImportError(7, "Sample %s already has a result for ASSAY; correct it with a reason instead"
                        .formatted(number)),
                new ImportError(8, "Expected 3 columns, found 2"));
        assertThat(result(lab.sample(number), "ASSAY").value()).isEqualByComparingTo("99.2");
        assertThat(lab.audit(number)).filteredOn(e -> "RESULT_ENTERED".equals(e.action()))
                .extracting(AuditView::reason).containsOnly("Imported from instrument file");
    }

    @Test
    void theAuditTrailCannotBeEditedOrDeleted() {
        registeredSample();

        assertThatThrownBy(() -> jdbc.update("update audit_event set reason = 'edited'"))
                .hasMessageContaining("audit_event is append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit_event"))
                .hasMessageContaining("audit_event is append-only");
    }

    @Test
    void approvedSamplesGetAJasperReportsCertificateOfAnalysis() throws Exception {
        var number = completedSample();

        mvc.perform(get("/api/samples/{number}/certificate", number).with(httpBasic("analyst", "analyst")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Sample %s is not approved yet".formatted(number)));

        lab.review(number, APPROVE, null, "reviewer");

        var pdf = mvc.perform(get("/api/samples/{number}/certificate", number).with(httpBasic("analyst", "analyst")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        var text = transaction.execute(status -> certificateText(number));
        assertThat(text).contains("Certificate of Analysis", number, "B-2611", "Assay", "95.0 - 105.0", "NLT 80",
                "NMT 0.5", "99.0", "Pass", "The batch complies with the specification.", "by reviewer");
    }

    @Test
    void certificateNamesOutOfSpecificationResults() {
        var number = registeredSample();
        lab.enterResult(number, "ASSAY", new BigDecimal("93.4"), null, "analyst");
        lab.enterResult(number, "DISSOLUTION", new BigDecimal("85"), null, "analyst");
        lab.enterResult(number, "LOD", new BigDecimal("0.2"), null, "analyst");
        lab.review(number, APPROVE, "Approved for OOS investigation record", "reviewer");

        var text = transaction.execute(status -> certificateText(number));
        assertThat(text).contains("OOS", "The batch does not comply: 1 result(s) out of specification.");
    }

    @Test
    void theApiEnforcesRolesAndExplainsErrors() throws Exception {
        var number = registeredSample();

        mvc.perform(get("/api/samples/{number}", number))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/samples/{number}/review", number).with(httpBasic("analyst", "analyst"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/samples/{number}/results/ASSAY", number).with(httpBasic("reviewer", "reviewer"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":99.1}"))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/samples/{number}/results/ASSAY", number).with(httpBasic("analyst", "analyst"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":99.1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outOfSpec").value(false))
                .andExpect(jsonPath("$.results[0].enteredBy").value("analyst"));
        mvc.perform(put("/api/samples/{number}/results/ASSAY", number).with(httpBasic("analyst", "analyst"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":99.3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A reason is required to change an entered result"));

        var file = new MockMultipartFile("file", "results.csv", "text/csv",
                "sample_number,test_code,value\n%s,LOD,0.3\n".formatted(number).getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/results/import").file(file).with(httpBasic("analyst", "analyst")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    private String registeredSample() {
        var code = "PARA-" + UUID.randomUUID().toString().substring(0, 8);
        lab.createProduct(new ProductRequest(code, "Paracetamol 500 mg tablets", List.of(
                new SpecRequest("ASSAY", "Assay", "%", new BigDecimal("95.0"), new BigDecimal("105.0")),
                new SpecRequest("DISSOLUTION", "Dissolution (30 min)", "%", new BigDecimal("80"), null),
                new SpecRequest("LOD", "Loss on drying", "% w/w", null, new BigDecimal("0.5")))));
        return lab.registerSample(code, "B-2611", "analyst").number();
    }

    private String completedSample() {
        var number = registeredSample();
        lab.enterResult(number, "ASSAY", new BigDecimal("99.0"), null, "analyst");
        lab.enterResult(number, "DISSOLUTION", new BigDecimal("88"), null, "analyst");
        lab.enterResult(number, "LOD", new BigDecimal("0.2"), null, "analyst");
        return number;
    }

    private String certificateText(String number) {
        var sample = samples.findById(Sample.idOf(number).orElseThrow()).orElseThrow();
        try {
            return coaReport.fill(sample).getPages().stream()
                    .flatMap(page -> page.getElements().stream())
                    .filter(JRPrintText.class::isInstance)
                    .map(element -> ((JRPrintText) element).getFullText())
                    .collect(Collectors.joining("\n"));
        } catch (JRException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResultView result(SampleView sample, String testCode) {
        return sample.results().stream().filter(r -> r.testCode().equals(testCode)).findFirst().orElseThrow();
    }
}
