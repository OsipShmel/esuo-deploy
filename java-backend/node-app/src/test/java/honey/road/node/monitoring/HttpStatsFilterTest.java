package honey.road.node.monitoring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What counts as an API request for {@code http_*} in the {@code sys} block (design 6.1). */
class HttpStatsFilterTest {
    private final HttpStatsFilter filter = new HttpStatsFilter();

    private void call(String uri, FilterChain chain) throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", uri), new MockHttpServletResponse(), chain);
    }

    @Test
    void requestsAreCounted() throws Exception {
        for (int i = 0; i < 3; i++) {
            call("/api/v1/public/board", (rq, rs) -> { });
        }

        assertThat(filter.drain().count()).isEqualTo(3);
    }

    @Test
    void sseIsNotCounted() throws Exception {
        call("/api/v1/public/board/stream", (rq, rs) -> ((MockHttpServletRequest) rq).setAsyncStarted(true));

        assertThat(filter.drain().count()).isZero();
    }

    @Test
    void actuatorIsNotCounted() throws Exception {
        call("/actuator/health", (rq, rs) -> { });

        assertThat(filter.drain().count()).isZero();
    }

    /** The node also serves the screens; their files are fast and would make p95 look better than the API is. */
    @Test
    void screensAndTheirFilesAreNotCounted() throws Exception {
        for (var uri : new String[]{"/", "/board", "/operator/", "/assets/index-3f2a.js", "/assets/app.css", "/apiary"}) {
            call(uri, (rq, rs) -> { });
        }

        assertThat(filter.drain().count()).isZero();
    }

    @Test
    void statusIsTakenFromTheResponse() throws Exception {
        call("/api/v1/x", (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(503));

        assertThat(filter.drain().errors5xx()).isEqualTo(1);
    }

    @Test
    void exceptionCountsAs5xx() {
        assertThatThrownBy(() -> call("/api/v1/x", (rq, rs) -> { throw new ServletException("boom"); }))
                .isInstanceOf(ServletException.class);

        var w = filter.drain();
        assertThat(w.count()).isEqualTo(1);
        assertThat(w.errors5xx()).isEqualTo(1);
    }
}
