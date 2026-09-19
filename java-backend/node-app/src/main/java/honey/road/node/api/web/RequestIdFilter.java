package honey.road.node.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import static honey.road.contracts.http.EsuoHeaders.MDC_REQUEST_ID;
import static honey.road.contracts.http.EsuoHeaders.REQUEST_ID;

/**
* Accepts or uses the `X-Request-Id` header, placing it into the MDC and the response.
* Placed before the security layer (described in the documentation, section 10).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    //TODO! мб стоит подумать, хотя в рамках хакатона плевать
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var incoming = request.getHeader(REQUEST_ID);
        var requestId = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString();

        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID, requestId);
        MDC.put(MDC_REQUEST_ID, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    // TODO!
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}
