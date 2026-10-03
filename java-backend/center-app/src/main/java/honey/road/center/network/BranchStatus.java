package honey.road.center.network;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * What the network overview needs to judge a branch (C3c), without its whole last heartbeat: a dozen fields the
 * database extracts from {@code last_heartbeat} itself. For 40 000 branches that is a few megabytes per poll instead
 * of the 80 MB of heartbeat JSON the full overview reads and parses.
 */
record BranchStatus(String id, String name, Instant lastHeartbeatAt, boolean sourceAttached,
                    long waiting, long overdue, long oldestWaitSec, long windowsOpen, long outboxLagSec,
                    @Nullable String loadLevel, @Nullable Instant loadLevelSince, @Nullable Instant sentAt, boolean dbOk,
                    int hbConfigVersion, @Nullable Integer configPublishedVersion, @Nullable Instant configPublishedAt,
                    @Nullable Integer configAppliedVersion, @Nullable String eventsVia) {
}
