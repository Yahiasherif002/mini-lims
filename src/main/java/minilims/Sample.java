package minilims;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Entity
class Sample {

    enum Status { IN_TESTING, APPROVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ManyToOne(optional = false)
    Product product;

    String batchNumber;

    @Enumerated(EnumType.STRING)
    Status status = Status.IN_TESTING;

    String registeredBy;
    Instant registeredAt;
    String approvedBy;
    Instant approvedAt;

    @OneToMany(mappedBy = "sample", cascade = CascadeType.ALL)
    @OrderBy("id")
    List<TestResult> results = new ArrayList<>();

    String number() {
        return "S-%06d".formatted(id);
    }

    static Optional<Long> idOf(String number) {
        return number.matches("S-\\d{6,18}") ? Optional.of(Long.parseLong(number.substring(2))) : Optional.empty();
    }
}
