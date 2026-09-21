package io.github.seongeun308.safecheck.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * LLM API 키 누락을 기동 단계에서 중단시킨다.
 *
 * <p>해석되지 않은 자리표시자를 Spring은 오류로 보지 않고 "${LLM_API_KEY}"라는
 * 문자열을 그대로 넘긴다. 그래서 키 없이 배포해도 기동에는 성공하고,
 * 첫 판정 요청에서야 401로 드러난다. 빈 값과 함께 여기서 걸러낸다.
 *
 * <p>판정 모델을 호출하지 않는 {@code mock} 프로파일에서는 키가 필요 없으므로
 * 이 검사를 등록하지 않는다.
 */
@Component
@Profile("!mock")
public class LlmApiKeyValidator {

    public LlmApiKeyValidator(LlmProperties properties) {
        String apiKey = properties == null ? null : properties.apiKey();

        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            throw new IllegalStateException("""
                    LLM API 키가 설정되지 않았습니다.
                    환경변수 LLM_API_KEY를 지정하거나, 호출이 필요 없다면 mock 프로파일로 실행하십시오.
                      ./gradlew bootRun --args='--spring.profiles.active=mock'""");
        }
    }
}
