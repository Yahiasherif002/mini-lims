package minilims;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class LabService {

    public enum Decision { APPROVE, REJECT }

    public record SpecRequest(@NotBlank String testCode, @NotBlank String testName, @NotBlank String unit,
                              BigDecimal min, BigDecimal max) {
    }

    public record ProductRequest(@NotBlank String code, @NotBlank String name,
                                 @NotEmpty List<@Valid SpecRequest> specs) {
    }

    public record ResultView(String testCode, String testName, String unit, String specification, BigDecimal value,
                             Boolean outOfSpec, String enteredBy, Instant enteredAt) {
    }

    public record SampleView(String number, String productCode, String productName, String batchNumber,
                             Sample.Status status, String registeredBy, Instant registeredAt, String approvedBy,
                             Instant approvedAt, List<ResultView> results) {
    }

    public record AuditView(String testCode, String action, String oldValue, String newValue, String username,
                            String reason, Instant at) {
    }

    public record ImportError(int line, String message) {
    }

    public record ImportReport(int imported, List<ImportError> errors) {
    }

    private static final List<String> RESULT_ACTIONS = List.of("RESULT_ENTERED", "RESULT_CHANGED");

    private final ProductRepository products;
    private final SampleRepository samples;
    private final AuditEventRepository audit;
    private final CoaReport coaReport;

    LabService(ProductRepository products, SampleRepository samples, AuditEventRepository audit, CoaReport coaReport) {
        this.products = products;
        this.samples = samples;
        this.audit = audit;
        this.coaReport = coaReport;
    }

    public void createProduct(ProductRequest request) {
        if (products.findByCode(request.code()).isPresent()) {
            throw error(HttpStatus.CONFLICT, "Product %s already exists".formatted(request.code()));
        }
        var product = new Product();
        product.code = request.code();
        product.name = request.name();
        var testCodes = new HashSet<String>();
        for (var spec : request.specs()) {
            if (!testCodes.add(spec.testCode())) {
                throw error(HttpStatus.BAD_REQUEST, "Test %s is listed twice".formatted(spec.testCode()));
            }
            if (spec.min() == null && spec.max() == null) {
                throw error(HttpStatus.BAD_REQUEST, "Test %s needs a minimum or a maximum limit".formatted(spec.testCode()));
            }
            if (spec.min() != null && spec.max() != null && spec.min().compareTo(spec.max()) > 0) {
                throw error(HttpStatus.BAD_REQUEST, "Test %s has a minimum above its maximum".formatted(spec.testCode()));
            }
            var item = new SpecItem();
            item.product = product;
            item.testCode = spec.testCode();
            item.testName = spec.testName();
            item.unit = spec.unit();
            item.minLimit = spec.min();
            item.maxLimit = spec.max();
            product.specs.add(item);
        }
        products.save(product);
    }

    public SampleView registerSample(String productCode, String batchNumber, String user) {
        var product = products.findByCode(productCode)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Unknown product " + productCode));
        var sample = new Sample();
        sample.product = product;
        sample.batchNumber = batchNumber;
        sample.registeredBy = user;
        sample.registeredAt = Instant.now();
        product.specs.forEach(spec -> sample.results.add(TestResult.from(spec, sample)));
        samples.save(sample);
        log(sample, null, "SAMPLE_REGISTERED", null, null, user, "Registered for batch " + batchNumber);
        return view(sample);
    }

    @Transactional(readOnly = true)
    public SampleView sample(String number) {
        return view(find(number));
    }

    public SampleView enterResult(String number, String testCode, BigDecimal value, String reason, String user) {
        var sample = find(number);
        record(sample, result(sample, testCode), value, reason, user);
        return view(sample);
    }

    public ImportReport importResults(String csv, String user) {
        var lines = csv.replace("﻿", "").lines().toList();
        if (lines.isEmpty()) {
            throw error(HttpStatus.BAD_REQUEST, "The file is empty");
        }
        var header = Arrays.stream(lines.getFirst().split(",")).map(h -> h.trim().toLowerCase()).toList();
        int sampleColumn = header.indexOf("sample_number");
        int testColumn = header.indexOf("test_code");
        int valueColumn = header.indexOf("value");
        if (sampleColumn < 0 || testColumn < 0 || valueColumn < 0) {
            throw error(HttpStatus.BAD_REQUEST, "The header must contain sample_number, test_code and value");
        }
        var errors = new ArrayList<ImportError>();
        int imported = 0;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            var columns = lines.get(i).split(",", -1);
            try {
                if (columns.length != header.size()) {
                    throw error(HttpStatus.BAD_REQUEST,
                            "Expected %d columns, found %d".formatted(header.size(), columns.length));
                }
                var sample = find(columns[sampleColumn].trim());
                var value = number(columns[valueColumn].trim());
                var result = result(sample, columns[testColumn].trim());
                if (result.value != null) {
                    throw error(HttpStatus.CONFLICT, "Sample %s already has a result for %s; correct it with a reason instead"
                            .formatted(sample.number(), result.testCode));
                }
                record(sample, result, value, "Imported from instrument file", user);
                imported++;
            } catch (ResponseStatusException e) {
                errors.add(new ImportError(i + 1, e.getReason()));
            }
        }
        return new ImportReport(imported, errors);
    }

    public SampleView review(String number, Decision decision, String reason, String user) {
        var sample = find(number);
        if (sample.status == Sample.Status.APPROVED) {
            throw error(HttpStatus.CONFLICT, "Sample %s is already approved".formatted(sample.number()));
        }
        long missing = sample.results.stream().filter(r -> r.value == null).count();
        if (missing > 0) {
            throw error(HttpStatus.CONFLICT,
                    "%d of %d results are still missing".formatted(missing, sample.results.size()));
        }
        if (audit.existsBySampleIdAndUsernameAndActionIn(sample.id, user, RESULT_ACTIONS)) {
            throw error(HttpStatus.CONFLICT, "You entered results on this sample, so a different reviewer must review it");
        }
        if (decision == Decision.REJECT) {
            if (isBlank(reason)) {
                throw error(HttpStatus.BAD_REQUEST, "A reason is required to reject results");
            }
            log(sample, null, "RESULTS_REJECTED", null, null, user, reason);
        } else {
            sample.status = Sample.Status.APPROVED;
            sample.approvedBy = user;
            sample.approvedAt = Instant.now();
            log(sample, null, "SAMPLE_APPROVED", null, null, user, isBlank(reason) ? "Approved" : reason);
        }
        return view(sample);
    }

    @Transactional(readOnly = true)
    public List<AuditView> audit(String number) {
        return audit.findBySampleIdOrderById(find(number).id).stream()
                .map(e -> new AuditView(e.testCode, e.action, e.oldValue, e.newValue, e.username, e.reason, e.at))
                .toList();
    }

    @Transactional(readOnly = true)
    public byte[] certificate(String number) {
        var sample = find(number);
        if (sample.status != Sample.Status.APPROVED) {
            throw error(HttpStatus.CONFLICT, "Sample %s is not approved yet".formatted(sample.number()));
        }
        return coaReport.pdf(sample);
    }

    private void record(Sample sample, TestResult result, BigDecimal value, String reason, String user) {
        if (sample.status == Sample.Status.APPROVED) {
            throw error(HttpStatus.CONFLICT, "Sample %s is approved; its results are locked".formatted(sample.number()));
        }
        var old = result.value;
        if (old != null && isBlank(reason)) {
            throw error(HttpStatus.BAD_REQUEST, "A reason is required to change an entered result");
        }
        result.record(value, user);
        log(sample, result.testCode, old == null ? "RESULT_ENTERED" : "RESULT_CHANGED",
                old == null ? null : old.toPlainString(), value.toPlainString(), user,
                isBlank(reason) ? "Initial entry" : reason);
    }

    private Sample find(String number) {
        return Sample.idOf(number).flatMap(samples::findById)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Unknown sample " + number));
    }

    private static TestResult result(Sample sample, String testCode) {
        return sample.results.stream().filter(r -> r.testCode.equals(testCode)).findFirst()
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND,
                        "Sample %s has no test %s".formatted(sample.number(), testCode)));
    }

    private static BigDecimal number(String text) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw error(HttpStatus.BAD_REQUEST, "'%s' is not a number".formatted(text));
        }
    }

    private void log(Sample sample, String testCode, String action, String oldValue, String newValue, String user,
                     String reason) {
        audit.save(new AuditEvent(sample.id, testCode, action, oldValue, newValue, user, reason));
    }

    private static SampleView view(Sample s) {
        var results = s.results.stream()
                .map(r -> new ResultView(r.testCode, r.testName, r.unit, r.specification(), r.value, r.outOfSpec,
                        r.enteredBy, r.enteredAt))
                .toList();
        return new SampleView(s.number(), s.product.code, s.product.name, s.batchNumber, s.status, s.registeredBy,
                s.registeredAt, s.approvedBy, s.approvedAt, results);
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
