package io.github.seongeun308.safecheck.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 클라이언트에 내보내는 판정 결과.
 *
 * <p>{@link JudgementResponse}가 모델이 돌려준 원형이라면, 이 레코드는
 * 검증과 보강을 거쳐 화면에 필요한 것만 남긴 형태다.
 */
public record JudgementResult(
        Status status,
        List<JudgedItem> judgements,
        String notice,
        String guidance,
        List<CaseView> cases,
        String imageHash
) {

    public enum Status {
        /** 결함이 판정되었다. */
        DEFECT_FOUND,
        /** 22개 점검항목에 해당하는 결함이 관찰되지 않았다. */
        NO_DEFECT,
        /** 부재를 특정하기 어려워 추가 촬영이 필요하다. */
        NEEDS_BETTER_SHOT
    }

    /**
     * @param officialName 공단 공식 점검항목 문구. 근거로 함께 노출한다.
     * @param confidence   0.0~1.0
     */
    public record JudgedItem(
            int itemId,
            String shortName,
            String officialName,
            double confidence,
            String evidence
    ) {}

    /**
     * 공단이 개방한 실제 점검 사례.
     *
     * <p>시설명과 주소는 담지 않는다. 특정 시설을 부정적으로 지목하는 모양새를
     * 피하기 위한 것으로, 데이터 적재 단계에서 이미 제외했다.
     */
    public record CaseView(
            String notice,
            String imageUrl,
            LocalDate inspectedOn
    ) {}
}
