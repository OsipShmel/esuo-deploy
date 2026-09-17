package honey.road.queue.application;

import honey.road.TicketView;
import honey.road.persistence.CatalogRepository;
import honey.road.queue.domain.Ticket;
import org.springframework.stereotype.Component;

@Component
public class TicketViews {
    private final CatalogRepository catalog;
    public TicketViews(CatalogRepository catalog) { this.catalog = catalog; }

    public TicketView of(Ticket t) {
        var service = catalog.service(t.branchId(),t.serviceId());
        var window = t.windowId() == null ? null : new TicketView.WindowView(t.windowId(),catalog.windowName(t.windowId()));
        // Прогноз и откладывание появятся в День 3; пока интерфейс не обещает недоступные действия.
        return new TicketView(t.id(),t.publicToken(),t.branchId(),t.displayNumber(),
                new TicketView.ServiceView(service.id(),service.name()),t.source().name(),t.clientCategory(),t.status().name(),
                null,null,null,t.slotStart(),window,t.calledAt(),t.status().canCancel(),false,t.version(),t.updatedAt());
    }
}
