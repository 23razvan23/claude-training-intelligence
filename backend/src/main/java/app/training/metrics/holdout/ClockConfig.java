package app.training.metrics.holdout;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The system clock, as a bean so tests can fix "today" (the holdout's interim/final state depends on it). */
@Configuration(proxyBeanMethods = false)
class ClockConfig {
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
