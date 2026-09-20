package honey.road.node.messaging.rpc;

import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import honey.road.contracts.error.FieldViolation;
import honey.road.contracts.rpc.RpcReply;
import honey.road.node.api.publicapi.PublicTicketController;
import honey.road.node.config.JsonCodec;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;
import honey.road.node.queue.application.TicketQueries;

/**
 * RPC operations (sect 10.1): the same public use cases as the node REST API; the center is just transport.
 */
@Component
public class RpcOperations {
    private final PublicTicketController api;
    private final TicketQueries queries;
    private final JsonCodec json;
    private final Validator validator;

    public RpcOperations(PublicTicketController api, TicketQueries queries,
                         JsonCodec json, Validator validator) {
        this.api = api;
        this.queries = queries;
        this.json = json;
        this.validator = validator;
    }

    public record TokenRequest(UUID token) { }
    public record MpRequest(String mpAppointmentId) { }
    public record SlotsRequest(UUID serviceId, LocalDate date) { }
    public record PostponeRequest(UUID token, Integer minutes) { }
    public record CheckInRequest(UUID token, String qrCode) { }

    public RpcReply<?> invoke(String op, String body, String idempotencyKey) {
        return switch (op) {
            case "branch.info" -> RpcReply.ok(200, api.branch());
            case "slots.list" -> {
                var r = read(body, SlotsRequest.class);
                require(r.serviceId() != null && r.date() != null, "service_id и date обязательны");
                yield RpcReply.ok(200, api.slots(r.serviceId(), r.date()));
            }
            case "appointment.create" -> RpcReply.ok(201, api.appointment(key(idempotencyKey),
                    valid(read(body, PublicTicketController.AppointmentRequest.class))));
            case "qr.issue" -> RpcReply.ok(201, api.qr(key(idempotencyKey),
                    valid(read(body, PublicTicketController.QrRequest.class))));
            case "ticket.get" -> RpcReply.ok(200, api.get(token(body)));
            case "ticket.by_mp" -> {
                var r = read(body, MpRequest.class);
                require(r.mpAppointmentId() != null && !r.mpAppointmentId().isBlank(), "mp_appointment_id обязателен");
                yield RpcReply.ok(200, queries.byMpAppointment(r.mpAppointmentId()));
            }
            case "ticket.cancel" -> RpcReply.ok(200, api.cancel(token(body)));
            case "ticket.postpone" -> {
                var r = read(body, PostponeRequest.class);
                require(r.token() != null, "token обязателен");
                yield RpcReply.ok(200, api.postpone(r.token(), valid(new PublicTicketController.PostponeRequest(r.minutes()))));
            }
            case "ticket.checkin" -> {
                var r = read(body, CheckInRequest.class);
                require(r.token() != null, "token обязателен");
                yield RpcReply.ok(200, api.checkIn(r.token(), valid(new PublicTicketController.CheckInRequest(r.qrCode()))));
            }
            default -> throw new EsuoException(ErrorCode.NOT_FOUND, "Неизвестная операция " + op);
        };
    }

    private UUID token(String body) {
        var r = read(body, TokenRequest.class);
        require(r.token() != null, "token обязателен");
        return r.token();
    }

    private <T> T read(String body, Class<T> type) {
        try {
            return json.readValue(body, type);
        } catch (RuntimeException e) {
            throw new EsuoException(ErrorCode.VALIDATION_ERROR, "Некорректное тело запроса");
        }
    }

    private <T> T valid(T request) {
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new EsuoException(ErrorCode.VALIDATION_ERROR, "Проверьте поля запроса",
                    violations.stream().map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage())).toList());
        }
        return request;
    }

    private static String key(String key) {
        if (key == null || key.isBlank()) {
            throw new EsuoException(ErrorCode.VALIDATION_ERROR, "Нужен заголовок Idempotency-Key");
        }
        return key;
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new EsuoException(ErrorCode.VALIDATION_ERROR, message); }
    }
}
