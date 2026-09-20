package honey.road.contracts.error;

/**
 * API error codes (sect 9.2), shared by the domain, node, center and the RPC envelope.
 * The frontend branches on {@link #name()}.
 */
public enum ErrorCode {

    VALIDATION_ERROR(400, "Некорректный запрос"),
    UNAUTHENTICATED(401, "Требуется аутентификация"),
    FORBIDDEN(403, "Недостаточно прав"),

    TICKET_NOT_FOUND(404, "Талон не найден"),
    WINDOW_NOT_FOUND(404, "Окно не найдено"),
    SERVICE_NOT_FOUND(404, "Услуга не найдена"),
    BRANCH_NOT_FOUND(404, "Отделение не найдено"),
    /** Unknown route or RPC operation. */
    NOT_FOUND(404, "Ресурс не найден"),
    /** Unsupported HTTP method. */
    METHOD_NOT_ALLOWED(405, "Метод не поддерживается"),

    INVALID_STATE_TRANSITION(409, "Недопустимый переход статуса"),
    STALE_STATE(409, "Состояние изменилось, обновите данные"),
    WINDOW_NOT_OPEN(409, "Окно не открыто"),
    WINDOW_BUSY(409, "Окно уже обслуживает клиента"),
    QUEUE_EMPTY(409, "Очередь пуста"),
    SLOT_UNAVAILABLE(409, "Слот недоступен"),
    SLOT_EXPIRED(409, "Время слота прошло"),
    ACTIVE_TICKET_EXISTS(409, "У устройства уже есть активный талон"),
    /** Booking for this hour is closed by the load quota (sect 6.10) [MVP-demo]. */
    BOOKING_LIMITED(409, "Запись на это время закрыта из-за высокой нагрузки"),
    LOGIN_TAKEN(409, "Логин уже занят"),

    IDEMPOTENCY_CONFLICT(422, "Ключ идемпотентности использован с другим запросом"),
    QR_CODE_INVALID(422, "QR-код недействителен"),
    CONFIG_OUT_OF_BOUNDS(422, "Значение вне допустимых границ"),
    SERVICE_NOT_IN_WINDOW(422, "Услуга не оказывается в этом окне"),

    RATE_LIMITED(429, "Слишком много запросов"),
    INTERNAL_ERROR(500, "Внутренняя ошибка"),
    BRANCH_UNAVAILABLE(503, "Отделение временно недоступно");

    private static final String TYPE_BASE = "https://esuo.local/problems/";

    private final int httpStatus;
    private final String title;

    ErrorCode(int httpStatus, String title) {
        this.httpStatus = httpStatus;
        this.title = title;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String title() {
        return title;
    }

    /** {@code WINDOW_BUSY -> https://esuo.local/problems/window-busy} */
    public String typeUri() {
        return TYPE_BASE + name().toLowerCase().replace('_', '-');
    }

    public static ErrorCode fallbackFor(int httpStatus) {
        return switch (httpStatus) {
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 429 -> RATE_LIMITED;
            case 503 -> BRANCH_UNAVAILABLE;
            default -> httpStatus >= 500 ? INTERNAL_ERROR : VALIDATION_ERROR;
        };
    }
}
