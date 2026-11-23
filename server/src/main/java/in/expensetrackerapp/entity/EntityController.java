package in.expensetrackerapp.entity;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Uniform REST for {accounts,budgets,goals,holdings,bills,loans,splits,festivals} (API_INTEGRATION_MAPPING s.11). */
@RestController
@RequestMapping("/api")
public class EntityController {

    private static final String R = "{resource:accounts|budgets|goals|holdings|bills|loans|splits|festivals}";

    private final EntityService service;

    public EntityController(EntityService service) {
        this.service = service;
    }

    @GetMapping("/" + R)
    public ResponseEntity<List<Map<String, Object>>> list(Authentication a, @PathVariable String resource) {
        return ResponseEntity.ok(service.list(a.getName(), resource));
    }

    @GetMapping("/" + R + "/{id}")
    public ResponseEntity<Map<String, Object>> get(Authentication a, @PathVariable String resource, @PathVariable String id) {
        return ResponseEntity.ok(service.get(a.getName(), resource, id));
    }

    @PostMapping("/" + R)
    public ResponseEntity<Map<String, Object>> create(Authentication a, @PathVariable String resource,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> created = service.create(a.getName(), resource, safe(body));
        return ResponseEntity.created(URI.create("/api/" + resource + "/" + created.get("id"))).body(created);
    }

    @PutMapping("/" + R + "/{id}")
    public ResponseEntity<Map<String, Object>> update(Authentication a, @PathVariable String resource,
                                                      @PathVariable String id,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(service.update(a.getName(), resource, id, safe(body)));
    }

    @DeleteMapping("/" + R + "/{id}")
    public ResponseEntity<Void> delete(Authentication a, @PathVariable String resource, @PathVariable String id) {
        service.delete(a.getName(), resource, id);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @PostMapping("/goals/{id}/contributions")
    public ResponseEntity<Map<String, Object>> contribute(Authentication a, @PathVariable String id,
                                                          @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(service.contribute(a.getName(), id, safe(body)));
    }

    @PostMapping("/bills/{id}/pay")
    public ResponseEntity<Map<String, Object>> pay(Authentication a, @PathVariable String id,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(service.payBill(a.getName(), id, safe(body)));
    }

    private static Map<String, Object> safe(Map<String, Object> body) {
        return body == null ? new HashMap<>() : body;
    }
}
