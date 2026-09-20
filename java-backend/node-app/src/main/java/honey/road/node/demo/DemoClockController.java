package honey.road.node.demo;

import jakarta.annotation.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Demo clock API (load-planning.md 7.1): {@code GET /public/demo/clock} always ({@code enabled=false} and real time
 * outside demo mode); {@code PUT /admin/demo/clock} only with {@code DEMO_MODE=true}.
 */
@RestController
class DemoClockController {
    private final DemoClockService demo;

    DemoClockController(DemoClockService demo) { this.demo = demo; }

    record SetClock(@Nullable Instant now, @Nullable Double rate) {
    }

    @GetMapping("/api/v1/public/demo/clock")
    DemoClockService.State get() {
        return demo.state();
    }

    @PutMapping("/api/v1/admin/demo/clock")
    DemoClockService.State put(@RequestBody SetClock body) {
        return demo.set(body.now(), body.rate());
    }
}
