package honey.road.node.it;

import honey.road.node.messaging.inbox.BranchInboxProcessor;
import honey.road.node.messaging.inbox.BranchInboxProcessor.Outcome;
import honey.road.node.persistence.PolicyRepository;
import honey.road.node.queue.application.BranchConfigService;
import honey.road.node.queue.domain.DomainException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Входящие сообщения центра (BRANCH_INBOX): конфигурация, пользователи, записи МП.
 * Транспорт (NATS) не участвует: сообщения подаются прямо в {@link BranchInboxProcessor}.
 */
class BranchInboxIT extends AbstractNodeIT {
    private static final String CONFIG = "center.config.branch.b01";
    private static final String MP = "center.mp.inbox.branch.b01";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    @Autowired BranchInboxProcessor inbox;
    @Autowired BranchConfigService config;
    @Autowired PolicyRepository policies;

    @AfterEach
    void cleanPeople() {
        jdbc.update("DELETE FROM users WHERE login LIKE 'ci-%'");
        jdbc.update("DELETE FROM employees WHERE full_name LIKE 'CI %'");
    }

    private static String configMessage(int version, String queue, String bounds) {
        return "{\"version\":" + version + ",\"scope\":\"branch\",\"queue\":" + queue + ",\"bounds\":" + bounds + "}";
    }

    private static final String BOUNDS = "{\"slack_min\":{\"min\":0,\"max\":15},\"call_timeout_sec\":{\"min\":30,\"max\":300}}";

    private long configApplied() {
        return count("SELECT count(*) FROM outbox WHERE subject LIKE '%config.applied'");
    }

    private static String slot(int hour) {
        return LocalDate.now(ZONE).plusDays(1).atTime(hour, 0).atZone(ZONE).toInstant().toString();
    }

    private static String mpMessage(String id, String service, String slot) {
        return "{\"mp_appointment_id\":\"" + id + "\",\"service_code\":\"" + service + "\",\"slot_start\":\"" + slot
                + "\",\"client_category\":\"default\",\"client_ref\":\"user-1\"}";
    }

    // ------------------------------------------------------------------ конфигурация

