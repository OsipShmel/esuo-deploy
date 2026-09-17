package honey.road.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

@Component
public class RequestIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id=request.getHeader("X-Request-Id");
        if (id==null || !id.matches("[a-zA-Z0-9._-]{1,128}")) { id=UUID.randomUUID().toString(); }
        request.setAttribute("request_id",id);
        response.setHeader("X-Request-Id",id);
        MDC.put("request_id",id);
        try { chain.doFilter(request,response); }
        finally { MDC.remove("request_id"); }
    }
}
