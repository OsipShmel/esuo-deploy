package honey.road.contracts.error;

/** An element of the {@code errors} array in ProblemDetail for VALIDATION_ERROR.*/
public record FieldViolation(String field, String message) {
}
