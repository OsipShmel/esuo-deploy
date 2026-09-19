package honey.road.center.gateway;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.net.URI;

import static honey.road.contracts.http.EsuoHeaders.MDC_REQUEST_ID;

/** RPC → HTTP response: {@code status} and {@code data}; error — ProblemDetail with the branch code (10.3). */
@Component
public class RpcResponses {

    public ResponseEntity<Object> of(BranchRpcClient.RpcResult r) {
        if (r.ok()) {
            return ResponseEntity.status(r.status()).body(r.data());
        }
        var p = r.problem();
        var code = p == null ? "INTERNAL_ERROR" : p.path("code").asString("INTERNAL_ERROR");
        var status = HttpStatus.resolve(r.status()) == null ? HttpStatus.BAD_GATEWAY : HttpStatus.valueOf(r.status());
        var pd = ProblemDetail.forStatusAndDetail(status, p == null ? null : p.path("detail").asString(null));
        pd.setType(URI.create("https://esuo.local/problems/" + code.toLowerCase().replace('_', '-')));
        pd.setTitle(p == null ? null : p.path("title").asString(null));
        var requestId = MDC.get(MDC_REQUEST_ID);
        pd.setInstance(URI.create("urn:esuo:request:" + (requestId == null ? "unknown" : requestId)));
        pd.setProperty("code", code);
        pd.setProperty("request_id", requestId);
        pd.setProperty("errors", p == null ? java.util.List.of() : p.path("errors"));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

}
