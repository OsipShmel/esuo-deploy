package honey.road.center.mp;

import honey.road.center.config.CenterProperties;
import honey.road.center.integration.HmacSigner;
import honey.road.center.nats.CenterNats;
import honey.road.center.network.BranchRegistry;
import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import honey.road.contracts.error.FieldViolation;
import honey.road.contracts.event.Subjects;
import honey.road.contracts.http.EsuoHeaders;
import honey.road.contracts.inbox.MpAppointmentMessage;
import io.nats.client.PublishOptions;
import io.nats.client.impl.Headers;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * MP webhook (sect 4.6, 13.2): HMAC signature with a ±5 min timestamp window, idempotency by {@code mp_appointment_id}
 * (same body → 202 with the current status, different body → 422), publish to {@code MP_INBOX} with
 * {@code Nats-Msg-Id = mp_appointment_id}. The message waits there while the branch is offline.
 */
@RestController
class MpWebhookController {
    private static final Logger log = LoggerFactory.getLogger(MpWebhookController.class);

    private final MpRequests requests;
    private final BranchRegistry registry;
    private final CenterNats nats;
    private final CenterProperties.Mp props;
    private final JsonMapper json;
    private final Clock clock;

    MpWebhookController(MpRequests requests, BranchRegistry registry, CenterNats nats, CenterProperties props,
                        JsonMapper json, Clock clock) {
        this.requests = requests; this.registry = registry; this.nats = nats; this.props = props.mp();
        this.json = json; this.clock = clock;
    }

    record Appointment(@Nullable String mpAppointmentId, @Nullable String branchId, @Nullable String serviceCode,
                       @Nullable Instant slotStart, @Nullable String clientCategory, @Nullable String clientRef) {
    }

    record Accepted(String mpAppointmentId, String status) {
    }

    record MpRequestView(String mpAppointmentId, String branchId, String status, boolean published,
                         @Nullable String displayNumber, @Nullable String reason, int callbackAttempts,
                         Instant createdAt, Instant updatedAt) {
    }

    /** The body is taken raw: the signature covers the exact bytes. */
    @Operation(summary = "MP appointment webhook",
            requestBody = @RequestBody(required = true, content = @Content(schema = @Schema(implementation = Appointment.class))))
    @PostMapping(value = "/api/v1/webhooks/mp/appointments", consumes = "application/json")
    ResponseEntity<Accepted> appointment(
            @RequestHeader(value = HmacSigner.TIMESTAMP, required = false) @Nullable String timestamp,
            @RequestHeader(value = HmacSigner.SIGNATURE, required = false) @Nullable String signature,
            HttpEntity<byte[]> request) {
        var body = request.getBody() == null ? new byte[0] : request.getBody();
        verify(timestamp, signature, body);
        var appointment = parse(body);
        if (!registry.exists(appointment.branchId())) {
            throw new EsuoException(ErrorCode.BRANCH_NOT_FOUND, "Отделение " + appointment.branchId() + " не зарегистрировано");
        }
        var id = appointment.mpAppointmentId();
        var hash = HmacSigner.sha256Hex(body);
        var now = clock.instant();
        var existing = requests.insertIfAbsent(id, appointment.branchId(), hash, new String(body, StandardCharsets.UTF_8), now);
        if (existing.isPresent() && !existing.get().bodyHash().equals(hash)) {
            throw new EsuoException(ErrorCode.IDEMPOTENCY_CONFLICT, "mp_appointment_id " + id + " уже принят с другим телом");
        }
        if (existing.isEmpty() || !existing.get().published()) {
            publish(appointment);
            requests.markPublished(id, now);
        }
        var status = existing.map(MpRequests.MpRequest::status).orElse("accepted");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Accepted(id, status));
    }

    @GetMapping("/api/v1/admin/mp/requests")
    List<MpRequestView> recent(@RequestParam(defaultValue = "50") int limit) {
        return requests.recent(Math.clamp(limit, 1, 500)).stream()
                .map(r -> new MpRequestView(r.mpAppointmentId(), r.branchId(), r.status(), r.published(), r.displayNumber(),
                        r.reason(), r.callbackAttempts(), r.createdAt(), r.updatedAt()))
                .toList();
    }

    private void verify(@Nullable String timestamp, @Nullable String signature, byte[] body) {
        long ts;
        try {
            ts = Long.parseLong(timestamp == null ? "" : timestamp.trim());
        } catch (NumberFormatException e) {
            throw new EsuoException(ErrorCode.UNAUTHENTICATED, "Нет или неверный " + HmacSigner.TIMESTAMP);
        }
        var skew = Duration.between(Instant.ofEpochSecond(ts), clock.instant()).abs();
        if (skew.compareTo(props.timestampTolerance()) > 0) {
            throw new EsuoException(ErrorCode.UNAUTHENTICATED, HmacSigner.TIMESTAMP + " вне допуска ±" + props.timestampTolerance().toMinutes() + " мин");
        }
        if (!HmacSigner.verify(props.webhookSecret(), timestamp.trim(), body, signature)) {
            throw new EsuoException(ErrorCode.UNAUTHENTICATED, "Неверная подпись");
        }
    }

    private Appointment parse(byte[] body) {
        Appointment a;
        try {
            a = json.readValue(body, Appointment.class);
        } catch (RuntimeException e) {
            throw new EsuoException(ErrorCode.VALIDATION_ERROR, "Некорректный JSON");
        }
        var errors = new ArrayList<FieldViolation>();
        if (a.mpAppointmentId() == null || a.mpAppointmentId().isBlank() || a.mpAppointmentId().length() > 128) {
            errors.add(new FieldViolation("mp_appointment_id", "Обязательно, до 128 символов"));
        }
        if (a.branchId() == null || !a.branchId().matches("[a-zA-Z0-9_-]{1,64}")) {
            errors.add(new FieldViolation("branch_id", "Обязательно"));
        }
        if (a.serviceCode() == null || a.serviceCode().isBlank()) { errors.add(new FieldViolation("service_code", "Обязательно")); }
        if (a.slotStart() == null) { errors.add(new FieldViolation("slot_start", "Обязательно, ISO-8601 UTC")); }
        if (!errors.isEmpty()) { throw new EsuoException(ErrorCode.VALIDATION_ERROR, "Проверьте поля записи", errors); }
        return a;
    }

    private void publish(Appointment a) {
        var connection = nats.connection().orElseThrow(() ->
                new EsuoException(ErrorCode.BRANCH_UNAVAILABLE, "Центр временно без связи с брокером, повторите запрос"));
        var message = new MpAppointmentMessage(a.mpAppointmentId(), a.serviceCode(), a.slotStart(),
                a.clientCategory() == null ? "default" : a.clientCategory(), a.clientRef());
        try {
            connection.jetStream().publish(Subjects.mpInbox(a.branchId()),
                    new Headers().add(EsuoHeaders.CONTENT_TYPE, "application/json"),
                    json.writeValueAsBytes(message),
                    PublishOptions.builder().messageId(a.mpAppointmentId()).streamTimeout(Duration.ofSeconds(5)).build());
        } catch (Exception e) {
            log.warn("mp {} not published to MP_INBOX: {}", a.mpAppointmentId(), e.toString());
            throw new EsuoException(ErrorCode.BRANCH_UNAVAILABLE, "Не удалось поставить запись в очередь, повторите запрос");
        }
    }
}
