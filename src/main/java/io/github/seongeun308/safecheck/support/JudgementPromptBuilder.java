package io.github.seongeun308.safecheck.support;

import io.github.seongeun308.safecheck.domain.InspectionItem;
import io.github.seongeun308.safecheck.repository.DefectCaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 판정 요청에 사용할 프롬프트를 조립한다.
 *
 * <p>시스템 프롬프트는 공단 점검항목 22종이 고정이므로 기동 시 한 번만
 * 완성하여 보관한다. 매 요청마다 동일한 문자열이 전달되므로 프롬프트
 * 캐싱의 효과를 그대로 받는다.
 */
@Slf4j
@Component
public class JudgementPromptBuilder {

    private static final String TEMPLATE_PATH = "prompts/judgement-system.txt";
    private static final String ITEMS_PLACEHOLDER = "{{INSPECTION_ITEMS}}";

    private final String systemPrompt;

    public JudgementPromptBuilder(DefectCaseRepository repository) {
        String template = readTemplate();

        if (!template.contains(ITEMS_PLACEHOLDER)) {
            throw new IllegalStateException(
                    "프롬프트 템플릿에 " + ITEMS_PLACEHOLDER + " 자리표시자가 없습니다: "
                            + TEMPLATE_PATH);
        }

        this.systemPrompt = template.replace(
                ITEMS_PLACEHOLDER, renderItemTable(repository.items()));

        log.info("판정 시스템 프롬프트 조립 완료: {}자", systemPrompt.length());
    }

    private static String readTemplate() {
        ClassPathResource resource = new ClassPathResource(TEMPLATE_PATH);
        try (InputStream in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "프롬프트 템플릿을 읽을 수 없습니다: " + TEMPLATE_PATH, e);
        }
    }

    /**
     * 점검항목 22종을 마크다운 표로 렌더링한다.
     * 약칭과 공식 항목명을 함께 제시하여, 모델이 약칭으로 응답하면서도
     * 공단 원문의 판정 범위를 인식하도록 한다.
     */
    private static String renderItemTable(List<InspectionItem> items) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("| ID | 항목명(약칭) | 공식 점검항목 |\n");
        sb.append("|----|--------------|---------------|\n");
        for (InspectionItem item : items) {
            sb.append("| ").append(item.id())
              .append(" | ").append(item.shortName())
              .append(" | ").append(item.officialName())
              .append(" |\n");
        }
        return sb.toString().stripTrailing();
    }

    /** 매 요청 동일하다. 프롬프트 캐싱 대상. */
    public String systemPrompt() {
        return systemPrompt;
    }

    /**
     * 사용자가 선택한 위치 정보를 담은 메시지.
     *
     * <p>건물구분과 위치구분을 함께 주면 판정 후보가 좁혀진다.
     * 검증에서 "주요구조부인지 비구조부인지 사진만으로 미확정" 유형의
     * 실패가 반복 관찰되어 필수 입력으로 설계했다.
     */
    public String userMessage(String buildingType, String positionType) {
        return """
                건물구분: %s
                위치구분: %s

                위 위치에서 촬영한 사진입니다. 판정하십시오."""
                .formatted(buildingType, positionType);
    }
}