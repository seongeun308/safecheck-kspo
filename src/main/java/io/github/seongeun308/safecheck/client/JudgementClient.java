package io.github.seongeun308.safecheck.client;

import io.github.seongeun308.safecheck.dto.JudgementResponse;

/**
 * 사진을 판정 모델에 보내고 결과를 받는다.
 *
 * <p>구현을 프로파일로 분리한다. 개발과 테스트에서는 호출 비용이 없는
 * {@code mock} 프로파일을 사용하고, 실제 판정 품질을 확인할 때만
 * 기본 프로파일로 전환한다.
 *
 * <pre>
 *   ./gradlew bootRun --args='--spring.profiles.active=mock'
 * </pre>
 */
public interface JudgementClient {
 
    /**
     * @param image        전처리를 마친 이미지 바이트 (리사이즈 완료)
     * @param mediaType    image/jpeg 또는 image/png
     * @param buildingType 건물구분 (건물 내외부 | 건물주변)
     * @param positionType 위치구분 (공단 15종 중 하나)
     */
    JudgementResponse judge(byte[] image, String mediaType,
                            String buildingType, String positionType);
}
 