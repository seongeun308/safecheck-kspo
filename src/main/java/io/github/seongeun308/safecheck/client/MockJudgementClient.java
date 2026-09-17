package io.github.seongeun308.safecheck.client;

import io.github.seongeun308.safecheck.dto.JudgementResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

/**
 * 호출 비용 없이 판정 응답을 흉내 낸다.
 *
 * <p>컨트롤러, 사례 조회, 응답 변환, 프론트 연동은 전부 이 구현으로 개발한다.
 * 실제 호출은 판정 품질을 확인할 때만 필요하다.
 *
 * <p>같은 이미지에는 항상 같은 응답을 돌려준다. 캐싱 동작을 검증할 때
 * 결과가 흔들리지 않도록 하기 위한 것이다.
 */
@Profile("mock")
@Component
public class MockJudgementClient implements JudgementClient {

    private static final Logger log = LoggerFactory.getLogger(MockJudgementClient.class);

    private static final String SAMPLES_PATH = "mock/judgement-samples.json";

    private final List<JudgementResponse> samples;

    public MockJudgementClient(ObjectMapper objectMapper) {
        ClassPathResource resource = new ClassPathResource(SAMPLES_PATH);
        try (InputStream in = resource.getInputStream()) {
            this.samples = List.copyOf(
                    objectMapper.readValue(in, new TypeReference<List<JudgementResponse>>() {}));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "모의 응답 파일을 읽을 수 없습니다: " + SAMPLES_PATH, e);
        }

        if (samples.isEmpty()) {
            throw new IllegalStateException("모의 응답이 비어 있습니다: " + SAMPLES_PATH);
        }

        log.warn("mock 프로파일로 기동합니다. 판정 모델을 호출하지 않습니다. (표본 {}건)",
                samples.size());
    }

    @Override
    public JudgementResponse judge(byte[] image, String mediaType,
                                   String buildingType, String positionType) {
        int index = Math.floorMod(Arrays.hashCode(image), samples.size());
        JudgementResponse sample = samples.get(index);

        log.info("[mock] 판정 응답 #{} 반환 (건물구분={}, 위치구분={}, {}바이트)",
                index, buildingType, positionType, image.length);

        return sample;
    }
}