package io.github.seongeun308.safecheck.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 판정 엔진 관련 설정.
 *
 * <p>배포 후 조정 가능성이 있는 값만 외부화한다.
 * classpath 리소스 경로처럼 환경과 무관한 값은 상수로 둔다.
 */
@ConfigurationProperties(prefix = "safecheck")
public record SafecheckProperties(
        Llm llm,
        Image image,
        Judgement judgement,
        Cache cache
) {

    /**
     * @param apiKey      환경변수로 주입. 코드나 설정 파일에 직접 쓰지 않는다.
     *                    실제 호출이 있는 프로파일에서만 필요하므로, 누락 검사는
     *                    {@link LlmApiKeyValidator}가 맡는다.
     * @param maxTokens   JSON 출력에 1000이면 충분하다.
     * @param temperature 판정 일관성을 위해 0에 가깝게 유지한다.
     * @param timeout     이미지 4장 기준 응답이 10~30초까지 걸린다.
     * @param maxRetries  파싱 실패 또는 일시적 오류에 대한 재시도 횟수.
     */
    public record Llm(
            String apiKey,
            @DefaultValue("https://api.anthropic.com/v1/messages") String baseUrl,
            @DefaultValue("claude-sonnet-5") String model,
            @DefaultValue("1000") int maxTokens,
            @DefaultValue("0.0") double temperature,
            @DefaultValue("60s") java.time.Duration timeout,
            @DefaultValue("1") int maxRetries
    ) {}

    /**
     * @param maxDimension 장변 기준 리사이즈 크기. 토큰 비용에 직결된다.
     *                     1024px이면 장당 약 1300토큰.
     * @param maxUploadMb  업로드 허용 크기. 초과 시 요청을 거부한다.
     */
    public record Image(
            @DefaultValue("1024") int maxDimension,
            @DefaultValue("10") int maxUploadMb
    ) {}

    /**
     * @param confidenceThreshold 이 값 미만이면 전경 사진 재촬영을 요청한다.
     *                            2026-09-14 검증에서 부재 특정 실패가 반복 관찰되어
     *                            도입했다. 실사용 데이터를 보고 조정한다.
     * @param maxJudgements       한 사진에 제시할 최대 판정 수.
     * @param caseDisplayLimit    결과 화면에 노출할 공단 사례 수.
     */
    public record Judgement(
            @DefaultValue("0.5") double confidenceThreshold,
            @DefaultValue("3") int maxJudgements,
            @DefaultValue("3") int caseDisplayLimit
    ) {}

    /**
     * @param maxEntries 넘으면 오래 쓰이지 않은 항목부터 버린다.
     *                   응답 하나가 1KB 남짓이라 1000건이면 수 MB 수준이다.
     * @param ttl        공단 데이터가 바뀌지 않으므로 길게 잡아도 무방하다.
     */
    public record Cache(
            @DefaultValue("1000") int maxEntries,
            @DefaultValue("24h") java.time.Duration ttl
    ) {}
}
