package io.github.seongeun308.safecheck.support;

import io.github.seongeun308.safecheck.TestProperties;
import io.github.seongeun308.safecheck.dto.JudgementResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JudgementCacheTest {

    private static final String HASH = "a".repeat(64);
    private static final String OTHER_HASH = "b".repeat(64);
    private static final String BUILDING = "건물 내외부";
    private static final String POSITION = "난간";

    @Test
    @DisplayName("저장한 응답을 같은 키로 다시 꺼낸다")
    void returnsStoredResponse() {
        JudgementCache cache = cache(100, Duration.ofMinutes(30));
        JudgementResponse response = response("외부 계단 벽체 망상균열");

        cache.put(HASH, BUILDING, POSITION, response);

        assertThat(cache.get(HASH, BUILDING, POSITION)).contains(response);
    }

    @Test
    @DisplayName("저장한 적 없는 이미지는 비어 있다")
    void returnsEmptyForUnknownImage() {
        JudgementCache cache = cache(100, Duration.ofMinutes(30));

        assertThat(cache.get(HASH, BUILDING, POSITION)).isEmpty();
    }

    @Test
    @DisplayName("같은 이미지라도 위치구분이 다르면 별개로 다룬다")
    void separatesByPositionType() {
        JudgementCache cache = cache(100, Duration.ofMinutes(30));
        cache.put(HASH, BUILDING, "난간", response("난간 부식"));

        assertThat(cache.get(HASH, BUILDING, "창호")).isEmpty();
        assertThat(cache.get(HASH, BUILDING, "난간")).isPresent();
    }

    @Test
    @DisplayName("같은 이미지라도 건물구분이 다르면 별개로 다룬다")
    void separatesByBuildingType() {
        JudgementCache cache = cache(100, Duration.ofMinutes(30));
        cache.put(HASH, "건물 내외부", POSITION, response("벽체 균열"));

        assertThat(cache.get(HASH, "건물주변", POSITION)).isEmpty();
    }

    @Test
    @DisplayName("유효기간이 지난 항목은 돌려주지 않는다")
    void expiresStaleEntry() {
        JudgementCache cache = cache(100, Duration.ZERO);
        cache.put(HASH, BUILDING, POSITION, response("벽체 균열"));

        assertThat(cache.get(HASH, BUILDING, POSITION)).isEmpty();
    }

    @Test
    @DisplayName("최대 건수를 넘으면 오래된 항목부터 버린다")
    void evictsOldestWhenFull() {
        JudgementCache cache = cache(2, Duration.ofMinutes(30));

        cache.put("1".repeat(64), BUILDING, POSITION, response("첫 번째"));
        cache.put("2".repeat(64), BUILDING, POSITION, response("두 번째"));
        cache.put("3".repeat(64), BUILDING, POSITION, response("세 번째"));

        assertThat(cache.get("1".repeat(64), BUILDING, POSITION)).isEmpty();
        assertThat(cache.get("2".repeat(64), BUILDING, POSITION)).isPresent();
        assertThat(cache.get("3".repeat(64), BUILDING, POSITION)).isPresent();
    }

    @Test
    @DisplayName("최근에 꺼낸 항목은 남기고 그렇지 않은 항목을 버린다")
    void keepsRecentlyAccessedEntry() {
        JudgementCache cache = cache(2, Duration.ofMinutes(30));
        String first = "1".repeat(64);
        String second = "2".repeat(64);
        String third = "3".repeat(64);

        cache.put(first, BUILDING, POSITION, response("첫 번째"));
        cache.put(second, BUILDING, POSITION, response("두 번째"));
        cache.get(first, BUILDING, POSITION);          // 첫 번째를 다시 사용
        cache.put(third, BUILDING, POSITION, response("세 번째"));

        assertThat(cache.get(first, BUILDING, POSITION)).isPresent();
        assertThat(cache.get(second, BUILDING, POSITION)).isEmpty();
    }

    @Test
    @DisplayName("적중과 실패 횟수를 집계한다")
    void tracksHitAndMissCount() {
        JudgementCache cache = cache(100, Duration.ofMinutes(30));
        cache.put(HASH, BUILDING, POSITION, response("벽체 균열"));

        cache.get(HASH, BUILDING, POSITION);           // 적중
        cache.get(HASH, BUILDING, POSITION);           // 적중
        cache.get(OTHER_HASH, BUILDING, POSITION);     // 실패

        JudgementCache.Stats stats = cache.stats();
        assertThat(stats.hits()).isEqualTo(2);
        assertThat(stats.misses()).isEqualTo(1);
        assertThat(stats.size()).isEqualTo(1);
        assertThat(stats.hitRate()).isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(0.001));
    }

    // ------------------------------------------------------------------

    private static JudgementCache cache(int maxEntries, Duration ttl) {
        return new JudgementCache(TestProperties.cacheOf(maxEntries, ttl), Clock.systemUTC());
    }

    private static JudgementResponse response(String notice) {
        return new JudgementResponse(
                JudgementResponse.YES,
                List.of(new JudgementResponse.Judgement(1, "균열, 누수", 0.85, "근거")),
                notice,
                new JudgementResponse.ImageQuality(true, ""),
                false, "");
    }
}
