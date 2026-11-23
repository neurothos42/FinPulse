package in.expensetrackerapp.dashboard.controller;

import in.expensetrackerapp.dashboard.service.MetricsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Net worth, computed (never stored) with the client's formula (selectors.ts):
 * available (non-credit balances) + portfolio + card credit - card debt - loans outstanding.
 */
@RestController
@RequestMapping("/api/dashboard")
public class NetWorthController {

    private final MetricsService metrics;

    public NetWorthController(MetricsService metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/net-worth")
    public ResponseEntity<Map<String, Object>> netWorth(Authentication authentication) {
        Map<String, Object> m = metrics.metrics(authentication.getName(), "all");
        double available = (double) m.get("available"), portfolio = (double) m.get("portfolio"),
                cardCredit = (double) m.get("cardCredit"), cardDebt = (double) m.get("cardDebt"),
                loans = (double) m.get("loans");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cashAndBank", available);
        body.put("investments", portfolio);
        body.put("creditCardCredit", cardCredit);
        body.put("totalAssets", Math.round((available + portfolio + cardCredit) * 100) / 100.0);
        body.put("loansOutstanding", loans);
        body.put("creditCardDebt", cardDebt);
        body.put("creditCardLimit", m.get("cardLimit"));
        body.put("totalLiabilities", Math.round((loans + cardDebt) * 100) / 100.0);
        body.put("netWorth", m.get("netWorth"));
        return ResponseEntity.ok(body);
    }
}
