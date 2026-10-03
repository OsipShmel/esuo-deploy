package honey.road.node.demo;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /admin/demo/log} — see {@link DemoLog}. */
@RestController
class DemoLogController {
    private final DemoLog demoLog;

    DemoLogController(DemoLog demoLog) { this.demoLog = demoLog; }

    record LogRequest(String level, String message) {
    }

    @PostMapping("/api/v1/admin/demo/log")
    ResponseEntity<Void> log(@RequestBody LogRequest body) {
        demoLog.write(body.level(), body.message());
        return ResponseEntity.noContent().build();
    }
}
