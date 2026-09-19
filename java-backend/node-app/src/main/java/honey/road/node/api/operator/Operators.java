package honey.road.node.api.operator;

import honey.road.contracts.event.Actor;
import honey.road.node.queue.domain.DomainException;
import honey.road.node.security.CurrentUser;
import honey.road.node.security.Role;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/** Кто действует: сотрудник из JWT. Без привязки к сотруднику команды окна недоступны. */
final class Operators {
    private Operators() {
    }

    static UUID employee(Jwt jwt) {
        var id = CurrentUser.of(jwt).employeeId();
        if (id == null) {
            throw new DomainException("FORBIDDEN", "Пользователь не связан с сотрудником отделения");
        }
        return id;
    }

    static Actor actor(Jwt jwt) {
        var user = CurrentUser.of(jwt);
        var type = user.role() == Role.BRANCH_ADMIN ? Actor.ADMIN : Actor.OPERATOR;
        return new Actor(type, user.employeeId() != null ? user.employeeId().toString() : user.userId().toString());
    }
}
