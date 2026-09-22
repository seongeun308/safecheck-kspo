package io.github.seongeun308.safecheck.repository;

import io.github.seongeun308.safecheck.domain.GradeStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 안전점검 등급 분포 집계 파일을 기동 시 한 번 읽는다.
 *
 * <p>파일이 없거나 숫자가 서로 맞지 않으면 기동을 중단한다. 잘못된 통계를
 * 화면에 내보내는 것보다 즉시 드러나는 편이 낫다.
 */
@Slf4j
@Repository
public class GradeStatsRepository {

    private static final String PATH = "data/grade_stats.json";

    private final GradeStats stats;

    public GradeStatsRepository(ObjectMapper objectMapper) {
        this.stats = read(objectMapper);
        verify(stats);
        log.info("안전점검 등급 분포 적재: 업종 {}종, 시설 {}개소 (수집일 {})",
                stats.byBusinessType().size(), stats.overall().total(), stats.source().fetchedAt());
    }

    private static GradeStats read(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(PATH).getInputStream()) {
            return objectMapper.readValue(in, GradeStats.class);
        } catch (IOException e) {
            throw new IllegalStateException("등급 분포 파일을 읽을 수 없습니다: " + PATH, e);
        }
    }

    /**
     * 집계 파일의 숫자가 서로 맞는지 확인한다.
     * 스크립트를 다시 돌리거나 파일을 손으로 고쳤을 때 조용히 어긋나는 것을 막는다.
     */
    private static void verify(GradeStats s) {
        if (s.byBusinessType() == null || s.byBusinessType().isEmpty()) {
            throw new IllegalStateException("업종별 분포가 비어 있습니다.");
        }

        Set<String> codes = s.grades().stream()
                .map(GradeStats.Grade::code)
                .collect(Collectors.toUnmodifiableSet());

        checkDistribution("전체", s.overall().total(), s.overall().counts().keySet(),
                s.overall().counts().values().stream().mapToInt(Integer::intValue).sum(), codes);

        Set<String> seen = new HashSet<>();
        int sumOfTypes = 0;
        for (GradeStats.BusinessType t : s.byBusinessType()) {
            if (!seen.add(t.name())) {
                throw new IllegalStateException("업종이 중복되었습니다: " + t.name());
            }
            checkDistribution(t.name(), t.total(), t.counts().keySet(),
                    t.counts().values().stream().mapToInt(Integer::intValue).sum(), codes);
            sumOfTypes += t.total();
        }

        if (sumOfTypes != s.overall().total()) {
            throw new IllegalStateException(
                    "업종별 합계(%d)가 전체(%d)와 다릅니다.".formatted(sumOfTypes, s.overall().total()));
        }
        if (s.overall().total() != s.filter().included()) {
            throw new IllegalStateException(
                    "전체(%d)가 집계 대상 수(%d)와 다릅니다."
                            .formatted(s.overall().total(), s.filter().included()));
        }
    }

    private static void checkDistribution(String label, int total, Set<String> keys,
                                          int sum, Set<String> codes) {
        if (!codes.containsAll(keys)) {
            throw new IllegalStateException(label + ": 알 수 없는 등급 코드 " + keys);
        }
        if (sum != total) {
            throw new IllegalStateException(
                    "%s: 등급별 합계(%d)가 총계(%d)와 다릅니다.".formatted(label, sum, total));
        }
    }

    // ------------------------------------------------------------------

    public GradeStats stats() {
        return stats;
    }

    public Optional<GradeStats.BusinessType> findBusinessType(String name) {
        return stats.byBusinessType().stream()
                .filter(t -> t.name().equals(name))
                .findFirst();
    }

    public List<String> businessTypeNames() {
        return stats.byBusinessType().stream().map(GradeStats.BusinessType::name).toList();
    }
}
