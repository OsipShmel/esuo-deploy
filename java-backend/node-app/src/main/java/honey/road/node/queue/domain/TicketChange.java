package honey.road.node.queue.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Изменение изменяемых полей талона. Применяется {@code TicketRepository.apply}: один UPDATE с проверкой
 * {@code version} (оптимистическая блокировка) и {@code version+1}.
 */
public final class TicketChange {
    private final Ticket base;
    private TicketStatus status;
    private UUID serviceId;
    private UUID windowId;
    private UUID employeeId;
    private UUID targetWindowId;
    private Instant eligibleFrom;
    private Instant dueAt;
    private Instant calledAt;
    private Instant checkedInAt;
    private Instant servingStartedAt;
    private Instant completedAt;
    private int callCount;
    private int postponeCount;
    private String closeReason;

    private TicketChange(Ticket t) {
        base = t;
        status = t.status();
        serviceId = t.serviceId();
        windowId = t.windowId();
        employeeId = t.employeeId();
        targetWindowId = t.targetWindowId();
        eligibleFrom = t.eligibleFrom();
        dueAt = t.dueAt();
        calledAt = t.calledAt();
        checkedInAt = t.checkedInAt();
        servingStartedAt = t.servingStartedAt();
        completedAt = t.completedAt();
        callCount = t.callCount();
        postponeCount = t.postponeCount();
    }

    public static TicketChange of(Ticket t) { return new TicketChange(t); }

    /** Талон снова ждёт вызова: окно и сотрудник освобождаются. */
    public TicketChange backToWaiting(Instant eligibleFrom, Instant dueAt) {
        status = TicketStatus.waiting;
        windowId = null;
        employeeId = null;
        calledAt = null;
        servingStartedAt = null;
        callCount = 0;
        this.eligibleFrom = eligibleFrom;
        this.dueAt = dueAt;
        return this;
    }

    public TicketChange status(TicketStatus v) { status = v; return this; }
    public TicketChange serviceId(UUID v) { serviceId = v; return this; }
    public TicketChange windowId(UUID v) { windowId = v; return this; }
    public TicketChange employeeId(UUID v) { employeeId = v; return this; }
    public TicketChange targetWindowId(UUID v) { targetWindowId = v; return this; }
    public TicketChange eligibleFrom(Instant v) { eligibleFrom = v; return this; }
    public TicketChange dueAt(Instant v) { dueAt = v; return this; }
    public TicketChange calledAt(Instant v) { calledAt = v; return this; }
    public TicketChange checkedInAt(Instant v) { checkedInAt = v; return this; }
    public TicketChange servingStartedAt(Instant v) { servingStartedAt = v; return this; }
    public TicketChange completedAt(Instant v) { completedAt = v; return this; }
    public TicketChange callCount(int v) { callCount = v; return this; }
    public TicketChange postponeCount(int v) { postponeCount = v; return this; }
    public TicketChange closeReason(String v) { closeReason = v; return this; }

    public Ticket base() { return base; }
    public TicketStatus status() { return status; }
    public UUID serviceId() { return serviceId; }
    public UUID windowId() { return windowId; }
    public UUID employeeId() { return employeeId; }
    public UUID targetWindowId() { return targetWindowId; }
    public Instant eligibleFrom() { return eligibleFrom; }
    public Instant dueAt() { return dueAt; }
    public Instant calledAt() { return calledAt; }
    public Instant checkedInAt() { return checkedInAt; }
    public Instant servingStartedAt() { return servingStartedAt; }
    public Instant completedAt() { return completedAt; }
    public int callCount() { return callCount; }
    public int postponeCount() { return postponeCount; }
    public String closeReason() { return closeReason; }
}
