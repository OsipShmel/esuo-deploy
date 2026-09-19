package honey.road.center.sse;

import honey.road.center.gateway.RpcResponses;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
public class CenterStreamController {
    private final CenterTicketStreams streams;
    private final RpcResponses responses;

    public CenterStreamController(CenterTicketStreams streams, RpcResponses responses) {
        this.streams = streams;
        this.responses = responses;
    }

    @GetMapping(path = "/api/v1/branches/{branch}/tickets/{token}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String branch, @PathVariable UUID token) {
        return streams.subscribe(branch, token);
    }

    @ExceptionHandler(CenterTicketStreams.TicketStreamRejected.class)
    ResponseEntity<Object> rejected(CenterTicketStreams.TicketStreamRejected e) {
        return responses.of(e.result());
    }
}
