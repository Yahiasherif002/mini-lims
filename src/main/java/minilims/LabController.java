package minilims;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import minilims.LabService.AuditView;
import minilims.LabService.Decision;
import minilims.LabService.ImportReport;
import minilims.LabService.ProductRequest;
import minilims.LabService.SampleView;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class LabController {

    public record SampleRequest(@NotBlank String productCode, @NotBlank String batchNumber) {
    }

    public record ResultRequest(@NotNull BigDecimal value, String reason) {
    }

    public record ReviewRequest(@NotNull Decision decision, String reason) {
    }

    private final LabService lab;

    LabController(LabService lab) {
        this.lab = lab;
    }

    @PostMapping("/products")
    @PreAuthorize("hasRole('REVIEWER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductRequest createProduct(@Valid @RequestBody ProductRequest request) {
        lab.createProduct(request);
        return request;
    }

    @PostMapping("/samples")
    @PreAuthorize("hasRole('ANALYST')")
    @ResponseStatus(HttpStatus.CREATED)
    public SampleView registerSample(@Valid @RequestBody SampleRequest request, Principal user) {
        return lab.registerSample(request.productCode(), request.batchNumber(), user.getName());
    }

    @GetMapping("/samples/{number}")
    public SampleView sample(@PathVariable String number) {
        return lab.sample(number);
    }

    @PutMapping("/samples/{number}/results/{testCode}")
    @PreAuthorize("hasRole('ANALYST')")
    public SampleView enterResult(@PathVariable String number, @PathVariable String testCode,
                                  @Valid @RequestBody ResultRequest request, Principal user) {
        return lab.enterResult(number, testCode, request.value(), request.reason(), user.getName());
    }

    @PostMapping(path = "/results/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ANALYST')")
    public ImportReport importResults(@RequestParam MultipartFile file, Principal user) throws IOException {
        return lab.importResults(new String(file.getBytes(), StandardCharsets.UTF_8), user.getName());
    }

    @PostMapping("/samples/{number}/review")
    @PreAuthorize("hasRole('REVIEWER')")
    public SampleView review(@PathVariable String number, @Valid @RequestBody ReviewRequest request, Principal user) {
        return lab.review(number, request.decision(), request.reason(), user.getName());
    }

    @GetMapping("/samples/{number}/audit")
    public List<AuditView> audit(@PathVariable String number) {
        return lab.audit(number);
    }

    @GetMapping("/samples/{number}/certificate")
    public ResponseEntity<byte[]> certificate(@PathVariable String number) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"CoA-%s.pdf\"".formatted(number))
                .body(lab.certificate(number));
    }
}
