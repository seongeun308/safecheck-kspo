package io.github.seongeun308.safecheck.repository;

import io.github.seongeun308.safecheck.domain.GradeStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
        log.info("안전점검 등급 분포 적재: 구분 {}종, 업종 {}종, 시설 {}개소 (수집일 {})",
                stats.byGroup().size(),
                stats.byGroup().stream().mapToInt(g -> g.types().size()).sum(),
                stats.overall().total(),
                stats.source().fetchedAt());
    }

    private static GradeStats read(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(PATH).getInputStream()) {
            return objectMapper.readValue(in, GradeStats.class);
        } catch (IOException e) {
            throw new IllegalStateException("등급 분포 파일을 읽을 수 없습니다: " + PATH, e);
        }
    }

    // ------------------------------------------------------------------
    // 정합성 검사
    // ------------------------------------------------------------------

    /**
     * 집계 파일의 숫자가 서로 맞는지 확인한다.
     * 스크립트를 다시 돌리거나 파일을 손으로 고쳤을 때 조용히 어긋나는 것을 막는다.
     *
     * <ul>
     *   <li>모든 분포에서 등급별 합계 = 총계, 알 수 없는 등급 코드 없음
     *   <li>구분마다 업종 합계 = 구분 총계 (등급별로도 일치)
     *   <li>구분 총계의 합 = 전체 = 집계 대상 수
     *   <li>구분 이름은 전체에서, 업종 이름은 같은 구분 안에서 중복 없음
     * </ul>
     */
    private static void verify(GradeStats s) {
        if (s.byGroup() == null || s.byGroup().isEmpty()) {
            throw new IllegalStateException("시설 구분별 분포가 비어 있습니다.");
        }

        Set<String> codes = s.grades().stream()
                .map(GradeStats.Grade::code)
                .collect(Collectors.toUnmodifiableSet());

        checkDistribution("전체", s.overall().total(), s.overall().counts(), codes);

        Set<String> groupNames = new HashSet<>();
        Map<String, Integer> overallFromGroups = new HashMap<>();
        int sumOfGroups = 0;

        for (GradeStats.Group g : s.byGroup()) {
            if (!groupNames.add(g.name())) {
                throw new IllegalStateException("시설 구분이 중복되었습니다: " + g.name());
            }
            verifyGroup(g, codes);
            sumOfGroups += g.total();
            g.counts().forEach((code, n) -> overallFromGroups.merge(code, n, Integer::sum));
        }

        if (sumOfGroups != s.overall().total()) {
            throw new IllegalStateException(
                    "구분별 합계(%d)가 전체(%d)와 다릅니다.".formatted(sumOfGroups, s.overall().total()));
        }
        checkSameCounts("전체", s.overall().counts(), overallFromGroups, codes);

        if (s.overall().total() != s.filter().included()) {
            throw new IllegalStateException(
                    "전체(%d)가 집계 대상 수(%d)와 다릅니다."
                            .formatted(s.overall().total(), s.filter().included()));
        }
    }

    private static void verifyGroup(GradeStats.Group g, Set<String> codes) {
        String label = g.label() + "(" + g.name() + ")";
        checkDistribution(label, g.total(), g.counts(), codes);

        if (g.types() == null || g.types().isEmpty()) {
            throw new IllegalStateException(label + ": 업종이 비어 있습니다.");
        }

        Set<String> typeNames = new HashSet<>();
        Map<String, Integer> fromTypes = new HashMap<>();
        int sumOfTypes = 0;

        for (GradeStats.BusinessType t : g.types()) {
            // 구분이 다르면 같은 이름이 정상(인공암벽장업). 같은 구분 안에서만 검사한다.
            if (!typeNames.add(t.name())) {
                throw new IllegalStateException(label + ": 업종이 중복되었습니다: " + t.name());
            }
            checkDistribution(label + " " + t.name(), t.total(), t.counts(), codes);
            sumOfTypes += t.total();
            t.counts().forEach((code, n) -> fromTypes.merge(code, n, Integer::sum));
        }

        if (sumOfTypes != g.total()) {
            throw new IllegalStateException(
                    "%s: 업종 합계(%d)가 구분 총계(%d)와 다릅니다.".formatted(label, sumOfTypes, g.total()));
        }
        checkSameCounts(label, g.counts(), fromTypes, codes);
    }

    private static void checkDistribution(String label, int total,
                                          Map<String, Integer> counts, Set<String> codes) {
        if (!codes.containsAll(counts.keySet())) {
            throw new IllegalStateException(label + ": 알 수 없는 등급 코드 " + counts.keySet());
        }
        int sum = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (sum != total) {
            throw new IllegalStateException(
                    "%s: 등급별 합계(%d)가 총계(%d)와 다릅니다.".formatted(label, sum, total));
        }
    }

    /** 상위 분포의 등급별 건수가 하위 분포를 더한 값과 같은지. */
    private static void checkSameCounts(String label, Map<String, Integer> declared,
                                        Map<String, Integer> summed, Set<String> codes) {
        for (String code : codes) {
            int a = declared.getOrDefault(code, 0);
            int b = summed.getOrDefault(code, 0);
            if (a != b) {
                throw new IllegalStateException(
                        "%s: 등급 %s 건수(%d)가 하위 합계(%d)와 다릅니다.".formatted(label, code, a, b));
            }
        }
    }

    // ------------------------------------------------------------------
    // 조회
    // ------------------------------------------------------------------

    public GradeStats stats() {
        return stats;
    }

    /** @param group 원본 구분 값 (신고업, 등록업, 공공) */
    public Optional<GradeStats.Group> findGroup(String group) {
        return stats.byGroup().stream()
                .filter(g -> g.name().equals(group))
                .findFirst();
    }

    /** 업종 이름은 구분마다 따로 있을 수 있으므로 구분을 함께 받는다. */
    public Optional<GradeStats.BusinessType> findBusinessType(String group, String name) {
        return findGroup(group).flatMap(g -> g.types().stream()
                .filter(t -> t.name().equals(name))
                .findFirst());
    }
}
