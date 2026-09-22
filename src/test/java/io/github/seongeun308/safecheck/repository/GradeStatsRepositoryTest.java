package io.github.seongeun308.safecheck.repository;

import io.github.seongeun308.safecheck.domain.GradeStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * 실제 집계 파일을 읽어 확인한다. 데이터를 다시 수집해 숫자가 바뀌어도 깨지지
 * 않도록, 특정 건수 대신 구조와 정합성을 본다.
 */
class GradeStatsRepositoryTest {

    private static final GradeStatsRepository REPOSITORY =
            new GradeStatsRepository(new ObjectMapper());

    @Test
    @DisplayName("공단 등급 체계 3단계를 담는다")
    void containsThreeGrades() {
        assertThat(REPOSITORY.stats().grades())
                .extracting(GradeStats.Grade::code, GradeStats.Grade::name)
                .containsExactly(tuple("01", "양호"), tuple("02", "주의"), tuple("03", "사용중지"));
    }

    @Test
    @DisplayName("시설 구분은 신고업, 등록업, 공공 순서이고 표시명을 가진다")
    void containsGroupsInOrder() {
        assertThat(REPOSITORY.stats().byGroup())
                .extracting(GradeStats.Group::name, GradeStats.Group::label)
                .containsExactly(
                        tuple("신고업", "신고 체육시설업"),
                        tuple("등록업", "등록 체육시설업"),
                        tuple("공공", "공공체육시설"));
    }

    @Test
    @DisplayName("구분별 합계가 전체와 같다")
    void groupsSumToOverall() {
        GradeStats stats = REPOSITORY.stats();
        int sum = stats.byGroup().stream().mapToInt(GradeStats.Group::total).sum();

        assertThat(sum).isEqualTo(stats.overall().total());
    }

    @Test
    @DisplayName("구분마다 업종 합계가 구분 총계와 같다")
    void typesSumToGroupTotal() {
        for (GradeStats.Group g : REPOSITORY.stats().byGroup()) {
            int sum = g.types().stream().mapToInt(GradeStats.BusinessType::total).sum();
            assertThat(sum).as(g.label()).isEqualTo(g.total());
        }
    }

    @Test
    @DisplayName("업종은 구분 안에서 건수가 많은 순서로 정렬되어 있다")
    void typesSortedByTotalDescending() {
        for (GradeStats.Group g : REPOSITORY.stats().byGroup()) {
            assertThat(g.types())
                    .as(g.label())
                    .extracting(GradeStats.BusinessType::total)
                    .isSortedAccordingTo((a, b) -> Integer.compare(b, a));
        }
    }

    @Test
    @DisplayName("같은 이름의 업종이 구분별로 따로 집계된다")
    void sameTypeNameIsSeparatedByGroup() {
        // 인공암벽장업은 신고업과 공공 양쪽에 있다. 이름만으로 묶으면 두 구분이 섞인다.
        var privateOne = REPOSITORY.findBusinessType("신고업", "인공암벽장업");
        var publicOne = REPOSITORY.findBusinessType("공공", "인공암벽장업");

        assertThat(privateOne).isPresent();
        assertThat(publicOne).isPresent();
        assertThat(privateOne.get().total()).isNotEqualTo(publicOne.get().total());
    }

    @Test
    @DisplayName("구분과 업종으로 분포를 찾는다")
    void findsBusinessTypeByGroupAndName() {
        GradeStats.Group first = REPOSITORY.stats().byGroup().getFirst();
        String firstType = first.types().getFirst().name();

        assertThat(REPOSITORY.findBusinessType(first.name(), firstType)).isPresent();
        assertThat(REPOSITORY.findBusinessType(first.name(), "존재하지 않는 업종")).isEmpty();
        assertThat(REPOSITORY.findBusinessType("존재하지 않는 구분", firstType)).isEmpty();
    }

    @Test
    @DisplayName("출처, 수집일, 점검 연도 범위를 담는다")
    void containsSourceAndYears() {
        GradeStats stats = REPOSITORY.stats();

        assertThat(stats.source().datasetId()).isEqualTo("15107773");
        assertThat(stats.source().fetchedAt()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(List.of(stats.inspectionYears().min(), stats.inspectionYears().max()))
                .allMatch(y -> y != null && y.matches("\\d{4}"));
        assertThat(stats.inspectionYears().max())
                .isLessThanOrEqualTo(stats.source().fetchedAt().substring(0, 4));
    }
}
