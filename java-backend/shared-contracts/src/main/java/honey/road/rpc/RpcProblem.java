package honey.road.rpc;

import honey.road.error.ErrorCode;
import honey.road.error.FieldViolation;

import java.util.List;

public record RpcProblem(ErrorCode code, String title, String detail, List<FieldViolation> errors) {

    public RpcProblem {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
