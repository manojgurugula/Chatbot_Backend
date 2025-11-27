package com.example.genai.genai_backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.genai.genai_backend.dto.ChatRequest;
import com.example.genai.genai_backend.ratelimit.SimpleRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;


@RestController
@RequestMapping("/api")
public class ChatController {

    @Value("${openai.api.key:}")
    private String openaiKey;

    @Value("${app.cors.allowedOrigins:http://localhost:5173}")
    private String allowedOrigins;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    private final SimpleRateLimiter rateLimiter = new SimpleRateLimiter(10, 60);

    @CrossOrigin(origins = "${app.cors.allowedOrigins}")
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request) throws Exception {
        if (!rateLimiter.tryConsume()) {
            throw new RateLimitException("Too many requests - slow down.");
        }

        if (openaiKey == null || openaiKey.isBlank()) {
            throw new RuntimeException("Missing OPENAI_API_KEY environment variable.");
        }

        if (request == null || request.messages == null || request.messages.isEmpty()) {
            throw new IllegalArgumentException("messages array required");
        }

        var body = Map.of(
                "model", "gpt-3.5-turbo",       // safe test model
                "messages", request.messages,
                "max_tokens", 400,
                "temperature", 0.2
        );

        String requestBody = mapper.writeValueAsString(body);

        var httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + openaiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        System.out.println("OpenAI returned status: " + response.statusCode());
        System.out.println("OpenAI response body: " + response.body());

        if (response.statusCode() >= 400) {
            throw new RuntimeException("OpenAI API error: " + response.statusCode() + " - " + response.body());
        }

        return mapper.readValue(response.body(), Map.class);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @ResponseStatus(code = HttpStatus.TOO_MANY_REQUESTS, reason = "Too many requests")
    private static class RateLimitException extends RuntimeException {
        public RateLimitException(String message) { super(message); }
    }
}