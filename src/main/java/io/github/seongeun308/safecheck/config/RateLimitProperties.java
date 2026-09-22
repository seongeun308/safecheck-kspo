package io.github.seongeun308.safecheck.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param perClientHourly 접속 주소별 시간당 상한.
 * @param dailyTotal      전체 일일 상한. 캐시 적용 후 건당 약 9원.
 */
@ConfigurationProperties(prefix = "safecheck.rate-limit")
public record RateLimitProperties(
        @DefaultValue("20") int perClientHourly,
        @DefaultValue("300") int dailyTotal
) {}
