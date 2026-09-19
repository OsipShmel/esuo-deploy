package honey.road.node.api.admin;

import honey.road.node.queue.application.AdminCatalog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Справочники отделения (раздел 9.3, руководитель): услуги, окна, сотрудники. Роль {@code BRANCH_ADMIN} —
 * правило по префиксу {@code /admin} в {@code SecurityConfig}. Удаление мягкое, {@code DELETE} возвращает запись.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminCatalogController {
    private final AdminCatalog catalog;

    public AdminCatalogController(AdminCatalog catalog) {
        this.catalog = catalog;
    }

    public record ServiceRequest(String code, String name, String numberPrefix, Integer slotDurationMin,
                                 Integer slotCapacity, Boolean active) {
    }

    public record WindowRequest(String name, List<UUID> serviceIds) {
    }

    public record WindowServicesRequest(@NotEmpty List<UUID> serviceIds) {
    }

    public record EmployeeRequest(String fullName, Boolean active) {
    }

    // ---- услуги
    @GetMapping("/services")
    public List<AdminCatalog.ServiceView> services(@RequestParam(name = "include_inactive", defaultValue = "false") boolean all) {
        return catalog.services(all);
    }

    @GetMapping("/services/{id}")
    public AdminCatalog.ServiceView service(@PathVariable UUID id) {
        return catalog.service(id);
    }

    @PostMapping("/services")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminCatalog.ServiceView createService(@RequestBody ServiceRequest r) {
        return catalog.createService(r.code(), r.name(), r.numberPrefix(), r.slotDurationMin(), r.slotCapacity());
    }

    @PutMapping("/services/{id}")
    public AdminCatalog.ServiceView updateService(@PathVariable UUID id, @RequestBody ServiceRequest r) {
        return catalog.updateService(id, r.name(), r.numberPrefix(), r.slotDurationMin(), r.slotCapacity(), r.active());
    }

    @DeleteMapping("/services/{id}")
    public AdminCatalog.ServiceView deleteService(@PathVariable UUID id) {
        return catalog.deleteService(id);
    }

    // ---- окна
    @GetMapping("/windows")
    public List<AdminCatalog.WindowAdminView> windows(@RequestParam(name = "include_inactive", defaultValue = "false") boolean all) {
        return catalog.windows(all);
    }

    @GetMapping("/windows/{id}")
    public AdminCatalog.WindowAdminView window(@PathVariable UUID id) {
        return catalog.window(id);
    }

    @PostMapping("/windows")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminCatalog.WindowAdminView createWindow(@RequestBody WindowRequest r) {
        return catalog.createWindow(r.name(), r.serviceIds());
    }

    @PutMapping("/windows/{id}")
    public AdminCatalog.WindowAdminView renameWindow(@PathVariable UUID id, @RequestBody WindowRequest r) {
        return catalog.renameWindow(id, r.name());
    }

    @PutMapping("/windows/{id}/services")
    public AdminCatalog.WindowAdminView windowServices(@PathVariable UUID id, @Valid @RequestBody WindowServicesRequest r) {
        return catalog.replaceServices(id, r.serviceIds());
    }

    @DeleteMapping("/windows/{id}")
    public AdminCatalog.WindowAdminView deleteWindow(@PathVariable UUID id) {
        return catalog.deleteWindow(id);
    }

    // ---- сотрудники
    @GetMapping("/employees")
    public List<AdminCatalog.EmployeeView> employees(@RequestParam(name = "include_inactive", defaultValue = "false") boolean all) {
        return catalog.employees(all);
    }

    @GetMapping("/employees/{id}")
    public AdminCatalog.EmployeeView employee(@PathVariable UUID id) {
        return catalog.employee(id);
    }

    @PostMapping("/employees")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminCatalog.EmployeeView createEmployee(@RequestBody EmployeeRequest r) {
        return catalog.createEmployee(r.fullName());
    }

    @PutMapping("/employees/{id}")
    public AdminCatalog.EmployeeView updateEmployee(@PathVariable UUID id, @RequestBody EmployeeRequest r) {
        return catalog.updateEmployee(id, r.fullName(), r.active());
    }

    @DeleteMapping("/employees/{id}")
    public AdminCatalog.EmployeeView deleteEmployee(@PathVariable UUID id) {
        return catalog.deleteEmployee(id);
    }
}
