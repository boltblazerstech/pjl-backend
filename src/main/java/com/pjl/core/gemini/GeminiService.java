package com.pjl.core.gemini;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiService {

    private final WebClient geminiWebClient;
    private final GeminiProperties geminiProperties;

    /**
     * Sends a prompt to the Gemini API and returns the text response.
     *
     * @param prompt the text prompt to send
     * @return the generated text response
     */
    public String generateContent(String prompt) {
        String endpoint = String.format("/v1beta/models/%s:generateContent", geminiProperties.model());

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", prompt)
                        ))
                )
        );

        try {
            Map<?, ?> response = geminiWebClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path(endpoint)
                            .queryParam("key", geminiProperties.key())
                            .build())
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            return extractTextFromResponse(response);
        } catch (WebClientResponseException e) {
            log.error("Gemini API error: {} - {}", e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new GeminiApiException("Failed to call Gemini API: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String extractTextFromResponse(Map<?, ?> response) {
        if (response == null) {
            throw new GeminiApiException("Received null response from Gemini API");
        }

        try {
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
            Map<String, Object> content = (Map<String, Object>) candidates.getFirst().get("content");
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            return (String) parts.getFirst().get("text");
        } catch (Exception e) {
            log.error("Failed to parse Gemini response: {}", response, e);
            throw new GeminiApiException("Failed to parse Gemini API response", e);
        }
    }
}
