package ru.esuo.contracts.rpc;

import ru.esuo.contracts.error.ErrorCode;
import ru.esuo.contracts.error.FieldViolation;

import java.util.List;

public record RpcProblem(ErrorCode code, String title, String detail, List<FieldViolation> errors) {

    public RpcProblem {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
