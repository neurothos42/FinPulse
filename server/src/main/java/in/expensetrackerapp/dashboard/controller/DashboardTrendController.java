package in.expensetrackerapp.dashboard.controller;

import in.expensetrackerapp.dashboard.dto.TrendResponse;
import in.expensetrackerapp.dashboard.service.DashboardTrendService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardTrendController {

    private final DashboardTrendService trendService;

    public DashboardTrendController(DashboardTrendService trendService) {
        this.trendService = trendService;
    }

    /** from/to are yyyy-MM and inclusive; default is the last 6 months ending with the current month. */
    @GetMapping("/trend")
    public ResponseEntity<TrendResponse> trend(
            Authentication authentication,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(trendService.trend(authentication.getName(), from, to));
    }
}
