package io.github.seongeun308.safecheck;

import io.github.seongeun308.safecheck.config.*;

import java.time.Duration;

/** 테스트용 설정 팩토리. 각 컴포넌트에 필요한 프로퍼티만 독립적으로 생성한다. */
public final class TestProperties {

    private TestProperties() {}

    // ==========================================
    // Image Properties
    // ==========================================
    public static ImageProperties imageDefaults() {
        return new ImageProperties(1024, 10);
    }

    public static ImageProperties imageOf(int maxDimension, int maxUploadMb) {
        return new ImageProperties(maxDimension, maxUploadMb);
    }

    // ==========================================
    // Cache Properties
    // ==========================================
    public static CacheProperties cacheDefaults() {
        return new CacheProperties(1000, Duration.ofMinutes(30));
    }

    public static CacheProperties cacheOf(int maxEntries, Duration ttl) {
        return new CacheProperties(maxEntries, ttl);
    }

    // ==========================================
    // Judgement Properties
    // ==========================================
    public static JudgementProperties judgementDefaults() {
        return new JudgementProperties(0.4, 3, 3);
    }

    public static JudgementProperties judgementOf(double confidenceThreshold, int maxJudgements, int caseDisplayLimit) {
        return new JudgementProperties(confidenceThreshold, maxJudgements, caseDisplayLimit);
    }

    // ==========================================
    // LLM Properties
    // ==========================================
    public static LlmProperties llmDefaults() {
        return new LlmProperties(
                "test-key",
                "http://localhost",
                "test-model",
                1000,
                Duration.ofSeconds(60),
                1
        );
    }

    public static RateLimitProperties rateLimitDefaults() {
        return new RateLimitProperties(20, 300);
    }

    public static RateLimitProperties rateLimitOf(int perClientHourly, int dailyTotal) {
        return new RateLimitProperties(perClientHourly, dailyTotal);
    }
}
