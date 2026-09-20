package io.github.seongeun308.safecheck;

import io.github.seongeun308.safecheck.config.SafecheckProperties;
import java.time.Duration;

/** 테스트용 설정. 필요한 부분만 바꿔 쓴다. */
public final class TestProperties {

    private TestProperties() {}

    public static SafecheckProperties defaults() {
        return of(new SafecheckProperties.Image(1024, 10),
                new SafecheckProperties.Judgement(0.5, 3, 3),
                new SafecheckProperties.Cache(1000, Duration.ofMinutes(30)));
    }

    public static SafecheckProperties withImage(int maxDimension, int maxUploadMb) {
        return of(new SafecheckProperties.Image(maxDimension, maxUploadMb),
                new SafecheckProperties.Judgement(0.5, 3, 3),
                new SafecheckProperties.Cache(1000, Duration.ofMinutes(30)));
    }

    public static SafecheckProperties withCache(int maxEntries, Duration ttl) {
        return of(new SafecheckProperties.Image(1024, 10),
                new SafecheckProperties.Judgement(0.5, 3, 3),
                new SafecheckProperties.Cache(maxEntries, ttl));
    }

    private static SafecheckProperties of(SafecheckProperties.Image image,
                                          SafecheckProperties.Judgement judgement,
                                          SafecheckProperties.Cache cache) {
        return new SafecheckProperties(
                new SafecheckProperties.Llm(
                        "test-key", "http://localhost", "test-model",
                        1000, Duration.ofSeconds(60), 1),
                image, judgement, cache);
    }
}
