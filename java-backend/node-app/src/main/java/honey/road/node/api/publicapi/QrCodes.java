package honey.road.node.api.publicapi;

import honey.road.node.config.NodeProperties;
import honey.road.node.queue.domain.DomainException;
import org.springframework.stereotype.Component;

/**
 * Проверка QR-кода отделения. Сейчас — демонстрационный статический код {@code demo:<branch>}
 * (только при demo-data). Ротируемый HMAC-код (раздел 13.4) заменит реализацию, не меняя вызовов.
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
