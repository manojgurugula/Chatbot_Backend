package com.example.genai.genai_backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.genai.genai_backend.dto.ChatRequest;
import com.example.genai.genai_backend.ratelimit.SimpleRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    @Value("${GROQ_API_KEY:}")
    private String groqKey;

    @Value("${groq.model:meta-llama/llama-4-maverick-17b-128e-instruct}")
    private String groqModel;

    @Value("${app.cors.allowedOrigins:http://localhost:5173}")
    private String allowedOrigins;

    private final ObjectMapper mapper = new ObjectMapper();
    private final SimpleRateLimiter rateLimiter = new SimpleRateLimiter(10, 60);

    // Use HTTP_1_1 and a slightly longer timeout to avoid EOF/HTTP2 issues
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @CrossOrigin(origins = "${app.cors.allowedOrigins}")
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request) throws Exception {
        if (!rateLimiter.tryConsume()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests - slow down.");
        }

        if (groqKey == null || groqKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing GROQ_API_KEY environment variable.");
        }

        if (request == null || request.messages == null || request.messages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messages array required");
        }

        var body = Map.of(
                "model", groqModel,
                "messages", request.messages,
                "max_tokens", 400,
                "temperature", 0.2
        );
        String requestBody = mapper.writeValueAsString(body);

        var httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + groqKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        // retry once on IO error to handle transient connection/EOF problems
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                break; // success
            } catch (IOException e) {
                // log
                System.err.println("Attempt " + attempts + " failed when calling Groq: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                if (attempts >= 2) {
                    // after retry fails, return a 502 so frontend can display an error
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Upstream Groq I/O error: " + e.getMessage());
                }
                // small backoff before retry
                try { Thread.sleep(250); } catch (InterruptedException ignored) {}
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Request interrupted");
            }
        }

        // logging for debugging
        System.out.println("Groq returned status: " + response.statusCode());
        System.out.println("Groq response body: " + response.body());

        if (response.statusCode() >= 400) {
            int code = response.statusCode();
            if (code == 401 || code == 403) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Groq API auth error: " + response.body());
            }
            if (code == 429) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Groq quota/limit error: " + response.body());
            }
            if (code == 404 && response.body() != null && response.body().contains("model")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Groq model error: " + response.body());
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Groq API error: " + response.body());
        }

        // return raw parsed JSON map so frontend receives same object from Groq
        return mapper.readValue(response.body(), Map.class);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

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
}