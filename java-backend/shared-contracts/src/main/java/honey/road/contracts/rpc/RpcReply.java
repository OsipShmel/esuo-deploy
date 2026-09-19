package honey.road.contracts.rpc;

import honey.road.contracts.error.EsuoException;

/**
 * NATS request-reply response envelope ("center → node") (dock section 10.3):
 * {@code {ok: true, status, data}} or {@code {ok: false, status, problem}}.
 */
public record RpcReply<T>(boolean ok, int status, T data, RpcProblem problem) {

    public static <T> RpcReply<T> ok(int status, T data) {
        return new RpcReply<>(true, status, data, null);
    }

    public static <T> RpcReply<T> failed(EsuoException ex) {
        var code = ex.code();
        return new RpcReply<>(false, code.httpStatus(), null,
                new RpcProblem(code, code.title(), ex.getMessage(), ex.violations()));
    }
}
