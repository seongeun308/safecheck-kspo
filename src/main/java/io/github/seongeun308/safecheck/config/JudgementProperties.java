package io.github.seongeun308.safecheck.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param confidenceThreshold 이 값 미만이면 전경 사진 재촬영을 요청한다.
 *                            2026-09-14 검증에서 부재 특정 실패가 반복 관찰되어
 *                            도입했다. 실사용 데이터를 보고 조정한다.
 * @param maxJudgements       한 사진에 제시할 최대 판정 수.
 * @param caseDisplayLimit    결과 화면에 노출할 공단 사례 수.
 */
@ConfigurationProperties(prefix = "safecheck.judgement")
public record JudgementProperties(
        @DefaultValue("0.4") double confidenceThreshold,
        @DefaultValue("3") int maxJudgements,
        @DefaultValue("3") int caseDisplayLimit
) {}
