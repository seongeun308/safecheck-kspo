package io.github.seongeun308.safecheck.repository;

import io.github.seongeun308.safecheck.domain.GradeStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 집계 파일을 읽어 확인한다. 스크립트를 다시 돌려 파일이 바뀌어도
 * 이 테스트가 깨지지 않도록 특정 숫자 대신 구조와 정합성을 본다.
 */
class GradeStatsRepositoryTest {

    private static final GradeStatsRepository REPOSITORY =
            new GradeStatsRepository(new ObjectMapper());

    @Test
    @DisplayName("공단 등급 체계 3단계를 담는다")
    void containsThreeGrades() {
        assertThat(REPOSITORY.stats().grades())
                .extracting(GradeStats.Grade::code, GradeStats.Grade::name)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("01", "양호"),
                        org.assertj.core.groups.Tuple.tuple("02", "주의"),
                        org.assertj.core.groups.Tuple.tuple("03", "사용중지"));
    }

    @Test
    @DisplayName("업종별 합계가 전체와 같다")
    void businessTypesSumToOverall() {
        GradeStats stats = REPOSITORY.stats();
        int sum = stats.byBusinessType().stream().mapToInt(GradeStats.BusinessType::total).sum();

        assertThat(sum).isEqualTo(stats.overall().total());
    }

    @Test
    @DisplayName("업종은 건수가 많은 순서로 정렬되어 있다")
    void sortedByTotalDescending() {
        assertThat(REPOSITORY.stats().byBusinessType())
                .extracting(GradeStats.BusinessType::total)
                .isSortedAccordingTo((a, b) -> Integer.compare(b, a));
    }

    @Test
    @DisplayName("업종 이름으로 분포를 찾는다")
    void findsBusinessTypeByName() {
        String first = REPOSITORY.businessTypeNames().get(0);

        assertThat(REPOSITORY.findBusinessType(first)).isPresent();
        assertThat(REPOSITORY.findBusinessType("존재하지 않는 업종")).isEmpty();
    }

    @Test
    @DisplayName("출처와 수집일을 담는다")
    void containsSource() {
        GradeStats.Source source = REPOSITORY.stats().source();

        assertThat(source.datasetId()).isEqualTo("15107773");
        assertThat(source.fetchedAt()).matches("\\d{4}-\\d{2}-\\d{2}");
    }
}
