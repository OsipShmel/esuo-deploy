package honey.road.node.security.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import honey.road.node.security.Role;

import java.util.Optional;
import java.util.UUID;

/** Чтение таблицы users (раздел 8.1).
 * При текущем плане пишет в неё потребитель CONFIG/сид
 * */
@Repository
class UserCredentialsRepository {

    private final JdbcClient jdbc;

    UserCredentialsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<honey.road.node.security.auth.UserCredentials> findActiveByLogin(String login) {
        return jdbc.sql("""
                        SELECT id, login, password_hash, role, employee_id
                          FROM users
                         WHERE login = :login AND active
                        """)
                .param("login", login)
                .query((rs, _) -> new honey.road.node.security.auth.UserCredentials(
                        rs.getObject("id", UUID.class),
                        rs.getString("login"),
                        rs.getString("password_hash"),
                        Role.valueOf(rs.getString("role")),
                        rs.getObject("employee_id", UUID.class)))
                .optional();
    }
}
