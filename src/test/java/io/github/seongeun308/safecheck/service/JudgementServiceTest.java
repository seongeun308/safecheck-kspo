package io.github.seongeun308.safecheck.service;

import io.github.seongeun308.safecheck.TestProperties;
import io.github.seongeun308.safecheck.client.JudgementClient;
import io.github.seongeun308.safecheck.domain.DefectCase;
import io.github.seongeun308.safecheck.dto.JudgementResponse;
import io.github.seongeun308.safecheck.dto.JudgementResult;
import io.github.seongeun308.safecheck.repository.DefectCaseRepository;
import io.github.seongeun308.safecheck.support.ImagePreprocessor;
import io.github.seongeun308.safecheck.support.JudgementCache;
import io.github.seongeun308.safecheck.support.RateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;

import static io.github.seongeun308.safecheck.TestImages.jpeg;
import static org.assertj.core.api.Assertions.assertThat;

class JudgementServiceTest {

    private static final String BUILDING_TYPE = "건물 내외부";
    private static final String POSITION_TYPE = "난간";
    private static final String OTHER_POSITION_TYPE = "창호";
    private static final String CLIENT_KEY = "127.0.0.1";

    // 공단 점검항목 id. 상세는 DefectCaseRepository의 항목 데이터를 참고.
    private static final int ITEM_CRACK = 1;       // 균열, 누수. 사례 20건이 있는 항목.
    private static final int ITEM_CORROSION = 3;   // 철골재 부식
    private static final int ITEM_FINISH = 6;      // 마감재파손
    private static final int ITEM_UNKNOWN = 999;   // 공단 22종에 없는 항목

    private static final int MAX_JUDGEMENTS = TestProperties.judgementDefaults().maxJudgements();
    private static final int CASE_LIMIT = TestProperties.judgementDefaults().caseDisplayLimit();
    private static final DefectCaseRepository REPOSITORY = new DefectCaseRepository(new ObjectMapper());

    private final StubJudgementClient client = new StubJudgementClient();
    private final byte[] photo = jpeg(1600, 1200);
    private final JudgementService service = new JudgementService(
            new ImagePreprocessor(TestProperties.imageDefaults()),
            client,
            new JudgementCache(TestProperties.cacheDefaults(), Clock.systemUTC()),
            REPOSITORY,
            TestProperties.judgementDefaults(),
            new RateLimiter(TestProperties.rateLimitDefaults(), Clock.systemUTC())
    );

    @Nested
    @DisplayName("판정 상태")
    class StatusDecision {

        @Test
        @DisplayName("확신도가 임계 이상이면 결함 판정으로 본다")
        void marksDefectFoundWhenConfidenceIsHigh() {
            client.respondWith(response(judgement(ITEM_CRACK, 0.85))
                    .notice("외부 계단 벽체 망상균열"));

            JudgementResult result = judge();

            assertThat(result.status()).isEqualTo(JudgementResult.Status.DEFECT_FOUND);
            assertThat(result.notice()).isEqualTo("외부 계단 벽체 망상균열");
        }

        @Test
        @DisplayName("확신도가 임계 미만이면 추가 촬영을 요청한다")
        void asksForBetterShotWhenConfidenceIsLow() {
            client.respondWith(response(judgement(ITEM_CRACK, 0.35)));

            JudgementResult result = judge();

            assertThat(result.status()).isEqualTo(JudgementResult.Status.NEEDS_BETTER_SHOT);
            assertThat(result.guidance()).contains("물러서서");
        }

        @Test
        @DisplayName("모델이 전경 사진을 요청하면 확신도와 무관하게 추가 촬영을 요청한다")
        void asksForBetterShotWhenModelRequests() {
            client.respondWith(response(judgement(ITEM_CRACK, 0.90)).needsWiderShot(true));

            JudgementResult result = judge();

            assertThat(result.status()).isEqualTo(JudgementResult.Status.NEEDS_BETTER_SHOT);
        }

