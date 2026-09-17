package io.github.seongeun308.safecheck.support;

import io.github.seongeun308.safecheck.config.SafecheckProperties;
import io.github.seongeun308.safecheck.domain.InspectionItem;
import io.github.seongeun308.safecheck.repository.DefectCaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class JudgementPromptBuilderTest {

    // 프롬프트 조립은 점검항목 목록만 사용하므로, 사례 노출 개수 외의 설정은 비워 둔다.
    private static final SafecheckProperties PROPERTIES = new SafecheckProperties(
            null, null, new SafecheckProperties.Judgement(0.5, 3, 3));

    private final DefectCaseRepository repository =
            new DefectCaseRepository(new ObjectMapper(), PROPERTIES);
    private final JudgementPromptBuilder builder = new JudgementPromptBuilder(repository);

    @Test
    @DisplayName("템플릿의 자리표시자가 남김없이 치환된다")
    void replacesAllPlaceholders() {
        assertThat(builder.systemPrompt()).doesNotContain("{{");
    }

    @Test
    @DisplayName("공단 점검항목 22종이 약칭과 공식 명칭 모두 포함된다")
    void containsAllInspectionItems() {
        String prompt = builder.systemPrompt();

        assertThat(repository.items()).hasSize(22);
        for (InspectionItem item : repository.items()) {
            assertThat(prompt)
                    .as("점검항목 %d번(%s)", item.id(), item.shortName())
                    .contains(item.shortName())
                    .contains(item.officialName());
        }
    }

    @Test
    @DisplayName("출력 스키마 필드명이 유실되지 않는다")
    void retainsOutputSchemaFields() {
        assertThat(builder.systemPrompt())
                .contains("defectObserved")
                .contains("judgements")
                .contains("confidence")
                .contains("notice")
                .contains("needsWiderShot")
                .contains("noticeText");
    }

    @Test
    @DisplayName("소견 서술 문법 지시가 유실되지 않는다")
    void retainsNoticeFormatInstruction() {
        assertThat(builder.systemPrompt()).contains("[위치] [부재] [상태]");
    }

    @Test
    @DisplayName("사용자 메시지에 건물구분과 위치구분이 담긴다")
    void buildsUserMessageWithLocation() {
        String message = builder.userMessage("건물 내외부", "난간");

        assertThat(message)
                .contains("건물 내외부")
                .contains("난간");
    }
}