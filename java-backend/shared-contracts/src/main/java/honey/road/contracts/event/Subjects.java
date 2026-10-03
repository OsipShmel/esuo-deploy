package honey.road.contracts.event;

/**
 * NATS subjects and stream names (sect 10.1, 10.4); the only place they are built as strings.
 */
public final class Subjects {
    private Subjects() {
    }

    // ---- branch → center
    public static String branchEvent(String branchId, String eventType) {
        return "branch." + branchId + ".evt." + eventType;
    }

    /** All branch events: BRANCH_EVT subjects. */
    public static String branchEventsAll(String branchId) {
        return "branch." + branchId + ".evt.>";
    }

    /** All events of all branches (center core subscription). */
    public static final String ALL_BRANCH_EVENTS = "branch.*.evt.>";
    public static final String ALL_TICKET_EVENTS = "branch.*.evt.ticket.>";

    public static String heartbeat(String branchId) {
        return "branch." + branchId + ".hb";
    }

    public static final String ALL_HEARTBEATS = "branch.*.hb";

    /** Config of every branch (core subscription of the swarm simulator to confirm versions like a node). */
    public static final String CONFIG_ALL_BRANCHES = "center.config.branch.>";

    /** Important log lines of a branch (WARN, ERROR, lifecycle), published by the branch collector; level in lower case. */
    public static String branchLog(String branchId, String level) {
        return "branch." + branchId + ".log." + level;
    }

    /** All log lines of a branch: BRANCH_LOG subjects. */
    public static String branchLogsAll(String branchId) {
        return "branch." + branchId + ".log.>";
    }

    // ---- center → branch, sync
    public static String rpc(String branchId, String op) {
        return "branch." + branchId + ".rpc." + op;
    }

    public static String rpcAll(String branchId) {
        return "branch." + branchId + ".rpc.>";
    }

    // ---- center → branch, async
    public static final String CONFIG_GLOBAL = "center.config.global";

    public static String configBranch(String branchId) {
        return "center.config.branch." + branchId;
    }

    public static String mpInbox(String branchId) {
        return "center.mp.inbox.branch." + branchId;
    }

    // ---- center-side logs
    /** Full branch log lines pulled on request (diag.logs), published by the center into its own CENTER_LOG. */
    public static String centerLogPull(String branchId) {
        return "center.log.pull." + branchId;
    }

    public static final String CENTER_LOG_PULL_ALL = "center.log.pull.>";

    // ---- streams and domains
    public static final String STREAM_BRANCH_EVT = "BRANCH_EVT";
    public static final String STREAM_BRANCH_INBOX = "BRANCH_INBOX";
    public static final String STREAM_CENTER_EVT = "CENTER_EVT";
    public static final String STREAM_CONFIG = "CONFIG";
    public static final String STREAM_MP_INBOX = "MP_INBOX";
    /**
     * Regional level (design doc 3.8): one regional leaf collects the events of all its branches, and the center
     * sources this stream once per region instead of one {@code BRANCH_EVT} per branch. The leaf denies importing
     * {@code branch.*.evt.>} from the hub, so the stream holds only its own region's events.
     */
    public static final String STREAM_REGION_EVT = "REGION_EVT";
    /** {@code events_via} of a heartbeat: the JetStream domain that holds the branch's events instead of its own leaf. */
    public static final String REGION_DOMAIN_PATTERN = "region-[a-z0-9-]{1,32}";
    public static final String STREAM_BRANCH_LOG = "BRANCH_LOG";
    public static final String STREAM_CENTER_LOG = "CENTER_LOG";

    public static final String CENTER_DOMAIN = "center";

    public static String branchDomain(String branchId) {
        return "branch-" + branchId;
    }

    /** JetStream API of another domain, for cross-domain sources. */
    public static String jsApiPrefix(String domain) {
        return "$JS." + domain + ".API";
    }

    /** Branch id from a {@code branch.b01.*} subject. */
    public static String branchIdOf(String subject) {
        if (subject == null || !subject.startsWith("branch.")) {
            return null;
        }
        int end = subject.indexOf('.', 7);
        return end < 0 ? null : subject.substring(7, end);
    }
}
