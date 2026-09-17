package ru.esuo.contracts.error;

import java.util.List;
import java.util.Objects;

/**
* The system's only application-level exception. The domain throws it with a code from {@link ErrorCode},
* and the edge layer (REST nodes, RPC handlers, hub gateway) converts it into a ProblemDetail or RPC response.
* No stack trace is captured: this is an expected business outcome, not a failure.
 */
public class EsuoException extends RuntimeException {

    private final ErrorCode code;
    private final List<FieldViolation> violations;

    public EsuoException(ErrorCode code, String detail) {
        this(code, detail, List.of());
    }

    public EsuoException(ErrorCode code, String detail, List<FieldViolation> violations) {
        super(detail, null, false, false);
        this.code = Objects.requireNonNull(code);
        this.violations = List.copyOf(violations);
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
