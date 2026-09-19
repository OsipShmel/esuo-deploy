package honey.road.contracts.rpc;

import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.FieldViolation;

import java.util.List;

public record RpcProblem(ErrorCode code, String title, String detail, List<FieldViolation> errors) {

    public RpcProblem {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
