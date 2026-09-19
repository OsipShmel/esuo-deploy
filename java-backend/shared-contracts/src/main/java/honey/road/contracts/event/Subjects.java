package honey.road.contracts.event;

/**
 * Subjects и имена стримов NATS (разделы 10.1, 10.4). Единственное место, где они собираются строкой.
 */
public final class Subjects {
    private Subjects() {
    }

    // ---- отделение → центр
    public static String branchEvent(String branchId, String eventType) {
        return "branch." + branchId + ".evt." + eventType;
    }

    /** Все события отделения: subjects стрима BRANCH_EVT. */
    public static String branchEventsAll(String branchId) {
        return "branch." + branchId + ".evt.>";
    }

    /** Все события всех отделений (для core-подписки центра). */
    public static final String ALL_BRANCH_EVENTS = "branch.*.evt.>";
    public static final String ALL_TICKET_EVENTS = "branch.*.evt.ticket.>";

    public static String heartbeat(String branchId) {
        return "branch." + branchId + ".hb";
    }

    public static final String ALL_HEARTBEATS = "branch.*.hb";

    // ---- центр → отделение, синхронно
    public static String rpc(String branchId, String op) {
        return "branch." + branchId + ".rpc." + op;
    }

    public static String rpcAll(String branchId) {
        return "branch." + branchId + ".rpc.>";
    }

    // ---- центр → отделение, асинхронно
    public static final String CONFIG_GLOBAL = "center.config.global";

    public static String configBranch(String branchId) {
        return "center.config.branch." + branchId;
    }

    public static String mpInbox(String branchId) {
        return "center.mp.inbox.branch." + branchId;
    }

    // ---- стримы и домены
    public static final String STREAM_BRANCH_EVT = "BRANCH_EVT";
    public static final String STREAM_BRANCH_INBOX = "BRANCH_INBOX";
    public static final String STREAM_CENTER_EVT = "CENTER_EVT";
    public static final String STREAM_CONFIG = "CONFIG";
    public static final String STREAM_MP_INBOX = "MP_INBOX";

    public static final String CENTER_DOMAIN = "center";

    public static String branchDomain(String branchId) {
        return "branch-" + branchId;
    }

    /** API JetStream чужого домена — для sources между доменами. */
    public static String jsApiPrefix(String domain) {
        return "$JS." + domain + ".API";
    }

    /** Имя отделения из subject вида {@code branch.b01.*}. */
    public static String branchIdOf(String subject) {
        if (subject == null || !subject.startsWith("branch.")) {
            return null;
        }
        int end = subject.indexOf('.', 7);
        return end < 0 ? null : subject.substring(7, end);
    }
}
