package io.github.seongeun308.safecheck.client;

import io.github.seongeun308.safecheck.config.SafecheckProperties;
import io.github.seongeun308.safecheck.dto.JudgementResponse;
import io.github.seongeun308.safecheck.support.JudgementPromptBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 판정 모델을 실제로 호출한다.
 *
 * <p>비용이 드는 유일한 지점이므로 다음을 지킨다.
 * <ul>
 *   <li>호출 횟수와 토큰 사용량을 매번 기록한다.
 *   <li>재시도는 설정한 횟수까지만 한다. 무한 재시도는 사고로 직결된다.
 *   <li>시스템 프롬프트에 캐시 지시를 붙인다. 매 요청 동일하므로 입력 비용이
 *       크게 줄어든다.
 * </ul>
 */
@Profile("!mock")
@Slf4j
@Component
public class AnthropicJudgementClient implements JudgementClient {

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 모델이 JSON을 코드펜스로 감싸는 경우가 있다. */
    private static final Pattern CODE_FENCE = Pattern.compile("```(?:json)?\\s*|\\s*```");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final JudgementPromptBuilder promptBuilder;
    private final SafecheckProperties.Llm config;

    private final AtomicLong callCount = new AtomicLong();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();

    public AnthropicJudgementClient(ObjectMapper objectMapper,
                                    JudgementPromptBuilder promptBuilder,
                                    SafecheckProperties properties) {
        this.config = properties.llm();
        this.objectMapper = objectMapper;
        this.promptBuilder = promptBuilder;
        this.restClient = RestClient.builder()
                .baseUrl(config.baseUrl())
                .defaultHeader("x-api-key", config.apiKey())
                .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                .defaultHeader("content-type", MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory(config.timeout()))
                .build();

        log.info("판정 모델 클라이언트 준비: model={}, timeout={}", config.model(), config.timeout());
    }

    private static ClientHttpRequestFactory requestFactory(Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .build());
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    @Override
    public JudgementResponse judge(byte[] image, String mediaType,
                                   String buildingType, String positionType) {

        Map<String, Object> body = requestBody(image, mediaType, buildingType, positionType);

        RuntimeException lastError = null;
        for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
            try {
                return callOnce(body, attempt);
            } catch (HttpClientErrorException e) {
                // 4xx는 요청 자체가 잘못된 것이므로 재시도하지 않는다
                throw new JudgementFailedException("판정 요청이 거부되었습니다: " + e.getStatusCode(), e);
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("판정 호출 실패 ({}회차): {}", attempt + 1, e.getMessage());
            }
        }
        throw new JudgementFailedException(
                "판정 모델 호출에 실패했습니다. 재시도 %d회 소진.".formatted(config.maxRetries()),
                lastError);
    }

    private JudgementResponse callOnce(Map<String, Object> body, int attempt) {
        long started = System.currentTimeMillis();

        JsonNode root = restClient.post()
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (root == null) {
            throw new JudgementFailedException("판정 응답이 비어 있습니다.", null);
        }

        recordResponse(root, System.currentTimeMillis() - started, attempt);

        String text = extractText(root);
        return parse(text);
    }

    // ------------------------------------------------------------------
    // 요청
    // ------------------------------------------------------------------

    private Map<String, Object> requestBody(byte[] image, String mediaType,
                                            String buildingType, String positionType) {
        return Map.of(
                "model", config.model(),
                "max_tokens", config.maxTokens(),
                "system", List.of(Map.of(
                        "type", "text",
                        "text", promptBuilder.systemPrompt(),
                        "cache_control", Map.of("type", "ephemeral"))),
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "image",
                                        "source", Map.of(
                                                "type", "base64",
                                                "media_type", mediaType,
                                                "data", Base64.getEncoder().encodeToString(image))),
                                Map.of("type", "text",
                                        "text", promptBuilder.userMessage(buildingType, positionType))))));
    }

    // ------------------------------------------------------------------
    // 응답
    // ------------------------------------------------------------------

    /** content 배열에서 텍스트 블록만 모은다. */
    private static String extractText(JsonNode root) {
        JsonNode content = root.path("content");
        if (!content.isArray() || content.isEmpty()) {
            throw new JudgementFailedException("판정 응답에 내용이 없습니다.", null);
        }

        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asString())) {
                sb.append(block.path("text").asString());
            }
        }

        String text = sb.toString().trim();
        if (text.isEmpty()) {
            throw new JudgementFailedException("판정 응답에서 텍스트를 찾지 못했습니다.", null);
        }
        return text;
    }

    private JudgementResponse parse(String text) {
        String cleaned = CODE_FENCE.matcher(text).replaceAll("").trim();
        try {
            return objectMapper.readValue(cleaned, JudgementResponse.class);
        } catch (RuntimeException e) {
            log.warn("판정 응답 파싱 실패. 원문 앞부분: {}",
                    cleaned.substring(0, Math.min(200, cleaned.length())));
            throw new JudgementFailedException("판정 응답을 해석하지 못했습니다.", e);
        }
    }

    // ------------------------------------------------------------------
    // 사용량 기록
    // ------------------------------------------------------------------

    /**
     * 호출 횟수와 토큰을 남긴다.
     * 개발 중 의도치 않은 반복 호출을 알아차리고, 운영 비용을 추적하기 위한 것이다.
     */
    private void recordResponse(JsonNode root, long elapsedMs, int attempt) {
        JsonNode usage = root.path("usage");
        long input = usage.path("input_tokens").asLong(0);
        long output = usage.path("output_tokens").asLong(0);
        long cacheRead = usage.path("cache_read_input_tokens").asLong(0);
        long cacheWrite = usage.path("cache_creation_input_tokens").asLong(0);
        String stopReason = root.path("stop_reason").asString();

        if ("max_tokens".equals(stopReason)) {
            log.warn("출력이 max_tokens({})에 도달해 잘렸습니다. 상한 조정이 필요합니다.",
                    config.maxTokens());
        }

        long calls = callCount.incrementAndGet();
        inputTokens.addAndGet(input);
        outputTokens.addAndGet(output);

        log.info("판정 호출 #{} ({}ms, 시도 {}회차) 토큰 입력={} 출력={} 캐시읽기={} 캐시쓰기={}",
                calls, elapsedMs, attempt + 1, input, output, cacheRead, cacheWrite);
    }

    public Usage usage() {
        return new Usage(callCount.get(), inputTokens.get(), outputTokens.get());
    }

    public record Usage(long calls, long inputTokens, long outputTokens) {}

    public static class JudgementFailedException extends RuntimeException {
        public JudgementFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}