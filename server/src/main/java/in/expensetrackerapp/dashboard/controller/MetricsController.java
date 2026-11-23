package in.expensetrackerapp.dashboard.controller;

import in.expensetrackerapp.dashboard.service.MetricsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** One call that returns everything the client's selectMetrics() computes, including the health score. */
@RestController
@RequestMapping("/api/dashboard")
public class MetricsController {

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    /** month = yyyy-MM (default current month) or "all". */
    @GetMapping({"/metrics", "/health"})
    public ResponseEntity<Map<String, Object>> metrics(Authentication authentication,
                                                       @RequestParam(required = false) String month) {
        return ResponseEntity.ok(metricsService.metrics(authentication.getName(), month));
    }
}
