package io.github.seongeun308.safecheck.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param maxEntries 넘으면 오래 쓰이지 않은 항목부터 버린다.
 *                   응답 하나가 1KB 남짓이라 1000건이면 수 MB 수준이다.
 * @param ttl        공단 데이터가 바뀌지 않으므로 길게 잡아도 무방하다.
 */
@ConfigurationProperties(prefix = "safecheck.cache")
public record CacheProperties(
        @DefaultValue("1000") int maxEntries,
        @DefaultValue("24h") Duration ttl
) {}
