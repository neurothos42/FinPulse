package in.expensetrackerapp.meta;

import in.expensetrackerapp.transaction.model.CategoryAliases;
import in.expensetrackerapp.transaction.model.TransactionCategory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** M6: one source for the vocabularies the client pickers need. */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    @GetMapping("/categories")
    public ResponseEntity<Map<String, Object>> categories() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categories", Arrays.stream(TransactionCategory.values()).map(TransactionCategory::getValue).toList());
        Map<String, String> aliases = new LinkedHashMap<>();
        CategoryAliases.ALIASES.forEach((k, v) -> aliases.put(k, v.getValue()));
        body.put("clientAliases", aliases);
        body.put("methods", List.of("UPI", "Credit Card", "Debit Card", "Cash", "Bank Transfer"));
        body.put("origins", List.of("DOCUMENT", "MANUAL", "IMPORT", "BILL", "VOICE", "RECEIPT"));
        body.put("directions", List.of("DEBIT", "CREDIT", "UNKNOWN"));
        return ResponseEntity.ok(body);
    }
}
