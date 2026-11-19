package minilims;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
class TestResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ManyToOne(optional = false)
    Sample sample;

    String testCode;
    String testName;
    String unit;
    BigDecimal minLimit;
    BigDecimal maxLimit;
    BigDecimal value;
    Boolean outOfSpec;
    String enteredBy;
    Instant enteredAt;

    static TestResult from(SpecItem spec, Sample sample) {
        var result = new TestResult();
        result.sample = sample;
        result.testCode = spec.testCode;
        result.testName = spec.testName;
        result.unit = spec.unit;
        result.minLimit = spec.minLimit;
        result.maxLimit = spec.maxLimit;
        return result;
    }

    void record(BigDecimal newValue, String user) {
        value = newValue;
        outOfSpec = (minLimit != null && newValue.compareTo(minLimit) < 0)
                || (maxLimit != null && newValue.compareTo(maxLimit) > 0);
        enteredBy = user;
        enteredAt = Instant.now();
    }

    String specification() {
        if (minLimit != null && maxLimit != null) {
            return text(minLimit) + " - " + text(maxLimit);
        }
        return minLimit != null ? "NLT " + text(minLimit) : "NMT " + text(maxLimit);
    }

    static String text(BigDecimal number) {
        return number.stripTrailingZeros().toPlainString();
    }
}
