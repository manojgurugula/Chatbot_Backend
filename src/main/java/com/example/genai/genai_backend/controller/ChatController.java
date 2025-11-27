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

    // Read the Groq API key from environment variable named GROQ_API_KEY
    @Value("${GROQ_API_KEY:}")
    private String groqKey;

    @Value("${app.cors.allowedOrigins:http://localhost:5173}")
    private String allowedOrigins;

    private final ObjectMapper mapper = new ObjectMapper();
    private final SimpleRateLimiter rateLimiter = new SimpleRateLimiter(10, 60);

    // Build HttpClient once (default trusted certs)
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @CrossOrigin(origins = "${app.cors.allowedOrigins}")
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request) throws Exception {
        if (!rateLimiter.tryConsume()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests - slow down.");
        }

        if (groqKey == null || groqKey.isBlank()) {
            // Return 401 so client knows it's an auth / config issue on server
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing GROQ_API_KEY environment variable.");
        }

        if (request == null || request.messages == null || request.messages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messages array required");
        }

        // Choose a Groq model. If you get model-not-found, replace with a model listed by /models.
        String modelName = "llama-3-70b"; // conservative default — change if needed

        var body = Map.of(
                "model", modelName,
                "messages", request.messages,
                "max_tokens", 400,
                "temperature", 0.2
        );

        String requestBody = mapper.writeValueAsString(body);

        var httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + groqKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        System.out.println("Groq returned status: " + response.statusCode());
        System.out.println("Groq response body: " + response.body());

        if (response.statusCode() >= 400) {
            // Convert known Groq/OpenAI errors to proper HTTP status codes where possible
            int code = response.statusCode();
            if (code == 401 || code == 403) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Groq API auth error: " + response.body());
            }
            if (code == 429) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Groq quota/limit error: " + response.body());
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Groq API error: " + response.body());
        }

        // Return parsed JSON from Groq to client
        return mapper.readValue(response.body(), Map.class);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    // Optional: a quick endpoint to list models (helpful if you get "model not found")
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
