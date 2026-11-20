package com.bytedance.douyinclouddemo.service;

import com.bytedance.douyinclouddemo.config.DeepSeekProperties;
import com.bytedance.douyinclouddemo.model.ChatResult;
import com.bytedance.douyinclouddemo.model.ChatUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class DeepSeekService {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private final DeepSeekProperties properties;
    private final AiGate gate;
    private final UsageLedgerService ledger;
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient client = new OkHttpClient();

    public DeepSeekService(DeepSeekProperties properties, AiGate gate, UsageLedgerService ledger) {
        this.properties = properties;
        this.gate = gate;
        this.ledger = ledger;
    }

    public Map<String, Object> config() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("paused", gate.isPaused());
        map.put("hasKey", properties.getApiKey() != null && !properties.getApiKey().isEmpty());
        map.put("keyFingerprint", fingerprint(properties.getApiKey()));
        map.put("baseUrl", properties.getBaseUrl());
        map.put("model", properties.getModel());
        map.put("thinking", properties.isThinking());
        map.put("timeoutMs", properties.getTimeoutMs());
        return map;
    }

    public String fingerprint(String key) {
        if (key == null || key.isEmpty()) return null;
        return "sk-…" + key.substring(Math.max(0, key.length() - 4));
    }

    public ChatResult chat(String agent, String purpose, String system, String user,
                           int maxTokens, double temperature, Integer timeoutMs, int retries) {
        ChatResult result = new ChatResult();
        if (gate.isPaused()) {
            result.setErrorType("ai_paused");
            result.setErrorMessage("AI 调用已暂停");
            return result;
        }
        if (properties.getApiKey() == null || properties.getApiKey().isEmpty()) {
            result.setErrorType("no_key");
            result.setErrorMessage("未配置 DEEPSEEK_API_KEY");
            return result;
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", properties.getModel());
        body.putArray("messages").addObject().put("role", "system").put("content", system);
        ((com.fasterxml.jackson.databind.node.ArrayNode) body.get("messages"))
                .addObject().put("role", "user").put("content", String.valueOf(user));
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("stream", false);
        body.putObject("response_format").put("type", "json_object");
        if (!properties.isThinking()) {
            body.putObject("thinking").put("type", "disabled");
        }

        ChatResult last = result;
        for (int attempt = 1; attempt <= retries + 1; attempt++) {
            long started = System.currentTimeMillis();
            int status = 0;
            String errorType = null;
            String message = "";
            JsonNode payload = null;
            try {
                int timeout = timeoutMs == null ? properties.getTimeoutMs() : timeoutMs;
                OkHttpClient callClient = client.newBuilder()
                        .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                        .readTimeout(timeout, TimeUnit.MILLISECONDS)
                        .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                        .callTimeout(timeout, TimeUnit.MILLISECONDS)
                        .build();
                Request request = new Request.Builder()
                        .url(properties.getBaseUrl().replaceAll("/+$", "") + "/chat/completions")
                        .header("Authorization", "Bearer " + properties.getApiKey())
                        .post(RequestBody.create(JSON, mapper.writeValueAsBytes(body)))
                        .build();
                try (Response response = callClient.newCall(request).execute()) {
                    status = response.code();
                    String text = response.body() == null ? "" : response.body().string();
                    try {
                        payload = mapper.readTree(text);
                    } catch (Exception ignored) {
                    }
                    if (!response.isSuccessful()) {
                        errorType = classify(status);
                        String apiMessage = payload != null && payload.path("error").has("message")
                                ? payload.path("error").path("message").asText() : text;
                        message = apiMessage.length() > 200 ? apiMessage.substring(0, 200) : apiMessage;
                    }
                }
            } catch (java.net.SocketTimeoutException e) {
                errorType = "timeout";
                message = "timeout";
            } catch (Exception e) {
                errorType = "network";
                message = e.getMessage() == null ? "network error" : e.getMessage();
            }

            long latency = System.currentTimeMillis() - started;
            ChatUsage usage = normalizeUsage(payload == null ? null : payload.get("usage"));
            boolean ok = errorType == null;
            ledger.record(agent, purpose, properties.getModel(), ok, errorType, status, latency, attempt, usage);

            if (ok) {
                JsonNode choice = payload.path("choices").path(0);
                String content = choice.path("message").path("content").asText("");
                result.setOk(true);
                result.setContent(content);
                result.setData(parseMaybeJson(content));
                result.setUsage(usage);
                result.setLatencyMs(latency);
                result.setFinishReason(choice.path("finish_reason").asText(null));
                return result;
            }

            result.setErrorType(errorType);
            result.setErrorMessage(message);
            result.setUsage(usage);
            result.setLatencyMs(latency);
            last = result;
            boolean retryable = "rate_limit".equals(errorType) || "server".equals(errorType)
                    || "timeout".equals(errorType) || "network".equals(errorType);
            if (!retryable || attempt > retries) break;
            try {
                Thread.sleep(300L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return last;
    }

    public Map<String, Object> balance() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (properties.getApiKey() == null || properties.getApiKey().isEmpty()) {
            map.put("ok", false);
            map.put("error", "no_key");
            return map;
        }
        try {
            Request request = new Request.Builder()
                    .url(properties.getBaseUrl().replaceAll("/+$", "") + "/user/balance")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .build();
            try (Response response = client.newCall(request).execute()) {
                String text = response.body() == null ? "" : response.body().string();
                map.put("ok", response.isSuccessful());
                if (response.isSuccessful()) map.put("data", mapper.readTree(text));
                else map.put("error", "HTTP " + response.code());
            }
        } catch (Exception e) {
            map.put("ok", false);
            map.put("error", e.getMessage());
        }
        return map;
    }

    private JsonNode parseMaybeJson(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        try {
            return mapper.readTree(trimmed);
        } catch (Exception ignored) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return mapper.readTree(trimmed.substring(start, end + 1));
                } catch (Exception ignored2) {
                }
            }
            return null;
        }
    }

    private ChatUsage normalizeUsage(JsonNode usage) {
        ChatUsage result = new ChatUsage();
        if (usage == null || usage.isNull()) return result;
        int prompt = usage.path("prompt_tokens").asInt(0);
        int completion = usage.path("completion_tokens").asInt(0);
        int hit = usage.path("prompt_cache_hit_tokens").asInt(0);
        int miss = usage.path("prompt_cache_miss_tokens").asInt(0);
        if (miss == 0) miss = Math.max(0, prompt - hit);
        result.setPromptTokens(prompt);
        result.setCompletionTokens(completion);
        result.setTotalTokens(usage.path("total_tokens").asInt(prompt + completion));
        result.setCacheHitTokens(hit);
        result.setCacheMissTokens(miss);
        result.setReasoningTokens(usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0));
        return result;
    }

    private String classify(int status) {
        if (status == 401 || status == 403) return "auth";
        if (status == 402) return "insufficient_balance";
        if (status == 429) return "rate_limit";
        if (status >= 500) return "server";
        if (status == 400) return "bad_request";
        return "network";
    }
}
