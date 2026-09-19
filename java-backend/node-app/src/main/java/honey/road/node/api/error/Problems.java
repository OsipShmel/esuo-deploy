package honey.road.node.api.error;

import honey.road.contracts.error.ErrorCode;
import honey.road.contracts.error.EsuoException;
import honey.road.node.queue.domain.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Любая ошибка → {@link EsuoException} с кодом раздела 9.2. Общая точка для REST и RPC. */
public final class Problems {
    private static final Logger log = LoggerFactory.getLogger(Problems.class);

    private Problems() {
    }

    public static EsuoException from(Throwable ex) {
        if (ex instanceof EsuoException e) {
            return e;
        }
        if (ex instanceof DomainException d) {
            try {
                return new EsuoException(ErrorCode.valueOf(d.code()), d.getMessage());
            } catch (IllegalArgumentException unknown) {
                log.error("unknown domain error code={}", d.code());
                return new EsuoException(ErrorCode.INTERNAL_ERROR, null);
            }
        }
        log.error("unexpected error", ex);
        return new EsuoException(ErrorCode.INTERNAL_ERROR, null);
    }
}
