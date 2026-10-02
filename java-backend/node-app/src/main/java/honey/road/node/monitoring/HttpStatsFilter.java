package honey.road.node.monitoring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Counts the node's API requests for the {@code sys} block (design 6.1): count, 5xx and p95 per heartbeat interval.
 * SSE streams and actuator are left out: a stream lives for minutes and would make p95 meaningless, and health probes
 * are not user traffic. Runs before security, so a rejected request is counted too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class HttpStatsFilter extends OncePerRequestFilter {
    private final LatencyHistogram histogram = new LatencyHistogram();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            failed = true;
            throw e;
        } finally {
            if (!request.isAsyncStarted()) {
                histogram.record((System.nanoTime() - started) / 1_000_000, failed ? 500 : response.getStatus());
            }
        }
    }

    /** Requests since the previous call. */
    public LatencyHistogram.Window drain() {
        return histogram.drain();
    }
}
