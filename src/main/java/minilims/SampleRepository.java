package minilims;

import org.springframework.data.jpa.repository.JpaRepository;

interface SampleRepository extends JpaRepository<Sample, Long> {
}
