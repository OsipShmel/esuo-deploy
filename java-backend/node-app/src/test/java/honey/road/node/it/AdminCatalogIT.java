package honey.road.node.it;

import honey.road.node.queue.application.AdminCatalog;
import honey.road.node.queue.domain.DomainException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Admin catalog (sect 9.3): services, windows, employees; soft delete; protection of what is in use. */
class AdminCatalogIT extends AbstractNodeIT {
    @Autowired AdminCatalog catalog;

    @AfterEach
    void cleanCreated() {
        // Test-created data must not leak into other classes (shared DB); the demo catalog stays.
        resetState();                                                        // tickets and journals first: services are referenced by FKs
        jdbc.update("DELETE FROM window_services WHERE service_id IN (SELECT id FROM services WHERE code LIKE 'ci-%')");
        jdbc.update("DELETE FROM window_services WHERE window_id IN (SELECT id FROM windows WHERE name LIKE 'CI %')");
        jdbc.update("DELETE FROM windows WHERE name LIKE 'CI %'");
        jdbc.update("DELETE FROM services WHERE code LIKE 'ci-%'");
        jdbc.update("DELETE FROM employees WHERE full_name LIKE 'CI %'");
        jdbc.update("UPDATE services SET active=true");
        jdbc.update("UPDATE windows SET active=true");
        jdbc.update("UPDATE employees SET active=true");
    }

    private static String code(DomainException e) {
        return e.code();
    }

    // ------------------------------------------------------------------ services

    @Test
    void serviceIsCreatedWithDefaultsFromConfigurationAndAppearsInTheBranchCatalog() {
        var s = catalog.createService("ci-passport", "CI Паспорта", "Ц", null, null);

        assertThat(s.active()).isTrue();
        assertThat(s.slotDurationMin()).isEqualTo(15);                       // from queue-defaults
        assertThat(s.slotCapacity()).isEqualTo(1);
        assertThat(queries.branch().get("services").toString()).contains("CI Паспорта");
        // A new service can issue tickets right away.
        assertThat(qrView(s.id(), "phone").displayNumber()).startsWith("Ц-");
    }

