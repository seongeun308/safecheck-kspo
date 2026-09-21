package io.github.seongeun308.safecheck.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param apiKey      환경변수로 주입. 코드나 설정 파일에 직접 쓰지 않는다.
 *                    실제 호출이 있는 프로파일에서만 필요하므로, 누락 검사는
 *                    LlmApiKeyValidator가 맡는다.
 * @param maxTokens   JSON 출력에 1000이면 충분하다.
 * @param timeout     이미지 4장 기준 응답이 10~30초까지 걸린다.
 * @param maxRetries  파싱 실패 또는 일시적 오류에 대한 재시도 횟수.
 */
@ConfigurationProperties(prefix = "safecheck.llm")
public record LlmProperties(
        String apiKey,
        @DefaultValue("https://api.anthropic.com/v1/messages") String baseUrl,
        @DefaultValue("claude-sonnet-5") String model,
        @DefaultValue("2000") int maxTokens,
        @DefaultValue("60s") Duration timeout,
        @DefaultValue("1") int maxRetries
) {}
