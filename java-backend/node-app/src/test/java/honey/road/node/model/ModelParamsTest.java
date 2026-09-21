package honey.road.node.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The Java side of the model must predict exactly what the trainer predicts.
 *
 * <p>Both read the same two files, committed in {@code src/test/resources/model/} and produced by
 * {@code uv run esuo-ml fixtures}: {@code model-params.json} (the published section) and {@code holdout.csv}
 * (a readable sample the trainer never saw). The Python side checks the same pair in
 * {@code tests/test_fixtures.py}, so the two implementations cannot drift apart without a red test on one of them.
 * No Python is needed to run this.
 */
class ModelParamsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path FIXTURES = Path.of("src/test/resources/model");

    private static ModelParams params() {
        var tree = JSON.readTree(read("model-params.json"));
        return ModelParams.of(tree.get("model"));
    }

    private record Row(String serviceCode, String category, String employeeId, int hour, String windowId) {
    }

    private static List<Row> holdout() {
        var lines = read("holdout.csv").lines().toList();
        var header = List.of(lines.get(0).split(","));
        var rows = new ArrayList<Row>();
        for (var line : lines.subList(1, lines.size())) {
            var cells = line.split(",");
            rows.add(new Row(cells[header.indexOf("service_code")], cells[header.indexOf("client_category")],
                    cells[header.indexOf("employee_id")], Integer.parseInt(cells[header.indexOf("hour")]),
                    cells[header.indexOf("window_id")]));
        }
        return rows;
    }

    private static String read(String name) {
        try {
            return Files.readString(FIXTURES.resolve(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("нет эталона " + name + "; соберите: uv run esuo-ml fixtures", e);
        }
    }

    @Test
    @DisplayName("прогноз Java совпадает с прогнозом обучателя на общей выборке")
    void matchesTheTrainerOnEveryFixtureRow() {
        var params = params();
        assertThat(params.usable()).isTrue();

        for (var row : holdout()) {
            var prediction = params.predict(row.serviceCode(), row.category(), row.employeeId(), row.hour(),
                    row.windowId());
            assertThat(prediction).as("нет прогноза для %s", row).isNotNull();
            // Обе стороны считают по одной формуле из ml-model.md 3.2; расхождение допустимо только
            // на округлении до миллисекунд.
            assertThat(prediction.p50().toMillis() / 1000.0)
                    .as("p50 для %s", row)
                    .isCloseTo(expected(row, "p50"), within(0.01));
            assertThat(prediction.mean().toMillis() / 1000.0)
                    .as("mean для %s", row)
                    .isCloseTo(expected(row, "mean"), within(0.01));
        }
    }

    /** Reference value computed straight from the formula, independently of {@link ModelParams}. */
    private static double expected(Row row, String point) {
        var section = JSON.readTree(read("model-params.json")).get("model");
        double m = section.get("mu").asDouble() + coefficient(section, "a", row.serviceCode());
        m += coefficient(section, "b", row.serviceCode() + "|" + row.category());
        m += coefficient(section, "e", row.employeeId());
        m += coefficient(section, "g", row.employeeId() + "|" + row.serviceCode());
        m += coefficient(section, "h", String.valueOf(row.hour()));
        double sigma = sigma(section, row.serviceCode());
        return "p50".equals(point) ? Math.exp(m) : Math.exp(m + sigma * sigma / 2);
    }

    private static double coefficient(JsonNode section, String group, String key) {
        var node = section.get(group);
        return node != null && node.hasNonNull(key) ? node.get(key).asDouble() : 0.0;
    }

    private static double sigma(JsonNode section, String serviceCode) {
        var node = section.get("sigma");
        if (node != null && node.hasNonNull(serviceCode)) {
            return node.get(serviceCode).asDouble();
        }
        return section.hasNonNull("sigma_default") ? section.get("sigma_default").asDouble() : 0.35;
    }

    // ------------------------------------------------------------------ свойства формулы

    @Test
    @DisplayName("средняя больше медианы: суммы работы нельзя складывать из медиан")
    void meanIsAboveMedianByTheLogNormalCorrection() {
        var params = params();
        var row = holdout().get(0);
        var prediction = params.predict(row.serviceCode(), row.category(), row.employeeId(), row.hour(), null);

        assertThat(prediction.mean()).isGreaterThan(prediction.p50());
        assertThat(prediction.p90()).isGreaterThan(prediction.mean());
        // exp(sigma^2/2) при sigma = 0,35 — около 6%.
        assertThat((double) prediction.mean().toMillis() / prediction.p50().toMillis())
                .isCloseTo(1.06, within(0.03));
    }

    @Test
    @DisplayName("незнакомый сотрудник обслуживается «как все», основа — model_partial")
    void unknownEmployeeIsServedAsAverage() {
        var params = params();
        var row = holdout().get(0);
        var unknown = params.predict(row.serviceCode(), row.category(), "00000000-0000-4000-8000-ffffffffffff",
                row.hour(), null);
        var anyone = params.predict(row.serviceCode(), row.category(), null, row.hour(), null);

        assertThat(unknown.basis()).isEqualTo(DurationModel.Prediction.BASIS_MODEL_PARTIAL);
        assertThat(unknown.p50()).isEqualTo(anyone.p50());
    }

    @Test
    @DisplayName("незнакомая услуга: модель отказывается, вызывающий уходит на каскад")
    void unknownServiceYieldsNoPrediction() {
        assertThat(params().predict("no-such-service", "default", null, 11, null)).isNull();
    }

    @Test
    @DisplayName("пустая и выключенная секция не дают прогноза")
    void absentOrDisabledSectionIsNotUsable() {
        assertThat(ModelParams.of(null).usable()).isFalse();
        assertThat(ModelParams.of(JSON.readTree("{}")).usable()).isFalse();

        var disabled = JSON.readTree(read("model-params.json")).get("model").deepCopy();
        ((ObjectNode) disabled).put("enabled", false);
        var params = ModelParams.of(disabled);
        assertThat(params.usable()).isFalse();
        assertThat(params.predict("parcel", "default", null, 11, null)).isNull();
    }

    @Test
    @DisplayName("секция без counts не применяется: нечем отличить известную группу от незнакомой")
    void sectionWithoutCountsIsIgnored() {
        var section = JSON.readTree("{\"version\":\"dm-x\",\"mu\":5.5,\"a\":{\"parcel\":-0.2}}");
        assertThat(ModelParams.of(section).usable()).isFalse();
    }
}
