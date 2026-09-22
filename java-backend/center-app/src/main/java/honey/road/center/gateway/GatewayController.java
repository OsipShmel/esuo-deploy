package honey.road.center.gateway;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
* Public API of the center for external clients (docs, ection 9.5): HTTP → NATS request-reply bridge.
* Validation rules for the branch node analogous to its REST API.
*/
@RestController
@RequestMapping("/api/v1/branches/{branch}")
public class GatewayController {
    private final BranchRpcClient rpc;
    private final RpcResponses responses;

    public GatewayController(BranchRpcClient rpc, RpcResponses responses) {
        this.rpc = rpc;
        this.responses = responses;
    }

    @GetMapping("/info")
    public ResponseEntity<Object> info(@PathVariable String branch) {
        return responses.of(rpc.call(branch, "branch.info", null, null));
    }

    @GetMapping("/slots")
    public ResponseEntity<Object> slots(@PathVariable String branch, @RequestParam("service_id") UUID serviceId,
                                        @RequestParam String date) {
        return responses.of(rpc.call(branch, "slots.list", Map.of("service_id", serviceId, "date", date), null));
    }

    @GetMapping("/booking-limits")
    public ResponseEntity<Object> bookingLimits(@PathVariable String branch, @RequestParam String date) {
        return responses.of(rpc.call(branch, "booking.limits", Map.of("date", date), null));
    }

    @PostMapping("/appointments")
    public ResponseEntity<Object> appointment(@PathVariable String branch, @RequestHeader("Idempotency-Key") String key,
                                              @RequestBody Map<String, Object> body) {
        return responses.of(rpc.call(branch, "appointment.create", body, key));
    }

    @PostMapping("/qr-tickets")
    public ResponseEntity<Object> qr(@PathVariable String branch, @RequestHeader("Idempotency-Key") String key,
                                     @RequestBody Map<String, Object> body) {
        return responses.of(rpc.call(branch, "qr.issue", body, key));
    }

    @GetMapping("/tickets/{token}")
    public ResponseEntity<Object> ticket(@PathVariable String branch, @PathVariable UUID token) {
        return responses.of(rpc.call(branch, "ticket.get", Map.of("token", token), null));
    }

    @PostMapping("/tickets/{token}/cancel")
    public ResponseEntity<Object> cancel(@PathVariable String branch, @PathVariable UUID token) {
        return responses.of(rpc.call(branch, "ticket.cancel", Map.of("token", token), null));
    }

    @PostMapping("/tickets/{token}/postpone")
    public ResponseEntity<Object> postpone(@PathVariable String branch, @PathVariable UUID token,
                                           @RequestBody Map<String, Object> body) {
        return responses.of(rpc.call(branch, "ticket.postpone", with(body, token), null));
    }

    @PostMapping("/tickets/{token}/check-in")
    public ResponseEntity<Object> checkIn(@PathVariable String branch, @PathVariable UUID token,
                                          @RequestBody Map<String, Object> body) {
        return responses.of(rpc.call(branch, "ticket.checkin", with(body, token), null));
    }

    private static Map<String, Object> with(Map<String, Object> body, UUID token) {
        var m = new LinkedHashMap<String, Object>(body == null ? Map.of() : body);
        m.put("token", token);
        return m;
    }
}
