package honey.road.node.queue.application;

import honey.road.contracts.dto.TicketView;
import honey.road.node.persistence.CatalogRepository;
import honey.road.node.persistence.PolicyRepository;
import honey.road.node.queue.domain.Ticket;
import honey.road.node.queue.domain.TicketStatus;
import org.springframework.stereotype.Component;

/** Ticket → {@link TicketView} (sect 9.4) with position and ETA. */
@Component
public class TicketViews {
    private final CatalogRepository catalog;
    private final WaitTimeEstimator estimator;
    private final PolicyRepository policies;

    public TicketViews(CatalogRepository catalog, WaitTimeEstimator estimator, PolicyRepository policies) {
        this.catalog = catalog;
        this.estimator = estimator;
        this.policies = policies;
    }

    public TicketView of(Ticket t) {
        var service = catalog.service(t.branchId(), t.serviceId());
        var window = t.windowId() == null || !t.active() ? null
                : new TicketView.WindowView(t.windowId(), catalog.windowName(t.windowId()));
        var estimate = estimator.estimate(t);
        boolean canPostpone = t.status() == TicketStatus.waiting && t.postponeCount() < policies.current().postponeMaxCount();
        return new TicketView(t.id(), t.publicToken(), t.branchId(), t.displayNumber(),
                new TicketView.ServiceView(service.id(), service.name()), t.source().name(), t.clientCategory(),
                t.status().name(), estimate.position(), estimate.eta(), estimate.confidence(), t.slotStart(), window,
                t.calledAt(), t.status().canCancel(), canPostpone, t.version(), t.updatedAt());
    }
}
