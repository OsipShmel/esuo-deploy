package honey.road.node.api.error;

import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import honey.road.contracts.error.FieldViolation;
import honey.road.node.api.web.RequestIdFilter;
import honey.road.node.queue.domain.DomainException;
import honey.road.node.monitoring.Lifecycle;

import java.net.URI;
import java.util.List;

/**
 * application/problem+json. Cur fields: code, request_id, errors (docs section 9.2).
 * {@link #createResponseEntity} extends ProblemDetail with our fields.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(EsuoException.class)
    ResponseEntity<Object> handleEsuo(EsuoException ex, WebRequest request) {
        return problem(ex.code(), ex.getMessage(), ex.violations(), request);
    }


    @ExceptionHandler(DomainException.class)
    ResponseEntity<Object> handleDomain(DomainException ex, WebRequest request) {
        ErrorCode code;
        try {
            code = ErrorCode.valueOf(ex.code());
        } catch (IllegalArgumentException _) {
            log.error("unknown domain error code={}", ex.code());
            return problem(ErrorCode.INTERNAL_ERROR, null, List.of(), request);
        }
        return problem(code, ex.getMessage(), List.of(), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleUnauthenticated(AuthenticationException ex, WebRequest request) {
        return problem(ErrorCode.UNAUTHENTICATED, null, List.of(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleForbidden(AccessDeniedException ex, WebRequest request) {
        return problem(ErrorCode.FORBIDDEN, null, List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        Lifecycle.error(log, "error.unhandled", ex, "unhandled exception in request handler");
        return problem(ErrorCode.INTERNAL_ERROR, null, List.of(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        var violations = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new FieldViolation(e.getField(), e.getDefaultMessage()))
                .toList();
        return problem(ErrorCode.VALIDATION_ERROR, "Check the request fields.", violations, request);
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

        pd.setInstance(URI.create("urn:esuo:request:" + (requestId == null ? "unknown" : requestId)));
        pd.setProperty("code", code.name());
        pd.setProperty("request_id", requestId);
    }

    private static @Nullable String requestId(WebRequest request) {
        return (String) request.getAttribute(RequestIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    }
}
