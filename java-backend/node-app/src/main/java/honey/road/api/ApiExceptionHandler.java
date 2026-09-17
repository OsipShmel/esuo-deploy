package honey.road.api;

import honey.road.queue.domain.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log=LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ProblemDetail domain(DomainException ex, HttpServletRequest request) {
        int status=switch (ex.code()) {
            case "VALIDATION_ERROR" -> 400;
            case "UNAUTHENTICATED" -> 401;
            case "FORBIDDEN" -> 403;
            case "BRANCH_NOT_FOUND", "SERVICE_NOT_FOUND", "WINDOW_NOT_FOUND", "TICKET_NOT_FOUND" -> 404;
            case "IDEMPOTENCY_CONFLICT", "QR_CODE_INVALID", "SERVICE_NOT_IN_WINDOW" -> 422;
            default -> 409;
        };
        return problem(status,ex.code(),ex.getMessage(),request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        var result=problem(400,"VALIDATION_ERROR","Проверьте обязательные поля и формат запроса",request);
        result.setProperty("errors",ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field",e.getField(),"message","Некорректное значение поля")).toList());
        return result;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception ex, HttpServletRequest request) {
        if (ex instanceof org.springframework.http.converter.HttpMessageNotReadableException
                || ex instanceof org.springframework.beans.TypeMismatchException
                || ex instanceof jakarta.validation.ConstraintViolationException) {
            return problem(400,"VALIDATION_ERROR","Некорректное тело или параметры запроса",request);
        }
        if (ex instanceof ErrorResponse error) {
            return problem(error.getStatusCode().value(),"VALIDATION_ERROR","Некорректный запрос",request);
        }
        // Токен талона и тело запроса не попадают в журнал ошибок.
        log.error("Ошибка обработки запроса: {}",ex.getClass().getSimpleName());
        return problem(500,"INTERNAL_ERROR","Не удалось выполнить операцию",request);
    }

    private ProblemDetail problem(int status, String code, String detail, HttpServletRequest request) {
        var result=ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status),detail);
        result.setTitle(detail);
        result.setType(URI.create("urn:esuo:problem:"+code.toLowerCase(Locale.ROOT).replace('_','-')));
        // Идентификатор экземпляра ошибки не раскрывает секрет из пути талона.
        result.setInstance(URI.create("urn:esuo:request:"+request.getAttribute("request_id")));
        result.setProperty("code",code);
        result.setProperty("request_id",request.getAttribute("request_id"));
        result.setProperty("errors",List.of());
        return result;
    }
}
