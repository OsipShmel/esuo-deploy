package honey.road.node.queue.application;

import honey.road.node.config.NodeProperties;
import honey.road.node.persistence.CatalogRepository;
import honey.road.node.persistence.TicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Hall board (sect 15): latest calls and queue length per service. */
@Service
@Transactional(readOnly = true)
public class BoardQueries {
    private final TicketRepository tickets;
    private final CatalogRepository catalog;
    private final NodeProperties node;

    public BoardQueries(TicketRepository tickets, CatalogRepository catalog, NodeProperties node) {
        this.tickets = tickets; this.catalog = catalog; this.node = node;
    }

    public record Call(String displayNumber, UUID windowId, String windowName, String status, Instant calledAt, int callCount) {
    }

    /**
     * Hall QR: code, ready join link (absolute, or relative to the board address) and the mode in effect
     * ({@code center} | {@code local}).
     */
    public record Qr(String code, String joinUrl, String modeEffective) {
    }

    public record Board(String branchId, String branchName, List<Call> calls, Map<String, Long> waitingByService,
                        String qrCode, String joinUrl, String qrModeEffective) {
    }

    public Board board(Qr qr) {
        var calls = tickets.recentCalls(node.branchId(), 10).stream()
                .map(t -> new Call(t.displayNumber(), t.windowId(), catalog.windowName(t.windowId()), t.status().name(),
                        t.calledAt(), t.callCount()))
                .toList();
        var names = catalog.services(node.branchId()).stream()
                .collect(Collectors.toMap(s -> s.id(), s -> s.name()));
        var waiting = tickets.waiting(node.branchId()).stream()
                .collect(Collectors.groupingBy(t -> names.getOrDefault(t.serviceId(), "?"), Collectors.counting()));
        return new Board(node.branchId(), catalog.branch(node.branchId()).name(), calls, waiting,
                qr.code(), qr.joinUrl(), qr.modeEffective());
    }
}
