package io.github.seongeun308.safecheck.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param maxDimension 장변 기준 리사이즈 크기. 토큰 비용에 직결된다.
 *                     1024px이면 장당 약 1300토큰.
 * @param maxUploadMb  업로드 허용 크기. 초과 시 요청을 거부한다.
 */
@ConfigurationProperties(prefix = "safecheck.image")
public record ImageProperties(
        @DefaultValue("1024") int maxDimension,
        @DefaultValue("10") int maxUploadMb
) {}
