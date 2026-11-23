package in.expensetrackerapp.voice;

import in.expensetrackerapp.common.exception.ServiceUnavailableException;
import in.expensetrackerapp.transaction.model.TransactionCategory;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Calls Gemini generateContent and asks for STRICT JSON (responseSchema), so one spoken sentence containing several
 * expenses comes back as a list. Text mode: the app sends its on-device transcript. Audio mode: the audio is sent
 * inline and Gemini also returns the transcript in {@code speechToText}.
 * The API key is sent in the x-goog-api-key header and never logged.
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    private final GeminiProperties props;
    private final RestClient http;

    public GeminiClient(GeminiProperties props) {
        this.props = props;
        Duration timeout = Duration.ofSeconds(Math.max(5, props.getTimeoutSeconds()));
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** @return the parsed JSON object: {speechToText?: string, expenses: [{merchant, amount, category, direction, date?, description?}]} */
    public Document extract(String transcript, byte[] audio, String audioMime, LocalDate today) {
        List<Map<String, Object>> parts = audio != null
                ? List.of(inline(audio, audioMime),
                          Map.of("text", "Transcribe this audio and extract every money transaction mentioned."))
                : List.of(Map.of("text", "Transcript:\n" + transcript));
        return call(parts, systemPrompt(today), schema());
    }

    /** Speech to text only (replaces the client's /speech/transcribe contract: {text}). */
    public String transcribe(byte[] audio, String audioMime) {
        Document d = call(List.of(inline(audio, audioMime), Map.of("text", "Transcribe this audio exactly. Return only the words spoken.")),
                "You are a speech-to-text engine. Transcribe faithfully in the language spoken (English, Hindi or Hinglish). Do not add commentary.",
                Map.of("type", "OBJECT", "properties", Map.of("speechToText", Map.of("type", "STRING")),
                        "required", List.of("speechToText")));
        String text = d.getString("speechToText");
        if (text == null || text.isBlank()) {
            throw new ServiceUnavailableException("No speech could be recognised in the audio");
        }
        return text.strip();
    }

    /**
     * Receipt / bill photo -> {text, expenses[]}. {@code text} is one plain sentence per transaction
     * ("Paid Rs 420 to Swiggy on 2026-11-19 by UPI") because the client's parseAlert() reads that format.
     */
    @SuppressWarnings("unchecked")
    public Document receipt(byte[] image, String imageMime, LocalDate today) {
        Map<String, Object> item = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) schema().get("properties")).get("expenses")).get("items");
        return call(List.of(inline(image, imageMime), Map.of("text", "Read this receipt, bill or payment screenshot.")),
                "You read receipts, bills and payment screenshots. Today's date is " + today + ". "
                        + "Return the final amount paid (the grand total, not line items), the merchant name, the date (yyyy-MM-dd, else today), "
                        + "a category, direction DEBIT, and the payment method if visible. "
                        + "text must be ONE short English sentence per transaction like: Paid Rs 420 to Swiggy on 2026-11-19 by UPI. "
                        + "If the image is not a receipt or has no readable total, return empty text and an empty expenses array. Never invent numbers.",
                Map.of("type", "OBJECT", "properties", Map.of("text", Map.of("type", "STRING"),
                        "expenses", Map.of("type", "ARRAY", "items", item)), "required", List.of("text", "expenses")));
    }

    private static Map<String, Object> inline(byte[] data, String mime) {
        return Map.of("inlineData", Map.of("mimeType", mime, "data", Base64.getEncoder().encodeToString(data)));
    }

    @SuppressWarnings("unchecked")
    private Document call(List<Map<String, Object>> parts, String system, Map<String, Object> schema) {
        if (!props.isConfigured()) {
            throw new ServiceUnavailableException("This feature is not configured on the server (GEMINI_API_KEY is missing)");
        }
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
                "contents", List.of(Map.of("role", "user", "parts", parts)),
                "generationConfig", Map.of("temperature", 0, "responseMimeType", "application/json",
                        "responseSchema", schema));
        Map<String, Object> response;
        try {
            response = http.post().uri(props.getApiUrl())
                    .header("x-goog-api-key", props.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(Map.class);
        } catch (RestClientResponseException e) {
            log.warn("Gemini call failed with HTTP {}", e.getStatusCode().value());
            throw new ServiceUnavailableException("AI service returned an error (" + e.getStatusCode().value() + ")");
        } catch (RestClientException e) {
            log.warn("Gemini call failed: {}", e.getClass().getSimpleName());
            throw new ServiceUnavailableException("AI service could not be reached");
        }
        try {
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
            Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
            String text = (String) ((Map<String, Object>) ((List<?>) content.get("parts")).get(0)).get("text");
            return Document.parse(text);                       // BSON's JSON reader: standard JSON, no extra dependency
        } catch (RuntimeException e) {
            log.warn("Unexpected Gemini response shape: {}", e.getClass().getSimpleName());
            throw new ServiceUnavailableException("AI service returned an unreadable answer. Please try again");
        }
    }

    private static String systemPrompt(LocalDate today) {
        return "You extract personal finance transactions from what a user said (any language, e.g. English, Hindi, Hinglish). "
                + "Today's date is " + today + ". Return one item per separate payment or receipt, even if several are in one sentence. "
                + "Amounts are in Indian rupees unless stated; return a plain number without symbols or commas. "
                + "direction is DEBIT for money spent and CREDIT only for money received. "
                + "merchant is the shop/person/service (short, Title Case); if none is said use a short noun such as 'Tea' or 'Auto'. "
                + "category must be one of the allowed values. date is yyyy-MM-dd; resolve words like yesterday/kal/parso relative to today; "
                + "if no date is said use today. description is a short phrase from the speech. "
                + "Ignore anything that is not a transaction. If there are no transactions return an empty expenses array. "
                + "Never invent amounts the user did not say.";
    }

    private static Map<String, Object> schema() {
        List<String> categories = Arrays.stream(TransactionCategory.values())
                .map(TransactionCategory::getValue).filter(c -> !c.equals("Transfer")).toList();
        Map<String, Object> item = Map.of("type", "OBJECT",
                "properties", Map.of(
                        "merchant", Map.of("type", "STRING"),
                        "amount", Map.of("type", "NUMBER"),
                        "category", Map.of("type", "STRING", "enum", categories),
                        "direction", Map.of("type", "STRING", "enum", List.of("DEBIT", "CREDIT")),
                        "date", Map.of("type", "STRING"),
                        "description", Map.of("type", "STRING")),
                "required", List.of("merchant", "amount", "category", "direction"));
        return Map.of("type", "OBJECT",
                "properties", Map.of("speechToText", Map.of("type", "STRING"),
                        "expenses", Map.of("type", "ARRAY", "items", item)),
                "required", List.of("expenses"));
    }
}
