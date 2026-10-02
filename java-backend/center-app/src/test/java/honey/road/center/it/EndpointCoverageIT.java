package honey.road.center.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every API handler of the center is either on the public list of the design (4.1) or answers 401 without a token.
 * The list is written here on purpose, not taken from the security config: a new endpoint must not become public
 * just because someone added it to both places — it has to be added to the spec's list by hand.
 */
class EndpointCoverageIT extends AbstractCenterIT {

    /** Design 4.1, verbatim. */
    private static final Set<String> PUBLIC_API = Set.of(
            "/api/v1/branches",
            "/api/v1/branches/{branch}/info",
            "/api/v1/branches/{branch}/slots",
            "/api/v1/branches/{branch}/booking-limits",
            "/api/v1/branches/{branch}/appointments",
            "/api/v1/branches/{branch}/qr-tickets",
            "/api/v1/branches/{branch}/tickets/{token}",
            "/api/v1/branches/{branch}/tickets/{token}/cancel",
            "/api/v1/branches/{branch}/tickets/{token}/postpone",
            "/api/v1/branches/{branch}/tickets/{token}/check-in",
            "/api/v1/branches/{branch}/tickets/{token}/stream",
            "/api/v1/webhooks/mp/appointments",
            "/api/v1/auth/login");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void everyNonPublicApiHandlerRequiresToken() {
        var open = new ArrayList<String>();
        int checked = 0;
        for (var info : mappings.getHandlerMethods().keySet()) {
            var methods = info.getMethodsCondition().getMethods();
            for (var pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/") || PUBLIC_API.contains(pattern)) {
                    continue;
                }
                for (var method : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    checked++;
                    var uri = concrete(pattern);
                    int status = mvc.method(HttpMethod.valueOf(method.name())).uri(uri)
                            .contentType(MediaType.APPLICATION_JSON).content("{}")
                            .exchange().getResponse().getStatus();
                    if (status != 401) {
                        open.add(method + " " + pattern + " -> " + status);
                    }
                }
            }
        }
        assertThat(checked).as("private handlers found").isPositive();
        assertThat(open).as("handlers reachable without a token").isEmpty();
    }

    private static String concrete(String pattern) {
        return pattern
                .replaceAll("\\{(branch|branchId)}", "b01")
                .replaceAll("\\{(token|userId)}", UUID.randomUUID().toString())
                .replaceAll("\\{[^}]+}", "1");
    }
}
