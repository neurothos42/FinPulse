package in.expensetrackerapp.voice;

import in.expensetrackerapp.common.exception.BadRequestException;
import in.expensetrackerapp.common.exception.UnsupportedFileTypeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private static final Set<String> AUDIO_TYPES = Set.of("audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav",
            "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac", "audio/ogg", "audio/webm", "audio/flac");

    public record SpeechRequest(String speechToText) {
    }

    private final VoiceService voiceService;

    public VoiceController(VoiceService voiceService) {
        this.voiceService = voiceService;
    }

    /** Body: {"speechToText": "..."} (the app's on-device transcript). Returns the saved entry and created cash transactions. */
    @PostMapping(value = "/expenses", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> fromText(Authentication authentication, @RequestBody SpeechRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(voiceService.process(authentication.getName(), request.speechToText(), null, null));
    }

    /** multipart part "audio" or "file" (<= 10 MB): Gemini transcribes and structures in one call. */
    @PostMapping(value = "/expenses", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> fromAudio(Authentication authentication,
                                                         @RequestParam(value = "audio", required = false) MultipartFile audio,
                                                         @RequestParam(value = "file", required = false) MultipartFile file) throws IOException {
        if (audio == null) {
            audio = file;                      // the client's capture code names the part "file"
        }
        if (audio == null) {
            throw new BadRequestException("audio is required (multipart part \"audio\" or \"file\")");
        }
        if (audio.isEmpty()) {
            throw new BadRequestException("audio is empty");
        }
        String type = audio.getContentType() == null ? "" : audio.getContentType().toLowerCase().split(";")[0].trim();
        if (!AUDIO_TYPES.contains(type)) {
            throw new UnsupportedFileTypeException("Unsupported audio type");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(voiceService.process(authentication.getName(), null, audio.getBytes(), type));
    }

    @GetMapping("/entries")
    public ResponseEntity<List<Map<String, Object>>> list(Authentication authentication,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(voiceService.list(authentication.getName(), page, size));
    }

    @GetMapping("/entries/{id}")
    public ResponseEntity<Map<String, Object>> get(Authentication authentication, @PathVariable String id) {
        return ResponseEntity.ok(voiceService.get(authentication.getName(), id));
    }
}
