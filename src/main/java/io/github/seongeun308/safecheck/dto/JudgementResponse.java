package io.github.seongeun308.safecheck.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 판정 모델이 반환하는 JSON의 구조.
 *
 * <p>프롬프트 템플릿(prompts/judgement-system.txt)의 출력 형식과 1:1로 대응한다.
 * 템플릿을 고치면 이 레코드도 함께 고쳐야 한다.
 *
 * <p>모델이 스키마에 없는 필드를 덧붙이는 경우가 있으므로 알 수 없는 필드는
 * 무시한다. 반대로 필드가 누락되면 null 또는 기본값이 되므로, 사용하는 쪽에서
 * {@link #hasJudgement()} 등으로 확인한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JudgementResponse(
        String defectObserved,
        List<Judgement> judgements,
        String notice,
        ImageQuality imageQuality,
        boolean needsWiderShot,
        String noticeText
) {

    public static final String YES = "YES";
    public static final String NO = "NO";
    public static final String UNCERTAIN = "UNCERTAIN";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Judgement(
            int itemId,
            String itemName,
            double confidence,
            String evidence
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImageQuality(
            boolean sufficient,
            String issue
    ) {}

    public List<Judgement> judgementsOrEmpty() {
        return judgements == null ? List.of() : judgements;
    }

    public boolean hasJudgement() {
        return !judgementsOrEmpty().isEmpty();
    }

    /** 확신도 최고값. 판정이 없으면 0. */
    public double topConfidence() {
        return judgementsOrEmpty().stream()
                .mapToDouble(Judgement::confidence)
                .max()
                .orElse(0.0);
    }
}
