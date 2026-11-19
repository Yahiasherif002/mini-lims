package minilims;

import java.time.Duration;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Aspect
@Component
class ServiceTiming {

    private static final Logger log = LoggerFactory.getLogger(ServiceTiming.class);

    private final Duration slow;

    ServiceTiming(@Value("${lims.slow-call:500ms}") Duration slow) {
        this.slow = slow;
    }

    @Around("execution(public * minilims.LabService.*(..))")
    Object time(ProceedingJoinPoint call) throws Throwable {
        long start = System.nanoTime();
        try {
            return call.proceed();
        } finally {
            var took = Duration.ofNanos(System.nanoTime() - start);
            if (took.compareTo(slow) >= 0) {
                log.warn("Slow call: {} took {} ms", call.getSignature().toShortString(), took.toMillis());
            }
        }
    }
}