    @Test
    void serviceCodeAndPrefixMustBeUniqueAndValid() {
        catalog.createService("ci-a", "CI А", "Щ", 10, 2);
        assertThat(code(catchDomain(() -> catalog.createService("ci-a", "CI Б", "Э", 10, 2)))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(catchDomain(() -> catalog.createService("ci-b", "CI Б", "Щ", 10, 2)))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(catchDomain(() -> catalog.createService("Bad Code", "x", "Ю", 10, 2)))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(catchDomain(() -> catalog.createService("ci-c", "x", "ДЛИННЫЙ", 10, 2)))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(catchDomain(() -> catalog.createService("ci-d", "x", "Я", 0, 2)))).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void updateChangesFieldsButNotTheCode() {
        var s = catalog.createService("ci-upd", "CI Было", "Ж", 15, 1);
        var u = catalog.updateService(s.id(), "CI Стало", null, 20, 3, null);
        assertThat(u.code()).isEqualTo("ci-upd");
        assertThat(u.name()).isEqualTo("CI Стало");
        assertThat(u.slotDurationMin()).isEqualTo(20);
        assertThat(u.slotCapacity()).isEqualTo(3);
        assertThat(u.numberPrefix()).isEqualTo("Ж");
    }

    @Test
    void deleteIsSoftAndBlockedWhileTicketsAreInWork() {
        var s = catalog.createService("ci-del", "CI Удаляемая", "Ы", 15, 1);
        var ticket = qr(s.id(), "busy");

        assertThat(code(catchDomain(() -> catalog.deleteService(s.id())))).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(catalog.service(s.id()).active()).isTrue();

        cancel.execute(queries.get(jdbc.queryForObject("SELECT public_token FROM tickets WHERE id=?", UUID.class, ticket)).token());
        var deleted = catalog.deleteService(s.id());

        assertThat(deleted.active()).isFalse();
        assertThat(catalog.services(false)).extracting(AdminCatalog.ServiceView::id).doesNotContain(s.id());
        assertThat(catalog.services(true)).extracting(AdminCatalog.ServiceView::id).contains(s.id());
        assertThat(count("SELECT count(*) FROM services WHERE id=?", s.id())).isEqualTo(1);     // record kept
        assertThat(code(catchDomain(() -> qrView(s.id(), "again")))).isEqualTo("SERVICE_NOT_FOUND");   // issuing closed
    }

    // ------------------------------------------------------------------ windows

    @Test
    void windowIsCreatedClosedWithServicesAndServiceListCanBeReplaced() {
        var w = catalog.createWindow("CI Окно 9", List.of(PARCEL));
        assertThat(w.status()).isEqualTo("closed");
        assertThat(w.serviceIds()).containsExactly(PARCEL);

        var replaced = catalog.replaceServices(w.id(), List.of(SEND, FINANCE, SEND));
        assertThat(replaced.serviceIds()).containsExactlyInAnyOrder(SEND, FINANCE);        // duplicates collapsed

        assertThat(code(catchDomain(() -> catalog.replaceServices(w.id(), List.of())))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(catchDomain(() -> catalog.replaceServices(w.id(), List.of(UUID.randomUUID()))))).isEqualTo("SERVICE_NOT_FOUND");
    }

    @Test
    void newWindowCanBeOpenedAndServesClients() {
        var w = catalog.createWindow("CI Окно 10", List.of(PARCEL));
        windows.open(w.id(), List.of(PARCEL), E1);
        qr(PARCEL, "x");
        assertThat(callNext.execute(w.id(), null).window().id()).isEqualTo(w.id());
    }

    @Test
    void openWindowCannotBeDeletedAndDeletedWindowDisappears() {
        var w = catalog.createWindow("CI Окно 11", List.of(PARCEL));
        windows.open(w.id(), List.of(PARCEL), E1);
        assertThat(code(catchDomain(() -> catalog.deleteWindow(w.id())))).isEqualTo("INVALID_STATE_TRANSITION");

        windows.close(w.id(), "graceful", E1);
        var deleted = catalog.deleteWindow(w.id());
        assertThat(deleted.active()).isFalse();
        assertThat(catalog.windows(false)).extracting(AdminCatalog.WindowAdminView::id).doesNotContain(w.id());
        assertThat(catalog.windows(true)).extracting(AdminCatalog.WindowAdminView::id).contains(w.id());
        // A deleted window cannot be opened and is gone from operator and analytics.
        assertThat(code(catchDomain(() -> windows.open(w.id(), List.of(PARCEL), E1)))).isEqualTo("WINDOW_NOT_FOUND");
        assertThat(code(catchDomain(() -> catalog.renameWindow(w.id(), "CI Новое")))).isEqualTo("WINDOW_NOT_FOUND");
        assertThat(catalog.deleteWindow(w.id()).active()).isFalse();                        // repeat is idempotent
    }

    // ------------------------------------------------------------------ employees

    @Test
    void employeeLifecycleAndProtectionOfOpenWindows() {
        var e = catalog.createEmployee("CI Пётр");
        assertThat(e.active()).isTrue();
        assertThat(catalog.updateEmployee(e.id(), "CI Пётр Иванов", null).fullName()).isEqualTo("CI Пётр Иванов");

        openWindow(W1, e.id(), PARCEL);
        assertThat(code(catchDomain(() -> catalog.deleteEmployee(e.id())))).isEqualTo("INVALID_STATE_TRANSITION");

        windows.close(W1, "graceful", e.id());
        assertThat(catalog.deleteEmployee(e.id()).active()).isFalse();
        assertThat(catalog.employees(false)).extracting(AdminCatalog.EmployeeView::id).doesNotContain(e.id());
        assertThat(catalog.employees(true)).extracting(AdminCatalog.EmployeeView::id).contains(e.id());
        assertThat(code(catchDomain(() -> catalog.createEmployee("  ")))).isEqualTo("VALIDATION_ERROR");
    }

    private static DomainException catchDomain(Runnable action) {
        return (DomainException) assertThatThrownBy(action::run).isInstanceOf(DomainException.class).actual();
    }
}
