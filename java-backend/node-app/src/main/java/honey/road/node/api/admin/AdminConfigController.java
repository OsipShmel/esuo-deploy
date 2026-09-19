package honey.road.node.api.admin;

import honey.road.node.queue.application.BranchConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Конфигурация отделения для руководителя (раздел 9.3): центральная версия, локальные переопределения, границы.
 * Доступ только {@code BRANCH_ADMIN} — правило в {@code SecurityConfig} для {@code /api/v1/admin/**}.
 */
@RestController
@RequestMapping("/api/v1/admin/config")
public class AdminConfigController {
    private final BranchConfigService config;

    public AdminConfigController(BranchConfigService config) {
        this.config = config;
    }

    @GetMapping
    public Map<String, Object> get() {
        return render(config.view());
    }

    /** Тело — объект переопределений в snake_case, например {@code {"slack_min": 8}}; {@code {}} снимает все. */
    @PutMapping
    public Map<String, Object> put(@RequestBody JsonNode overrides) {
        return render(config.putOverrides(overrides));
    }

    private static Map<String, Object> render(BranchConfigService.View v) {
        var out = new LinkedHashMap<String, Object>();
        out.put("central_version", v.centralVersion());
        out.put("effective_version", v.effectiveVersion());
        out.put("central", v.central());
        out.put("local_overrides", v.localOverrides());
        out.put("bounds", v.bounds());
        out.put("effective", v.effective());
        return out;
    }
}
