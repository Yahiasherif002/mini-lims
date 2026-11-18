package minilims;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findBySampleIdOrderById(Long sampleId);

    boolean existsBySampleIdAndUsernameAndActionIn(Long sampleId, String username, Collection<String> actions);
}
