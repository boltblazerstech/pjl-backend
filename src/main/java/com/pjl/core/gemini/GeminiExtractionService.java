package com.pjl.core.gemini;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

@Slf4j
@Service
public class GeminiExtractionService {

    /** Max characters to log from a raw Gemini response (avoids flooding logs with base64). */
    private static final int RAW_LOG_MAX_CHARS = 4000;

    /** How many times to retry when Gemini returns an unparseable response. */
    private static final int PARSE_RETRY_MAX = 2;

    private final WebClient geminiWebClient;
    private final GeminiProperties geminiProperties;
    private final ObjectMapper lenientMapper;

    public GeminiExtractionService(WebClient geminiWebClient, GeminiProperties geminiProperties,
                                   ObjectMapper objectMapper) {
        this.geminiWebClient = geminiWebClient;
        this.geminiProperties = geminiProperties;
        // Lenient copy for parsing LLM output which may have non-standard JSON
        this.lenientMapper = objectMapper.copy()
                .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
                .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
                .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
                .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Extracts structured data from a single document file using the Gemini API.
     */
    public JsonNode extractFromDocument(byte[] fileBytes, String mimeType, String promptText) {
        return extractFromMultipleFiles(List.of(fileBytes), List.of(mimeType), promptText);
    }

    /**
     * Extracts structured data from multiple file pages in a single Gemini API call.
     * Each file is sent as a separate inlineData part so Gemini can see all pages together.
     * <p>
     * On parse failure, logs the raw response and retries the call up to
     * {@value PARSE_RETRY_MAX} additional times before throwing {@link ExtractionException}.
     */
    public JsonNode extractFromMultipleFiles(List<byte[]> fileBytesList, List<String> mimeTypes,
                                             String promptText) {
        for (int attempt = 0; attempt <= PARSE_RETRY_MAX; attempt++) {
            if (attempt > 0) {
                log.warn("[Gemini] Parse attempt {} / {} for document ({} files)",
                        attempt + 1, PARSE_RETRY_MAX + 1, fileBytesList.size());
            }

            String rawText;
            try {
                rawText = callGeminiApi(fileBytesList, mimeTypes, promptText);
            } catch (ExtractionException e) {
                // Network / API failure — propagate immediately (already retried inside)
                throw e;
            }

            // Log raw text BEFORE parsing so failures are always inspectable
            logRawResponse(rawText, attempt);

            String cleanJson = stripMarkdown(rawText);

            // 1st pass: parse the full cleaned response
            JsonNode result = tryParse(cleanJson);
            if (result != null) {
                return result;
            }

            // 2nd pass: extract the first balanced {...} block (handles trailing content,
            // double-concatenated objects, extra commentary after the JSON)
            String firstObject = extractFirstJsonObject(cleanJson);
            if (firstObject != null) {
                result = tryParse(firstObject);
                if (result != null) {
                    log.warn("[Gemini] Full response was malformed JSON; " +
                             "successfully parsed first valid object ({} chars extracted from {} chars).",
                             firstObject.length(), cleanJson.length());
                    return result;
                }
            }

            // 3rd pass: targeted regex repairs for known Gemini malformation patterns.
            // These are deterministic failures (same malformed output every time) where
            // retrying the API call would not help — repair the JSON text directly.
            String repaired = applyJsonRepairs(cleanJson);
            if (repaired != null) {
                result = tryParse(repaired);
                if (result != null) {
                    return result;
                }
                // Repaired text still didn't parse — try extractFirstJsonObject on it too
                String firstObjectOfRepaired = extractFirstJsonObject(repaired);
                if (firstObjectOfRepaired != null) {
                    result = tryParse(firstObjectOfRepaired);
                    if (result != null) {
                        log.warn("[Gemini] Parsed successfully after JSON repair + first-object extraction.");
                        return result;
                    }
                }
            }

            // All passes failed — log full raw text (not truncated) for diagnosis
            log.error("[Gemini] Parse failure on attempt {} — full raw response:\n{}",
                    attempt + 1, rawText);

            if (attempt == PARSE_RETRY_MAX) {
                throw new ExtractionException(
                        "Failed to extract data from document: Gemini returned unparseable JSON " +
                        "after " + (PARSE_RETRY_MAX + 1) + " attempt(s). " +
                        "Truncated raw text (first " + RAW_LOG_MAX_CHARS + " chars): " +
                        truncate(rawText, RAW_LOG_MAX_CHARS));
            }
            // else: loop → retry the API call
        }

        // Unreachable, but required by compiler
        throw new ExtractionException("Failed to extract data from document after all retries.");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Gemini API call
    // ─────────────────────────────────────────────────────────────────────────────

    private String callGeminiApi(List<byte[]> fileBytesList, List<String> mimeTypes,
                                 String promptText) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", promptText));

        for (int i = 0; i < fileBytesList.size(); i++) {
            String base64Data = Base64.getEncoder().encodeToString(fileBytesList.get(i));
            parts.add(Map.of("inlineData", Map.of(
                    "mimeType", mimeTypes.get(i),
                    "data", base64Data
            )));
        }

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of("parts", parts)),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json"
                )
        );

        String endpoint = String.format("/v1beta/models/%s:generateContent",
                geminiProperties.model());

        log.debug("[Gemini] Sending request ({} files, prompt length: {})",
                fileBytesList.size(), promptText.length());

        Map<?, ?> response;
        try {
            response = geminiWebClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path(endpoint)
                            .queryParam("key", geminiProperties.key())
                            .build())
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(120))
                    .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                            .filter(this::isRetryable)
                            .onRetryExhaustedThrow((spec, signal) ->
                                    new ExtractionException(
                                            "Gemini API call failed after retries: " +
                                            getMessageWithCause(signal.failure()),
                                            signal.failure())))
                    .block();
        } catch (org.springframework.web.reactive.function.client.WebClientRequestException e) {
            String cause = getMessageWithCause(e);
            log.error("[Gemini] WebClientRequestException (network/connection level): {}", cause);
            throw new ExtractionException("Gemini API connection failed: " + cause, e);
        } catch (Exception e) {
            String cause = getMessageWithCause(e);
            log.error("[Gemini] Unexpected exception during API call: {}", cause, e);
            throw new ExtractionException("Unexpected API failure: " + cause, e);
        }

        return extractTextFromResponse(response);
    }

    private String getMessageWithCause(Throwable t) {
        if (t == null) return "Unknown error";
        String msg = t.getMessage();
        if (t.getCause() != null && t.getCause() != t) {
            msg += " (Cause: " + t.getCause().getClass().getSimpleName() + " - " + t.getCause().getMessage() + ")";
        }
        return msg != null ? msg : t.getClass().getSimpleName();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // JSON parsing helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /** Returns parsed JsonNode, or null if parsing fails (does not throw). */
    private JsonNode tryParse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return lenientMapper.readTree(json);
        } catch (Exception e) {
            log.debug("[Gemini] Parse attempt failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts the first complete, balanced {@code {...}} block from {@code text}.
     * Handles cases where Gemini appends duplicate objects, commentary, or extra
     * whitespace after a valid JSON object.
     *
     * @return the substring containing just the first balanced object, or null if
     *         no balanced object could be found
     */
    private String extractFirstJsonObject(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        if (start < 0) return null;

        int depth = 0;
        boolean inString = false;
        boolean escape = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null; // unbalanced
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Retry / response helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private boolean isRetryable(Throwable throwable) {
        if (throwable instanceof TimeoutException) {
            log.warn("[Gemini] Timeout — retrying...");
            return true;
        }
        if (throwable instanceof WebClientResponseException wce) {
            boolean retry = wce.getStatusCode().is5xxServerError()
                    || wce.getStatusCode().value() == 429;
            if (retry) {
                log.warn("[Gemini] HTTP {} — retrying...", wce.getStatusCode().value());
            }
            return retry;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private String extractTextFromResponse(Map<?, ?> response) {
        if (response == null) {
            throw new ExtractionException("Received null response from Gemini API");
        }
        try {
            List<Map<String, Object>> candidates =
                    (List<Map<String, Object>>) response.get("candidates");
            if (candidates == null || candidates.isEmpty()) {
                throw new ExtractionException("No candidates returned from Gemini API");
            }
            Map<String, Object> content =
                    (Map<String, Object>) candidates.getFirst().get("content");
            List<Map<String, Object>> parts =
                    (List<Map<String, Object>>) content.get("parts");
            return (String) parts.getFirst().get("text");
        } catch (ExtractionException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Gemini] Failed to parse response structure: {}", response, e);
            throw new ExtractionException("Failed to parse Gemini response structure", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Logging / formatting helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private void logRawResponse(String rawText, int attempt) {
        if (rawText == null) {
            log.warn("[Gemini] Raw response is null (attempt {})", attempt + 1);
            return;
        }
        String preview = truncate(rawText, RAW_LOG_MAX_CHARS);
        log.debug("[Gemini] Raw response (attempt {}, {} chars total, showing first {}):\n{}",
                attempt + 1, rawText.length(), Math.min(rawText.length(), RAW_LOG_MAX_CHARS),
                preview);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // JSON repair patterns (for deterministic Gemini malformations)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Pattern 1 — missing closing brace before a comma+open-brace in an array.
     * Gemini sometimes omits the "}" that closes one array element before starting
     * the next, producing: {@code "amount": 243589.67 , { ...}
     * instead of:          {@code "amount": 243589.67 }, { ...}
     *
     * The regex matches: a number (or quoted string), optional whitespace/newlines,
     * then a comma, optional whitespace/newlines, then an open brace.
     * It inserts the missing "}" immediately before the comma.
     */
    private static final Pattern MISSING_CLOSE_BRACE =
            Pattern.compile("((?:\\d+(?:\\.\\d+)?|\"[^\"]*\"))\\s*,\\s*(\\{)");

    /**
     * Pattern 2 — missing comma between adjacent closing/opening braces.
     * Produces: {@code } { ...} instead of {@code }, { ...}
     */
    private static final Pattern MISSING_COMMA_BETWEEN_OBJECTS =
            Pattern.compile("\\}\\s*\\{");

    /**
     * Applies targeted regex repairs for known Gemini JSON malformation patterns.
     * <p>
     * Each repair is logged at WARN level so it's always visible in the console.
     *
     * @return the repaired JSON string if any repairs were applied; {@code null}
     *         if no known patterns were found (callers should skip re-parsing)
     */
    private String applyJsonRepairs(String json) {
        if (json == null || json.isBlank()) return null;

        String result = json;
        boolean anyRepaired = false;

        // Repair 1: insert missing "}" before ", {" inside arrays
        // "amount": 243589.67 , {  →  "amount": 243589.67 }, {
        String r1 = MISSING_CLOSE_BRACE.matcher(result).replaceAll(m -> {
            // Only apply when the match is inside an array context (preceded by a value,
            // not already a closing brace — that would be a valid object separator)
            // The full replacement inserts "}" before the comma.
            return m.group(1) + " }," + m.group(2);
        });
        if (!r1.equals(result)) {
            log.warn("[Gemini] JSON repair applied: inserted missing '}}' before ',{{' in array element. "
                    + "Before ({} chars) → After ({} chars). "
                    + "First occurrence context: ...{}...",
                    result.length(), r1.length(),
                    truncate(findFirstDiff(result, r1), 120));
            result = r1;
            anyRepaired = true;
        }

        // Repair 2: insert missing comma between adjacent objects "} {" → "}, {"
        String r2 = MISSING_COMMA_BETWEEN_OBJECTS.matcher(result).replaceAll("},{");
        if (!r2.equals(result)) {
            log.warn("[Gemini] JSON repair applied: inserted missing ',' between adjacent objects '}}{{' → '}},{{'. "
                    + "Char count: {} → {}", result.length(), r2.length());
            result = r2;
            anyRepaired = true;
        }

        return anyRepaired ? result : null;
    }

    /** Returns a short context string around the first character difference between two strings. */
    private String findFirstDiff(String a, String b) {
        int len = Math.min(a.length(), b.length());
        for (int i = 0; i < len; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                int start = Math.max(0, i - 40);
                int end   = Math.min(b.length(), i + 80);
                return b.substring(start, end);
            }
        }
        return b.substring(0, Math.min(80, b.length()));
    }

    /**
     * Removes markdown code fences (e.g. {@code ```json ... ```}) that Gemini
     * sometimes wraps around its JSON output.\n     */
    private String stripMarkdown(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.startsWith("```json")) {
            text = text.substring(7);
        } else if (text.startsWith("```")) {
            text = text.substring(3);
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3);
        }
        return text.trim();
    }

    private String truncate(String s, int maxChars) {
        if (s == null) return "(null)";
        return s.length() <= maxChars ? s : s.substring(0, maxChars) + "…[truncated]";
    }
}
