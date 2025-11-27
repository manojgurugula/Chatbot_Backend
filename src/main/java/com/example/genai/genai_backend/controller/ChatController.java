package com.example.genai.genai_backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.genai.genai_backend.dto.ChatRequest;
import com.example.genai.genai_backend.ratelimit.SimpleRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    // Read Groq API key from environment variable GROQ_API_KEY
    @Value("${GROQ_API_KEY:}")
    private String groqKey;

    // Read model from application.properties -> env var GROQ_MODEL
    @Value("${groq.model:meta-llama/llama-4-maverick-17b-128e-instruct}")
    private String groqModel;

    // CORS origin (optional)
    @Value("${app.cors.allowedOrigins:http://localhost:5173}")
    private String allowedOrigins;

    private final ObjectMapper mapper = new ObjectMapper();
    private final SimpleRateLimiter rateLimiter = new SimpleRateLimiter(10, 60);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @CrossOrigin(origins = "${app.cors.allowedOrigins}")
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request) throws Exception {
        // Basic rate limiting
        if (!rateLimiter.tryConsume()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests - slow down.");
        }

        // Ensure API key present
        if (groqKey == null || groqKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing GROQ_API_KEY environment variable.");
        }

        // Validate request
        if (request == null || request.messages == null || request.messages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messages array required");
        }

        // Prepare Groq (OpenAI-compatible) request body
        var body = Map.of(
                "model", groqModel,
                "messages", request.messages,
                "max_tokens", 400,
                "temperature", 0.2
        );
        String requestBody = mapper.writeValueAsString(body);

        // Build HTTP request to Groq
        var httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + groqKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        // Send request
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        // Log for debugging (Render logs)
        System.out.println("Groq returned status: " + response.statusCode());
        System.out.println("Groq response body: " + response.body());

        // Handle errors with appropriate HTTP codes
        if (response.statusCode() >= 400) {
            int code = response.statusCode();
            if (code == 401 || code == 403) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Groq API auth error: " + response.body());
            }
            if (code == 429) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Groq quota/limit error: " + response.body());
            }
            if (code == 404 && response.body().contains("model")) {
                // Model-not-found -> bad request so frontend can show better message
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Groq model error: " + response.body());
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Groq API error: " + response.body());
        }

        // Parse Groq response JSON and extract assistant reply
        Map<String, Object> parsed = mapper.readValue(response.body(), Map.class);
        String assistantText = extractAssistantText(parsed);

        // If we couldn't extract any content, return a friendly fallback message
        if (assistantText == null || assistantText.isBlank()) {
            assistantText = "No reply from model (empty response).";
        }

        // Return only the assistant text to the frontend
        return Map.of("response", assistantText);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    // /models endpoint helps you inspect available models for the account
    @GetMapping("/models")
    public Map<String, Object> models() throws Exception {
        if (groqKey == null || groqKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing GROQ_API_KEY environment variable.");
        }
        var req = HttpRequest.newBuilder()
                .uri(URI.create("https://api.groq.com/openai/v1/models"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + groqKey)
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new ResponseStatusException(HttpStatus.valueOf(resp.statusCode()), "Groq models error: " + resp.body());
        }
        return mapper.readValue(resp.body(), Map.class);
    }

    /**
     * Helper: safely extract assistant text from Groq/OpenAI-like response Map.
     * Supports:
     *  - choices[0].message.content
     *  - choices[0].text (older style)
     */
    private String extractAssistantText(Map<String, Object> parsed) {
        if (parsed == null) return null;

        try {
            Object choicesObj = parsed.get("choices");
            if (choicesObj instanceof List<?> choices && !choices.isEmpty()) {
                Object first = choices.get(0);
                if (first instanceof Map<?, ?> firstMap) {
                    // Try message.content
                    Object messageObj = firstMap.get("message");
                    if (messageObj instanceof Map<?, ?> messageMap) {
                        Object contentObj = messageMap.get("content");
                        if (contentObj instanceof String) {
                            return (String) contentObj;
                        }
                    }
                    // Fallback: choice.text
                    Object textObj = firstMap.get("text");
                    if (textObj instanceof String) {
                        return (String) textObj;
                    }
                }
            }

            // Some providers may put the text directly in "text" or other fields
            Object textRoot = parsed.get("text");
            if (textRoot instanceof String) {
                return (String) textRoot;
            }
        } catch (Exception e) {
            // swallow parse errors and return null - main code will handle fallback
            System.err.println("Failed to extract assistant text: " + e.getMessage());
        }

        return null;
    }
}
