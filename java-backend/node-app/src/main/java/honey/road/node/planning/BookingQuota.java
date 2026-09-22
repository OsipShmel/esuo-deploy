package honey.road.node.planning;

/**
 * Booking quota arithmetic for one hour and service (load-planning.md 5.1): {@code разрешено(s, h)}.
 *
 * <p>A pure function on purpose, same as {@link LoadForecast}: how many appointments an hour may hold does not
 * depend on the database, only on the service's own slot shape and the quota share for that hour's level.
 * Already accepted appointments are never revoked — the caller compares this number against the count of
 * appointments already booked, and only ever refuses new ones.
 */
public final class BookingQuota {

    private BookingQuota() {
    }

    /**
     * @param slotDurationMin length of one slot of this service, minutes
     * @param slotCapacity    clients per slot
     * @param quota           share of the hour left open for booking, 0..1 (sect 5.1); values outside are clamped
     * @return appointments allowed in the hour for this service, rounded down
     */
    public static long allowed(int slotDurationMin, int slotCapacity, double quota) {
        if (slotDurationMin <= 0 || slotCapacity <= 0) {
            return 0;
        }
        double slotsInHour = 60.0 / slotDurationMin;
        return (long) Math.floor(slotsInHour * slotCapacity * Math.clamp(quota, 0.0, 1.0));
    }
}
