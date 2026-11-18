package minilims;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;
import org.springframework.stereotype.Component;

@Component
class CoaReport {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    private final JasperReport template;

    CoaReport() throws JRException {
        try (var jrxml = getClass().getResourceAsStream("/reports/coa.jrxml")) {
            template = JasperCompileManager.compileReport(jrxml);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    JasperPrint fill(Sample sample) throws JRException {
        long outOfSpec = sample.results.stream().filter(r -> r.outOfSpec).count();
        var parameters = new HashMap<String, Object>();
        parameters.put("sampleNumber", sample.number());
        parameters.put("product", sample.product.code + " - " + sample.product.name);
        parameters.put("batchNumber", sample.batchNumber);
        parameters.put("registered", format(sample.registeredAt) + " by " + sample.registeredBy);
        parameters.put("approved", format(sample.approvedAt) + " by " + sample.approvedBy);
        parameters.put("conclusion", outOfSpec == 0
                ? "The batch complies with the specification."
                : "The batch does not comply: %d result(s) out of specification.".formatted(outOfSpec));
        List<Map<String, ?>> rows = sample.results.stream()
                .<Map<String, ?>>map(r -> Map.of(
                        "test", r.testName,
                        "unit", r.unit,
                        "specification", r.specification(),
                        "result", r.value.toPlainString(),
                        "status", r.outOfSpec ? "OOS" : "Pass"))
                .toList();
        return JasperFillManager.fillReport(template, parameters, new JRMapCollectionDataSource(rows));
    }

    byte[] pdf(Sample sample) {
        try {
            return JasperExportManager.exportReportToPdf(fill(sample));
        } catch (JRException e) {
            throw new IllegalStateException("Could not render the certificate for " + sample.number(), e);
        }
    }

    private static String format(Instant instant) {
        return TIME.format(instant);
    }
}