        @Test
        @DisplayName("판정된 항목이 없으면 결함 없음으로 본다")
        void marksNoDefectWhenNothingJudged() {
            client.respondWithNoJudgements();

            JudgementResult result = judge();

            assertThat(result.status()).isEqualTo(JudgementResult.Status.NO_DEFECT);
            assertThat(result.notice()).isEmpty();
            assertThat(result.guidance()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("판정 항목 검증")
    class ItemValidation {

        @Test
        @DisplayName("공단 점검항목에 없는 항목은 제외한다")
        void dropsUnknownItemId() {
            client.respondWith(response(
                    judgement(ITEM_CRACK, 0.80),
                    judgement(ITEM_UNKNOWN, 0.70)));

            JudgementResult result = judge();

            assertThat(result.judgements())
                    .hasSize(1)
                    .first()
                    .extracting(JudgementResult.JudgedItem::itemId)
                    .isEqualTo(ITEM_CRACK);
        }

        @Test
        @DisplayName("항목명은 모델 응답이 아니라 공단 원문을 사용한다")
        void usesOfficialNamesFromRepository() {
            client.respondWith(response(
                    new JudgementResponse.Judgement(ITEM_CRACK, "균열,누수", 0.80, "근거")));

            JudgementResult result = judge();

            var item = REPOSITORY.findItem(ITEM_CRACK).orElseThrow();
            assertThat(result.judgements()).first()
                    .satisfies(judged -> {
                        assertThat(judged.shortName()).isEqualTo(item.shortName());
                        assertThat(judged.officialName()).isEqualTo(item.officialName());
                    });
        }

        @Test
        @DisplayName("확신도 내림차순으로 정렬한다")
        void sortsByConfidenceDescending() {
            client.respondWith(response(
                    judgement(ITEM_CRACK, 0.40),
                    judgement(ITEM_CORROSION, 0.90),
                    judgement(ITEM_FINISH, 0.65)));

            JudgementResult result = judge();

            assertThat(result.judgements())
                    .extracting(JudgementResult.JudgedItem::confidence)
                    .containsExactly(0.90, 0.65, 0.40);
        }

        @Test
        @DisplayName("설정한 개수를 넘는 판정은 잘라낸다")
        void limitsJudgementCount() {
            client.respondWith(response(
                    judgement(ITEM_CRACK, 0.90),
                    judgement(ITEM_CORROSION, 0.80),
                    judgement(ITEM_FINISH, 0.70),
                    judgement(11, 0.60)));

            JudgementResult result = judge();

            assertThat(result.judgements()).hasSize(MAX_JUDGEMENTS);
        }

        @Test
        @DisplayName("확신도가 범위를 벗어나면 0과 1 사이로 맞춘다")
        void clampsConfidenceRange() {
            client.respondWith(response(
                    judgement(ITEM_CRACK, 1.7),
                    judgement(ITEM_CORROSION, -0.3)));

            JudgementResult result = judge();

            assertThat(result.judgements())
                    .extracting(JudgementResult.JudgedItem::confidence)
                    .containsExactly(1.0, 0.0);
        }
    }

    @Nested
    @DisplayName("공단 사례 보강")
    class CaseAttachment {

        @Test
        @DisplayName("확신도 최상위 항목의 사례를 붙인다")
        void attachesCasesOfTopItem() {
            client.respondWith(response(
                    judgement(ITEM_CORROSION, 0.60),
                    judgement(ITEM_CRACK, 0.85)));

            JudgementResult result = judge();

            List<String> expected = REPOSITORY.casesOf(ITEM_CRACK, CASE_LIMIT).stream()
                    .map(DefectCase::notice)
                    .toList();
            assertThat(result.cases())
                    .extracting(JudgementResult.CaseView::notice)
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("사례 수는 설정한 개수를 넘지 않는다")
        void limitsCaseCount() {
            client.respondWith(response(judgement(ITEM_CRACK, 0.85)));

            JudgementResult result = judge();

            assertThat(result.cases()).hasSizeLessThanOrEqualTo(CASE_LIMIT);
        }

        @Test
        @DisplayName("사례 이미지 경로는 정적 리소스 경로로 만든다")
        void buildsStaticImageUrl() {
            client.respondWith(response(judgement(ITEM_CRACK, 0.85)));

            JudgementResult result = judge();

            assertThat(result.cases())
                    .isNotEmpty()
                    .allSatisfy(view -> assertThat(view.imageUrl()).startsWith("/cases/"));
        }

        @Test
        @DisplayName("결함이 없으면 사례를 붙이지 않는다")
        void attachesNoCaseWhenNoDefect() {
            client.respondWithNoJudgements();

            JudgementResult result = judge();

            assertThat(result.cases()).isEmpty();
        }
    }

    @Nested
    @DisplayName("전처리 연계")
    class Preprocessing {

        @Test
        @DisplayName("같은 사진은 같은 해시를 돌려준다")
        void returnsStableHash() {
            client.respondWith(response(judgement(ITEM_CRACK, 0.85)));

            String first = judge().imageHash();
            String second = judge().imageHash();

            assertThat(first).isEqualTo(second).hasSize(64);
        }

        @Test
        @DisplayName("전처리를 거친 이미지를 판정에 넘긴다")
        void passesPreprocessedImageToClient() {
            client.respondWith(response(judgement(ITEM_CRACK, 0.85)));

            judge();

            StubJudgementClient.Call call = client.lastCall();
            assertThat(call.image())
                    .as("원본이 아니라 전처리 결과가 넘어가야 한다")
                    .isNotNull()
                    .isNotEqualTo(photo);
            assertThat(call.mediaType()).isEqualTo(ImagePreprocessor.JPEG);
            assertThat(call.buildingType()).isEqualTo(BUILDING_TYPE);
            assertThat(call.positionType()).isEqualTo(POSITION_TYPE);
        }
    }

    @Nested
    @DisplayName("캐싱")
    class Caching {

        @Test
        @DisplayName("같은 사진을 다시 판정하면 모델을 호출하지 않는다")
        void reusesCachedResponse() {
            client.respondWith(response(judgement(ITEM_CRACK, 0.85)));

            judge();
            judge();

            assertThat(client.callCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("위치구분이 다르면 다시 판정한다")
        void judgesAgainForDifferentPosition() {
            client.respondWith(response(judgement(ITEM_CRACK, 0.85)));

            judge(POSITION_TYPE);
            judge(OTHER_POSITION_TYPE);

            assertThat(client.callCount()).isEqualTo(2);
        }
    }

    // ------------------------------------------------------------------
    // 도우미
    // ------------------------------------------------------------------

    private JudgementResult judge() {
        return judge(POSITION_TYPE);
    }

    private JudgementResult judge(String positionType) {
        return service.judge(photo, BUILDING_TYPE, positionType, CLIENT_KEY);
    }

    private static JudgementResponse.Judgement judgement(int itemId, double confidence) {
        return new JudgementResponse.Judgement(itemId, "항목" + itemId, confidence, "근거");
    }

    private static ResponseBuilder response(JudgementResponse.Judgement... judgements) {
        return new ResponseBuilder(List.of(judgements));
    }

    /** 응답의 기본값을 채우고 필요한 필드만 바꾼다. */
    private static final class ResponseBuilder {
        private final List<JudgementResponse.Judgement> judgements;
        private String notice = "소견";
        private boolean needsWiderShot = false;

        private ResponseBuilder(List<JudgementResponse.Judgement> judgements) {
            this.judgements = judgements;
        }

        ResponseBuilder notice(String notice) {
            this.notice = notice;
            return this;
        }

        ResponseBuilder needsWiderShot(boolean value) {
            this.needsWiderShot = value;
            return this;
        }

        JudgementResponse build() {
            return new JudgementResponse(
                    JudgementResponse.YES, judgements, notice,
                    new JudgementResponse.ImageQuality(true, ""),
                    needsWiderShot, "");
        }
    }

    /** 호출 인자를 기록하고 지정한 응답을 돌려준다. */
    private static final class StubJudgementClient implements JudgementClient {

        record Call(byte[] image, String mediaType, String buildingType, String positionType) {
        }

        private JudgementResponse response;
        private Call lastCall;
        private int callCount;

        void respondWith(ResponseBuilder builder) {
            this.response = builder.build();
        }

        void respondWithNoJudgements() {
            this.response = new JudgementResponse(
                    JudgementResponse.NO, List.of(), "",
                    new JudgementResponse.ImageQuality(true, ""), false, "");
        }

        Call lastCall() {
            return lastCall;
        }

        int callCount() {
            return callCount;
        }

        @Override
        public JudgementResponse judge(byte[] image, String mediaType,
                                       String buildingType, String positionType) {
            callCount++;
            lastCall = new Call(image, mediaType, buildingType, positionType);
            return response;
        }
    }
}
