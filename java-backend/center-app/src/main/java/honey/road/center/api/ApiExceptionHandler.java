package honey.road.center.api;

import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import honey.road.contracts.error.FieldViolation;

import java.net.URI;
import java.util.List;

/**
 * application/problem+json. Cur fields: code, request_id, errors (docs section 9.2).
 * {@link #createResponseEntity} extends ProblemDetail with our fields.
 * Скрывает стандартный Spring MVC,
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(EsuoException.class)
    ResponseEntity<Object> handleEsuo(EsuoException ex, WebRequest request) {
        return problem(ex.code(), ex.getMessage(), ex.violations(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("unhandled exception", ex);
        return problem(ErrorCode.INTERNAL_ERROR, null, List.of(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        var violations = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new FieldViolation(e.getField(), e.getDefaultMessage()))
                .toList();
        return problem(ErrorCode.VALIDATION_ERROR, "Проверьте поля запроса", violations, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail pd
                && (pd.getProperties() == null || !pd.getProperties().containsKey("code"))) {
            decorate(pd, ErrorCode.fallbackFor(statusCode.value()), requestId(request));
            pd.setProperty("errors", List.of());
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ResponseEntity<Object> problem(ErrorCode code, @Nullable String detail,
                                           List<FieldViolation> violations, WebRequest request) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(code.httpStatus()), detail);
        decorate(pd, code, requestId(request));
        pd.setProperty("errors", violations);
        return ResponseEntity.status(code.httpStatus()).body(pd);
    }

    private static void decorate(ProblemDetail pd, ErrorCode code, @Nullable String requestId) {
        pd.setType(URI.create(code.typeUri()));
        pd.setTitle(code.title());

        // Не путь запроса: в пути бывает токен талона (идея A).
        pd.setInstance(URI.create("urn:esuo:request:" + (requestId == null ? "unknown" : requestId)));
        pd.setProperty("code", code.name());
        pd.setProperty("request_id", requestId);
    }

    private static @Nullable String requestId(WebRequest request) {
        return (String) request.getAttribute(RequestIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    }
}
