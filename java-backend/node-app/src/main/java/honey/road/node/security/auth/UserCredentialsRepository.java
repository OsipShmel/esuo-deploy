package honey.road.node.security.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import honey.road.node.security.Role;

import java.util.Optional;
import java.util.UUID;

/** Reads the users table (sect 8.1); written by the CONFIG consumer and the demo seeder. */
@Repository
class UserCredentialsRepository {

    private final JdbcClient jdbc;

    UserCredentialsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<UserCredentials> findActiveByLogin(String login) {
        return jdbc.sql("""
                        SELECT id, login, password_hash, role, employee_id
                          FROM users
                         WHERE login = :login AND active
                        """)
                .param("login", login)
                .query((rs, _) -> new UserCredentials(
                        rs.getObject("id", UUID.class),
                        rs.getString("login"),
                        rs.getString("password_hash"),
                        Role.valueOf(rs.getString("role")),
                        rs.getObject("employee_id", UUID.class)))
                .optional();
    }
}
