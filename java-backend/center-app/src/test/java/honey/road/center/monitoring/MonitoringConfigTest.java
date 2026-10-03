package honey.road.center.monitoring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rules file (design 6.4): the bundled one loads, a mistake stops the start with the rule id in the message. */
class MonitoringConfigTest {

    private static MonitoringConfig bind(Resource yaml) throws Exception {
        var sources = new YamlPropertySourceLoader().load("rules", yaml);
        return new Binder(ConfigurationPropertySources.from(sources)).bind("esuo.center.monitoring", MonitoringConfig.class).get();
    }

    private static MonitoringConfig bind(String rule) throws Exception {
        return bind(new ByteArrayResource(("""
                esuo:
                  center:
                    monitoring:
                      rules:
                        - %s
                """.formatted(rule)).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void bundledFileLoads() throws Exception {
        var config = bind(new ClassPathResource("monitoring-rules.yml"));

        assertThat(config.evaluateEvery()).isEqualTo(Duration.ofSeconds(15));
        assertThat(config.demoDurationScale()).isEqualTo(0.1);
        assertThat(config.rules()).extracting(MonitoringConfig.Rule::id)
                .containsExactly("sys.cpu", "sys.slow_api", "sys.db_pool", "sys.disk", "sys.link", "sys.clock", "sys.restart");
        assertThat(config.rules().getFirst().holdFor()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void unknownKindFailsWithRuleId() {
        assertThatThrownBy(() -> bind("{id: sys.x, kind: average, metric: sys.cpu_system, op: '>', value: 1, for: 1m, severity: info, text: t}"))
                .hasStackTraceContaining("sys.x").hasStackTraceContaining("average");
    }

    @Test
    void unknownMetricFails() {
        assertThatThrownBy(() -> bind("{id: sys.y, kind: threshold, metric: sys.nope, op: '>', value: 1, for: 1m, severity: info, text: t}"))
                .hasStackTraceContaining("sys.y").hasStackTraceContaining("sys.nope");
    }

    @Test
    void thresholdWithoutForFails() {
        assertThatThrownBy(() -> bind("{id: sys.z, kind: threshold, metric: sys.cpu_system, op: '>', value: 1, severity: info, text: t}"))
                .hasStackTraceContaining("sys.z").hasStackTraceContaining("for");
    }

    @Test
    void unknownOperatorFails() {
        assertThatThrownBy(() -> bind("{id: sys.w, kind: threshold, metric: sys.cpu_system, op: '=>', value: 1, for: 1m, severity: info, text: t}"))
                .hasStackTraceContaining("sys.w").hasStackTraceContaining("=>");
    }
}
