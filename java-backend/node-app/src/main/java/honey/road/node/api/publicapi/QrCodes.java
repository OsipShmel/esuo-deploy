package honey.road.node.api.publicapi;

import honey.road.node.config.NodeProperties;
import honey.road.node.queue.domain.DomainException;
import org.springframework.stereotype.Component;

/**
    * Branch QR code verification. Currently, a static demo code {@code demo:<branch>} is used
    * (only with demo data). A rotating HMAC code (Section 13.4) will replace the implementation without changing the calls.
 */
@Component
public class QrCodes {
    private final NodeProperties node;

    public QrCodes(NodeProperties node) { this.node = node; }

    public String current() {
        return "demo:" + node.branchId();
    }

    public void verify(String code) {
        if (!node.demoData() || !current().equals(code)) {
            throw new DomainException("QR_CODE_INVALID", "QR-код недействителен");
        }
    }
}
