package in.expensetrackerapp.voice;

import in.expensetrackerapp.common.exception.BadRequestException;
import in.expensetrackerapp.common.exception.UnsupportedFileTypeException;
import in.expensetrackerapp.dashboard.config.DashboardProperties;
import org.bson.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Endpoints the client's captureApi.ts already expects: multipart part "file" -> {"text": "..."}.
 * Both require the Bearer token (the client must add the header and the /api prefix).
 * They only READ the media and return text; nothing is saved, the app then shows its normal review form.
 */
@RestController
@RequestMapping("/api")
public class CaptureController {

    static final Set<String> AUDIO = Set.of("audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav", "audio/mp4",
            "audio/x-m4a", "audio/m4a", "audio/aac", "audio/ogg", "audio/webm", "audio/flac", "video/mp4", "video/webm");
    static final Set<String> IMAGE = Set.of("image/jpeg", "image/jpg", "image/png", "image/webp");

    private final GeminiClient gemini;
    private final DashboardProperties dashboard;
    private final Clock clock;

    public CaptureController(GeminiClient gemini, DashboardProperties dashboard, Clock clock) {
        this.gemini = gemini;
        this.dashboard = dashboard;
        this.clock = clock;
    }

    /** Voice recording -> {"text": "transcript"} */
    @PostMapping("/speech/transcribe")
    public ResponseEntity<Map<String, Object>> transcribe(@RequestParam("file") MultipartFile file) throws IOException {
        String type = type(file, AUDIO, "Unsupported audio type");
        return ResponseEntity.ok(Map.of("text", gemini.transcribe(file.getBytes(), type)));
    }

    /**
     * Receipt photo -> {"text": "Paid Rs 420 to Swiggy on 2026-11-19 by UPI", "expenses": [{merchant, amount, category,
     * direction, date, description}]}. "expenses" is extra structured data for apps that want to prefill a form.
     */
    @PostMapping("/receipts/parse")
    public ResponseEntity<Map<String, Object>> receipt(@RequestParam("file") MultipartFile file) throws IOException {
        String type = type(file, IMAGE, "Unsupported image type (use JPG, PNG or WebP)");
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(dashboard.getZone())));
        Document d = gemini.receipt(file.getBytes(), type, today);
        String text = d.getString("text");
        if (text == null || text.isBlank()) {
            throw new BadRequestException("No readable receipt was found in the image");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", text.strip());
        out.put("expenses", d.get("expenses"));
        return ResponseEntity.ok(out);
    }

    private static String type(MultipartFile file, Set<String> allowed, String message) {
        if (file.isEmpty()) {
            throw new BadRequestException("file is empty");
        }
        String t = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (!allowed.contains(t)) {
            throw new UnsupportedFileTypeException(message);
        }
        return t.equals("image/jpg") ? "image/jpeg" : t;
    }
}
