package honey.road.node.api.admin;

import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import honey.road.node.config.NodeProperties;
import honey.road.node.planning.LoadPlanner;
import honey.road.node.planning.LoadStateRepository;
import honey.road.node.planning.PlanningSource;
import jakarta.annotation.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Branch load for the admin screen and the demo console (load-planning.md 6.1).
 *
 * <p>The response shape is fixed by the frontend, which was written ahead of this endpoint and shipped showing
 * a placeholder on 404 ({@code frontend/src/shared/api/load.ts}). Field names and nullability follow that file
 * rather than being invented here.
 */
@RestController
@RequestMapping("/api/v1/admin/load")
public class AdminLoadController {

    private final LoadPlanner planner;
    private final LoadStateRepository state;
    private final PlanningSource planning;
    private final NodeProperties node;
    private final Clock clock;

    public AdminLoadController(LoadPlanner planner, LoadStateRepository state, PlanningSource planning,
                               NodeProperties node, Clock clock) {
        this.planner = planner; this.state = state; this.planning = planning; this.node = node; this.clock = clock;
    }

    /** Тело {@code PUT /admin/load/profile}; публичный, потому что его собирают и тесты. */
    public record SetProfile(@Nullable String profile) {
    }

    @GetMapping
    public Map<String, Object> get() {
        return render(planner.snapshot());
    }

    /**
     * Day profile chosen by hand: {@code {"profile": "pension"}}, or {@code {"profile": null}} to go back to the
     * center's calendar. Needed when the payouts arrive on a different day than the calendar says — and for the
     * demo console, which switches the day profile on the fly.
     */
    @PutMapping("/profile")
    public Map<String, Object> putProfile(@RequestBody SetProfile body) {
        var code = body.profile() == null || body.profile().isBlank() ? null : body.profile().trim();
        var config = planning.current();
        if (code != null && !config.profiles().containsKey(code)) {
            throw new EsuoException(ErrorCode.VALIDATION_ERROR,
                    "Неизвестный профиль дня; опубликованы: " + String.join(", ", config.profiles().keySet()));
        }
        var now = clock.instant();
        state.manualProfile(code, now.atZone(ZoneId.of(node.timezone())).toLocalDate(), now);
        return render(planner.snapshot());
    }

    private static Map<String, Object> render(LoadPlanner.Snapshot s) {
        var out = new LinkedHashMap<String, Object>();
        out.put("generated_at", s.generatedAt());
        out.put("date", s.date());
        if (s.profile() != null) {
            out.put("profile", Map.of("code", s.profile().profile().code(),
                    "name", s.profile().profile().name(), "source", s.profile().source()));
        }
        out.put("level", s.level());
        out.put("level_since", s.levelSince());
        out.put("clear_time_min", s.clearTimeMin());
        out.put("ratio_now", s.ratioNow());
        out.put("windows", Map.of("open", s.windows().open(), "planned", s.windows().planned(),
                "needed", s.windows().needed()));
        var causes = new ArrayList<Map<String, Object>>();
        for (var cause : s.causes()) {
            causes.add(Map.of("code", cause.code(), "value", cause.value(), "threshold", cause.threshold()));
        }
        out.put("causes", causes);
        out.put("forecast", forecast(s));
        // Секция опускается целиком, когда спросить не о чем: экран тогда честно говорит, что узел
        // не сообщает основу прогноза, вместо пустой «версии».
        if (s.modelVersion() != null) {
            out.put("duration_model", Map.of("version", s.modelVersion(), "basis", s.modelBasis()));
        }
        return out;
    }

    private static List<Map<String, Object>> forecast(LoadPlanner.Snapshot s) {
        var rows = new ArrayList<Map<String, Object>>();
        for (var hour : s.forecast()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("hour_start", hour.hourStart());
            row.put("arrivals", round(hour.arrivals()));
            row.put("booked", hour.booked());
            row.put("work_min", round(hour.workMin()));
            row.put("capacity_min", round(hour.capacityMin()));
            row.put("ratio", round(hour.ratio()));
            row.put("level", hour.level());
            row.put("booking_quota", hour.bookingQuota());
            rows.add(row);
        }
        return rows;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
