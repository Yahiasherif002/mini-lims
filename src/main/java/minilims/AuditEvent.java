package minilims;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    Long sampleId;
    String testCode;
    String action;
    String oldValue;
    String newValue;
    String username;
    String reason;
    Instant at;

    AuditEvent() {
    }

    AuditEvent(Long sampleId, String testCode, String action, String oldValue, String newValue, String username,
               String reason) {
        this.sampleId = sampleId;
        this.testCode = testCode;
        this.action = action;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.username = username;
        this.reason = reason;
        this.at = Instant.now();
    }
}
