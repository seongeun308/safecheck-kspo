package io.github.seongeun308.safecheck.support;

import io.github.seongeun308.safecheck.config.CacheProperties;
import io.github.seongeun308.safecheck.dto.JudgementResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 판정 응답을 이미지 단위로 기억한다.
 *
 * <p>같은 사진을 다시 올렸을 때 모델을 호출하지 않는다. 개발 중 같은 사진으로
 * 반복 시험하는 경우와, 심사 기간에 동일한 데모 이미지가 여러 번 들어오는
 * 경우를 모두 흡수한다.
 *
 * <p>같은 사진이라도 위치구분이 다르면 판정이 달라지므로 키에 포함한다.
 *
 * <p>인스턴스 하나만 띄우는 구성이므로 프로세스 메모리에 둔다. 무한정 쌓이지
 * 않도록 최대 건수를 정하고 오래 쓰이지 않은 항목부터 버린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JudgementCache {

    private final CacheProperties properties;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    private final Map<String, Entry> store = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                    return size() > properties.maxEntries();
                }
            });

    @PostConstruct
    private void init() {
        log.info("판정 캐시 설정: 최대 {}건, 유효기간 {}", properties.maxEntries(), properties.ttl());
    }

    public Optional<JudgementResponse> get(String imageHash, String buildingType, String positionType) {
        String key = key(imageHash, buildingType, positionType);
        Entry entry = store.get(key);

        if (entry == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        if (entry.isExpired(properties.ttl())) {
            store.remove(key);
            misses.incrementAndGet();
            return Optional.empty();
        }

        long hitCount = hits.incrementAndGet();
        log.info("캐시 적중: hash={}, 누적 적중 {}건 / 전체 {}건",
                imageHash.substring(0, 8), hitCount, hitCount + misses.get());
        return Optional.of(entry.response());
    }

    public void put(String imageHash, String buildingType, String positionType,
                    JudgementResponse response) {
        store.put(key(imageHash, buildingType, positionType),
                new Entry(response, Instant.now()));
    }

    /** 적중률. 운영 중 캐시가 실제로 작동하는지 확인하는 용도. */
    public Stats stats() {
        long hit = hits.get();
        long miss = misses.get();
        long total = hit + miss;
        double rate = total == 0 ? 0.0 : (double) hit / total;
        return new Stats(hit, miss, store.size(), rate);
    }

    private static String key(String imageHash, String buildingType, String positionType) {
        return imageHash + '|' + buildingType + '|' + positionType;
    }

    private record Entry(JudgementResponse response, Instant storedAt) {
        boolean isExpired(Duration ttl) {
            return Instant.now().isAfter(storedAt.plus(ttl));
        }
    }

    public record Stats(long hits, long misses, int size, double hitRate) {}
}
