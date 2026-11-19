package minilims;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = "lims.slow-call=0ms")
@ExtendWith(OutputCaptureExtension.class)
class ServiceTimingTest {

    @Autowired
    LabService lab;

    @Test
    void theAspectLogsSlowServiceCalls(CapturedOutput output) {
        assertThatThrownBy(() -> lab.sample("S-999999999")).isInstanceOf(ResponseStatusException.class);

        assertThat(output).contains("Slow call: LabService.sample(..) took");
    }
}
