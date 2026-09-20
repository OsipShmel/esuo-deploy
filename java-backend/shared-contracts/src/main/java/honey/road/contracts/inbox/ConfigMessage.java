package honey.road.contracts.inbox;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Сообщение конфигурации центра → отделение ({@code center.config.global}, {@code center.config.branch.{b}}),
 * раздел 4.8. Применяется, если {@code version} выше действующей центральной версии отделения.
 * <ul>
 *   <li>{@code queue} — параметры очереди с ключами как в разделе 6.9 в snake_case (полные или частичные:
 *       частичные накладываются на действующую центральную конфигурацию);</li>
 *   <li>{@code bounds} — границы локального переопределения руководителем: {@code {"slack_min": {"min":0,"max":15}}};</li>
 *   <li>{@code employees}, {@code users} — сотрудники и пользователи отделения (раздел 13.2); отсутствующие
 *       в сообщении не удаляются, отключение — флагом {@code active=false}.</li>
 * </ul>
 */
public record ConfigMessage(
        int version,
        String scope,
        Map<String, Object> queue,
        Map<String, Object> bounds,
        List<Employee> employees,
        List<User> users
) {
    public record Employee(UUID id, String fullName, Boolean active) {
    }

    /** {@code passwordHash}: bcrypt; plain passwords never travel over NATS. */
    public record User(UUID id, String login, String passwordHash, String role, UUID employeeId, Boolean active) {
    }
}
