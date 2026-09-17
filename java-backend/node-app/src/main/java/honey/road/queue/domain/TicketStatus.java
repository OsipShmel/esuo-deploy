package honey.road.queue.domain;

public enum TicketStatus {
    booked, waiting, called, serving, done, cancelled, no_show;

    public boolean canCancel() {
        return this == booked || this == waiting;
    }
}
