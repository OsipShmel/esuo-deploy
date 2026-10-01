package honey.road.node.api.publicapi;

import honey.road.node.config.NodeProperties;
import honey.road.node.messaging.nats.LeafLinkMonitor;
import honey.road.node.queue.application.BoardQueries;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/**
 * Where the hall QR leads (design doc, QR modes): the join page on the center (internet) or on the node (branch
 * Wi-Fi). In {@code auto} the center is used while the leaf is linked to the hub, so a cut link switches the board to
 * the local address within one leaf poll. A blank local base yields a relative link: the board resolves it against
 * the address it is opened from.
 */
@Component
public class JoinLinks {
    public static final String CENTER = "center";
    public static final String LOCAL = "local";

    private final String branchId;
    private final QrProperties props;
    private final QrCodes codes;
    private final BooleanSupplier linked;

    public JoinLinks(NodeProperties node, QrProperties props, QrCodes codes, LeafLinkMonitor leaf) {
        this(node.branchId(), props, codes, leaf::linked);
    }

    JoinLinks(String branchId, QrProperties props, QrCodes codes, BooleanSupplier linked) {
        if (props.mode() == QrProperties.Mode.center && props.publicBaseUrl().isEmpty()) {
            throw new IllegalStateException("esuo.qr.mode=center требует QR_PUBLIC_BASE_URL (публичный адрес центра)");
        }
        this.branchId = branchId;
        this.props = props;
        this.codes = codes;
        this.linked = linked;
    }

    /** QR block of the board: code, ready link and the mode in effect. */
    public BoardQueries.Qr board() {
        var mode = effectiveMode();
        var code = codes.current();
        return new BoardQueries.Qr(code, url(mode, code), mode);
    }

    public String effectiveMode() {
        return switch (props.mode()) {
            case center -> CENTER;
            case local -> LOCAL;
            case auto -> !props.publicBaseUrl().isEmpty() && linked.getAsBoolean() ? CENTER : LOCAL;
        };
    }

    /** Center link, when a public center address is configured. */
    public String centerUrl(String code) {
        return props.publicBaseUrl().isEmpty() ? null : url(CENTER, code);
    }

    public String localUrl(String code) {
        return url(LOCAL, code);
    }

    private String url(String mode, String code) {
        var base = CENTER.equals(mode) ? props.publicBaseUrl() : props.localBaseUrl();
        return base + "/b/" + branchId + "/join?code=" + URLEncoder.encode(code, StandardCharsets.UTF_8);
    }
}
