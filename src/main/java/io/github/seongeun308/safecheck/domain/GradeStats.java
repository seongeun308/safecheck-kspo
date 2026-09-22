package io.github.seongeun308.safecheck.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * 공단 안전점검 정보(15107773)의 업종별 종합등급 분포.
 *
 * <p>{@code scripts/build_grade_stats.py}가 만든 집계 파일과 1:1로 대응한다.
 * 개별 시설 정보는 담지 않는 익명 집계다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GradeStats(
        Source source,
        Filter filter,
        List<Grade> grades,
        Distribution overall,
        List<BusinessType> byBusinessType,
        InspectionYears inspectionYears
) {

    /** 출처. 화면과 보고서에 기준일로 표시한다. */
    public record Source(String datasetId, String datasetName, String operation, String fetchedAt) {}

    /** 수집부터 집계까지 몇 건이 남았는지. 모집단 설명에 쓴다. */
    public record Filter(String facilityStatus, int fetched, int active,
                         int activeWithoutGrade, int included) {}

    /** 01 양호, 02 주의, 03 사용중지 */
    public record Grade(String code, String name) {}

    public record Distribution(int total, Map<String, Integer> counts) {}

    /** 업종(fcob_nm)별 분포. 체력단련장업, 수영장업 등. */
    public record BusinessType(String name, int total, Map<String, Integer> counts) {}

    public record InspectionYears(String min, String max, Map<String, Integer> counts, int invalid) {}
}
