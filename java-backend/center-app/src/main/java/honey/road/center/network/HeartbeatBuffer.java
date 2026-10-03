package honey.road.center.network;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The last heartbeat of every known branch, waiting for the next batch write (design 6.2). Bounded by the number of
 * branches: a newer heartbeat replaces the older one. Puts run concurrently; a drain waits for puts in progress, so a
 * heartbeat never lands in a map that was already taken away.
 */
@Component
public class HeartbeatBuffer {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private ConcurrentHashMap<String, Heartbeat> pending = new ConcurrentHashMap<>();

    public void put(Heartbeat hb) {
        lock.readLock().lock();
        try {
            pending.merge(hb.branchId(), hb, (old, fresh) -> fresh.receivedAt().isBefore(old.receivedAt()) ? old : fresh);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Heartbeats waiting for the next flush. */
    public int size() {
        lock.readLock().lock();
        try {
            return pending.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Everything collected so far; later heartbeats go to a fresh map. */
    public Map<String, Heartbeat> drain() {
        lock.writeLock().lock();
        try {
            var taken = pending;
            pending = new ConcurrentHashMap<>();
            return taken;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Puts back a batch that could not be written, unless a fresher heartbeat of the branch came meanwhile. */
    public void restore(Collection<Heartbeat> batch) {
        lock.readLock().lock();
        try {
            batch.forEach(hb -> pending.putIfAbsent(hb.branchId(), hb));
        } finally {
            lock.readLock().unlock();
        }
    }
}
