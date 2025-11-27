package com.example.genai.genai_backend.dto;

import java.util.List;
import java.util.Map;

public class ChatRequest {
    // Expected to be [{"role":"user","content":"..."}, ...]
    public List<Map<String, String>> messages;
}