    @Test
    void newerCentralVersionIsAppliedAndConfirmed() {
        int before = policies.current().slackMin();
        assertThat(before).isEqualTo(5);

        var outcome = inbox.process(CONFIG, "cfg-1", configMessage(10, "{\"slack_min\":7}", BOUNDS));

        assertThat(outcome).isEqualTo(Outcome.APPLIED);
        assertThat(policies.current().slackMin()).isEqualTo(7);
        assertThat(config.view().centralVersion()).isEqualTo(10);
        assertThat(configApplied()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload::text FROM outbox WHERE subject LIKE '%config.applied'", String.class))
                .contains("\"central_version\": 10").contains("effective_version");
    }

    @Test
    void newTicketsCarryTheEffectiveConfigVersion() {
        inbox.process(CONFIG, "cfg-1", configMessage(10, "{\"slack_min\":7}", BOUNDS));
        var ticket = qr(PARCEL, "v");
        assertThat(count("SELECT config_version FROM tickets WHERE id=?", ticket))
                .isEqualTo(config.view().effectiveVersion());
    }

    @Test
    void partialQueueIsMergedOntoCurrentConfiguration() {
        inbox.process(CONFIG, "cfg-1", configMessage(10, "{\"target_wait_min\":{\"qr\":{\"default\":25}}}", BOUNDS));
        var policy = policies.current();
        assertThat(policy.targetWaitMin().get("qr").get("default")).isEqualTo(25);
        assertThat(policy.targetWaitMin().get("qr").get("svo")).isEqualTo(5);           // остальное не потеряно
        assertThat(policy.targetWaitMin().get("walkin").get("default")).isEqualTo(30);
    }

    @Test
    void sameMessageIdIsProcessedOnce() {
        var body = configMessage(10, "{\"slack_min\":7}", BOUNDS);
        assertThat(inbox.process(CONFIG, "cfg-1", body)).isEqualTo(Outcome.APPLIED);
        assertThat(inbox.process(CONFIG, "cfg-1", body)).isEqualTo(Outcome.DUPLICATE);
        assertThat(configApplied()).isEqualTo(1);
    }

    @Test
    void olderOrEqualVersionIsIgnored() {
        inbox.process(CONFIG, "cfg-1", configMessage(10, "{\"slack_min\":7}", BOUNDS));
        assertThat(inbox.process(CONFIG, "cfg-2", configMessage(10, "{\"slack_min\":9}", BOUNDS))).isEqualTo(Outcome.STALE);
        assertThat(inbox.process(CONFIG, "cfg-3", configMessage(9, "{\"slack_min\":1}", BOUNDS))).isEqualTo(Outcome.STALE);
        assertThat(policies.current().slackMin()).isEqualTo(7);
        assertThat(configApplied()).isEqualTo(1);
    }

    @Test
    void invalidConfigurationIsRejectedOnceAndLeavesPolicyUntouched() {
        var effectiveBefore = config.view().effectiveVersion();
        var bad = configMessage(10, "{\"slack_min\":-3}", BOUNDS);

        assertThat(inbox.process(CONFIG, "bad-1", bad)).isEqualTo(Outcome.REJECTED);

        assertThat(policies.current().slackMin()).isEqualTo(5);
        assertThat(config.view().effectiveVersion()).isEqualTo(effectiveBefore);
        assertThat(configApplied()).isZero();
        // Повторная доставка не зацикливается: сообщение уже отмечено обработанным.
        assertThat(inbox.process(CONFIG, "bad-1", bad)).isEqualTo(Outcome.DUPLICATE);
    }

    @Test
    void garbageAndUnknownSubjectAreDiscarded() {
        assertThat(inbox.process(CONFIG, "junk-1", "{not json")).isEqualTo(Outcome.REJECTED);
        assertThat(inbox.process("center.something.else", "junk-2", "{}")).isEqualTo(Outcome.REJECTED);
        assertThat(count("SELECT count(*) FROM inbox_processed WHERE message_id IN ('junk-1','junk-2')")).isEqualTo(2);
    }

    @Test
    void employeesAndUsersAreSyncedWithTheConfiguration() {
        var employee = UUID.randomUUID();
        var body = "{\"version\":10,\"scope\":\"branch\",\"queue\":{},\"bounds\":{},"
                + "\"employees\":[{\"id\":\"" + employee + "\",\"full_name\":\"CI Оператор\",\"active\":true}],"
                + "\"users\":[{\"id\":\"" + UUID.randomUUID() + "\",\"login\":\"ci-operator\",\"password_hash\":\"$2a$10$hash\","
                + "\"role\":\"OPERATOR\",\"employee_id\":\"" + employee + "\",\"active\":true}]}";

        assertThat(inbox.process(CONFIG, "people-1", body)).isEqualTo(Outcome.APPLIED);

        assertThat(count("SELECT count(*) FROM employees WHERE id=? AND active", employee)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE login='ci-operator' AND active", String.class))
                .isEqualTo("OPERATOR");

        // Следующая версия отключает пользователя флагом active=false; запись не удаляется.
        var off = "{\"version\":11,\"scope\":\"branch\",\"users\":[{\"id\":\"" + UUID.randomUUID()
                + "\",\"login\":\"ci-operator\",\"password_hash\":\"$2a$10$hash\",\"role\":\"OPERATOR\",\"active\":false}]}";
        assertThat(inbox.process(CONFIG, "people-2", off)).isEqualTo(Outcome.APPLIED);
        assertThat(count("SELECT count(*) FROM users WHERE login='ci-operator' AND NOT active")).isEqualTo(1);
    }

    @Test
    void userWithUnknownRoleRejectsTheWholeMessageAtomically() {
        var body = "{\"version\":10,\"scope\":\"branch\",\"queue\":{\"slack_min\":9},"
                + "\"users\":[{\"id\":\"" + UUID.randomUUID() + "\",\"login\":\"ci-root\",\"password_hash\":\"x\",\"role\":\"ROOT\"}]}";
        assertThat(inbox.process(CONFIG, "bad-role", body)).isEqualTo(Outcome.REJECTED);
        assertThat(policies.current().slackMin()).isEqualTo(5);                       // конфигурация тоже не применена
        assertThat(count("SELECT count(*) FROM users WHERE login='ci-root'")).isZero();
    }

    // ------------------------------------------------------------------ локальные переопределения

    @Test
    void branchAdminOverridesWithinBoundsAndCentralCanTightenThem() throws Exception {
        inbox.process(CONFIG, "cfg-1", configMessage(10, "{}", BOUNDS));

        var view = config.putOverrides(JSON.readTree("{\"slack_min\":12}"));
        assertThat(view.effective().slackMin()).isEqualTo(12);
        assertThat(policies.current().slackMin()).isEqualTo(12);

        // Центр сузил границы до 8: локальное значение 12 больше недопустимо и отбрасывается.
        inbox.process(CONFIG, "cfg-2", configMessage(11, "{}",
                "{\"slack_min\":{\"min\":0,\"max\":8},\"call_timeout_sec\":{\"min\":30,\"max\":300}}"));
        assertThat(policies.current().slackMin()).isEqualTo(5);
        assertThat(config.view().localOverrides().isEmpty()).isTrue();
        assertThat(jdbc.queryForObject("SELECT payload::text FROM outbox WHERE subject LIKE '%config.applied' ORDER BY seq DESC LIMIT 1",
                String.class)).contains("\"dropped_overrides\": 1");
    }

    @Test
    void overridesOutsideBoundsOrWithoutBoundsAreRejected() throws Exception {
        inbox.process(CONFIG, "cfg-1", configMessage(10, "{}", BOUNDS));
        var effectiveBefore = config.view().effectiveVersion();

        assertThatThrownBy(() -> config.putOverrides(JSON.readTree("{\"slack_min\":99}")))
                .isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("CONFIG_OUT_OF_BOUNDS");
        assertThatThrownBy(() -> config.putOverrides(JSON.readTree("{\"max_recalls\":9}")))
                .isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("CONFIG_OUT_OF_BOUNDS");
        assertThat(config.view().effectiveVersion()).isEqualTo(effectiveBefore);
    }

    @Test
    void emptyOverridesClearThemAll() throws Exception {
        inbox.process(CONFIG, "cfg-1", configMessage(10, "{}", BOUNDS));
        config.putOverrides(JSON.readTree("{\"slack_min\":12}"));
        config.putOverrides(JSON.readTree("{}"));
        assertThat(policies.current().slackMin()).isEqualTo(5);
    }

    // ------------------------------------------------------------------ записи МП

    @Test
    void mobileAppointmentBecomesBookedTicketAndConfirmationEvent() {
        var outcome = inbox.process(MP, "mp-100", mpMessage("mp-100", "parcel", slot(10)));

        assertThat(outcome).isEqualTo(Outcome.APPLIED);
        assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE mp_appointment_id='mp-100'", String.class)).isEqualTo("booked");
        var payload = jdbc.queryForObject("SELECT payload::text FROM outbox WHERE subject LIKE '%mp.appointment.confirmed'", String.class);
        assertThat(payload).contains("mp-100").contains("display_number").contains("token_hash");
        assertThat(count("SELECT count(*) FROM outbox WHERE subject LIKE '%mp.appointment.rejected'")).isZero();
    }

    @Test
    void redeliveredMobileAppointmentDoesNotDuplicateTicketOrEvent() {
        var body = mpMessage("mp-101", "parcel", slot(11));
        assertThat(inbox.process(MP, "mp-101", body)).isEqualTo(Outcome.APPLIED);
        assertThat(inbox.process(MP, "mp-101", body)).isEqualTo(Outcome.DUPLICATE);
        assertThat(count("SELECT count(*) FROM tickets WHERE mp_appointment_id='mp-101'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox WHERE subject LIKE '%mp.appointment.confirmed'")).isEqualTo(1);
    }

    @Test
    void appointmentForPassedSlotIsRejectedWithReason() {
        var past = Instant.now().minusSeconds(3600).toString();
        assertThat(inbox.process(MP, "mp-102", mpMessage("mp-102", "parcel", past))).isEqualTo(Outcome.REJECTED);

        assertThat(count("SELECT count(*) FROM tickets WHERE mp_appointment_id='mp-102'")).isZero();
        assertThat(jdbc.queryForObject("SELECT payload::text FROM outbox WHERE subject LIKE '%mp.appointment.rejected'", String.class))
                .contains("SLOT_EXPIRED").contains("mp-102");
        // Результат отправлен один раз: повтор доставки ничего не добавляет.
        assertThat(inbox.process(MP, "mp-102", mpMessage("mp-102", "parcel", past))).isEqualTo(Outcome.DUPLICATE);
        assertThat(count("SELECT count(*) FROM outbox WHERE subject LIKE '%mp.appointment.rejected'")).isEqualTo(1);
    }

    @Test
    void occupiedSlotAndUnknownServiceAreRejected() {
        var slot = slot(12);
        assertThat(inbox.process(MP, "mp-103", mpMessage("mp-103", "parcel", slot))).isEqualTo(Outcome.APPLIED);
        assertThat(inbox.process(MP, "mp-104", mpMessage("mp-104", "parcel", slot))).isEqualTo(Outcome.REJECTED);
        assertThat(inbox.process(MP, "mp-105", mpMessage("mp-105", "no-such-service", slot(13)))).isEqualTo(Outcome.REJECTED);

        assertThat(jdbc.queryForList("SELECT payload::text FROM outbox WHERE subject LIKE '%mp.appointment.rejected' ORDER BY seq", String.class))
                .hasSize(2)
                .satisfies(l -> assertThat(l.get(0)).contains("SLOT_UNAVAILABLE"))
                .satisfies(l -> assertThat(l.get(1)).contains("SERVICE_NOT_FOUND"));
        assertThat(count("SELECT count(*) FROM tickets WHERE mp_appointment_id IN ('mp-104','mp-105')")).isZero();
    }

    @Test
    void confirmationNeverLeaksTheClientSecret() {
        inbox.process(MP, "mp-106", mpMessage("mp-106", "parcel", slot(14)));
        var token = jdbc.queryForObject("SELECT public_token::text FROM tickets WHERE mp_appointment_id='mp-106'", String.class);
        var payload = jdbc.queryForObject("SELECT payload::text FROM outbox WHERE subject LIKE '%mp.appointment.confirmed'", String.class);
        assertThat(payload).doesNotContain(token);
    }
}
