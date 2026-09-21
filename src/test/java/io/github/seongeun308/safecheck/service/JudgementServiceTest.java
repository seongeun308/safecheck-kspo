package io.github.seongeun308.safecheck.service;

import io.github.seongeun308.safecheck.TestProperties;
import io.github.seongeun308.safecheck.client.JudgementClient;
import io.github.seongeun308.safecheck.config.CacheProperties;
import io.github.seongeun308.safecheck.config.ImageProperties;
import io.github.seongeun308.safecheck.config.JudgementProperties;
import io.github.seongeun308.safecheck.domain.DefectCase;
import io.github.seongeun308.safecheck.dto.JudgementResponse;
import io.github.seongeun308.safecheck.dto.JudgementResult;
import io.github.seongeun308.safecheck.repository.DefectCaseRepository;
import io.github.seongeun308.safecheck.support.ImagePreprocessor;
import io.github.seongeun308.safecheck.support.JudgementCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static io.github.seongeun308.safecheck.TestImages.jpeg;
import static org.assertj.core.api.Assertions.assertThat;

class JudgementServiceTest {

    private static final int MAX_JUDGEMENTS = 3;
    private static final int CASE_LIMIT = 3;

    private static final String BUILDING_TYPE = "건물 내외부";
    private static final String POSITION_TYPE = "난간";

    /** 균열, 누수. 사례 20건이 있는 항목. */
    private static final int ITEM_CRACK = 1;
    /** 철골재 부식 */
    private static final int ITEM_CORROSION = 3;
    /** 마감재파손 */
    private static final int ITEM_FINISH = 6;

    private static final ImageProperties IMAGE_PROPERTIES = TestProperties.imageDefaults();
    private static final CacheProperties CACHE_PROPERTIES = TestProperties.cacheDefaults();
    private static final JudgementProperties JUDGEMENT_PROPERTIES = TestProperties.judgementDefaults();

    private static final DefectCaseRepository REPOSITORY = new DefectCaseRepository(new ObjectMapper());

    private final StubJudgementClient client = new StubJudgementClient();
    private final JudgementCache cache = new JudgementCache(CACHE_PROPERTIES);
    private final JudgementService service = new JudgementService(
            new ImagePreprocessor(IMAGE_PROPERTIES),
            client,
            cache,
            REPOSITORY,
            JUDGEMENT_PROPERTIES
    );
    private final byte[] photo = jpeg(1600, 1200);

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("판정 상태")
    class StatusDecision {

        @Test
        @DisplayName("확신도가 임계 이상이면 결함 판정으로 본다")
        void marksDefectFoundWhenConfidenceIsHigh() {
            client.respondWith(response(judgement(ITEM_CRACK, 0.85))
                    .notice("외부 계단 벽체 망상균열")
                    .build());

            JudgementResult result = judge();

            assertThat(result.status()).isEqualTo(JudgementResult.Status.DEFECT_FOUND);
            assertThat(result.notice()).isEqualTo("외부 계단 벽체 망상균열");
        }

        @Test
        @DisplayName("확신도가 임계 미만이면 추가 촬영을 요청한다")
        void asksForBetterShotWhenConfidenceIsLow() {
            client.respondWith(response(judgement(ITEM_CRACK, 0.35)).build());

            JudgementResult result = judge();

            assertThat(result.status()).isEqualTo(JudgementResult.Status.NEEDS_BETTER_SHOT);
            assertThat(result.guidance()).contains("물러서서");
        }

        @Test
        @DisplayName("모델이 전경 사진을 요청하면 확신도와 무관하게 추가 촬영을 요청한다")
        void asksForBetterShotWhenModelRequests() {
            client.respondWith(response(judgement(ITEM_CRACK, 0.90))
                    .needsWiderShot(true)
                    .build());

            JudgementResult result = judge();

            assertThat(result.status()).isEqualTo(JudgementResult.Status.NEEDS_BETTER_SHOT);
        }

        @Test
        @DisplayName("판정된 항목이 없으면 결함 없음으로 본다")
        void marksNoDefectWhenNothingJudged() {
            client.respondWith(JudgementResponse.NO, List.of());

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
                    judgement(999, 0.70))
                    .build());

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
                    new JudgementResponse.Judgement(ITEM_CRACK, "균열,누수", 0.80, "근거"))
                    .build());

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
                    judgement(ITEM_FINISH, 0.65))
                    .build());

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
                    judgement(11, 0.60))
                    .build());

            JudgementResult result = judge();

            assertThat(result.judgements()).hasSize(MAX_JUDGEMENTS);
        }

        @Test
        @DisplayName("확신도가 범위를 벗어나면 0과 1 사이로 맞춘다")
        void clampsConfidenceRange() {
            client.respondWith(response(
                    judgement(ITEM_CRACK, 1.7),
                    judgement(ITEM_CORROSION, -0.3))
                    .build());

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
                    judgement(ITEM_CRACK, 0.85))
                    .build());

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
            client.respondWith(response(judgement(ITEM_CRACK, 0.85)).build());

            JudgementResult result = judge();

            assertThat(result.cases()).hasSizeLessThanOrEqualTo(CASE_LIMIT);
        }

        @Test
        @DisplayName("사례 이미지 경로는 정적 리소스 경로로 만든다")
        void buildsStaticImageUrl() {
            client.respondWith(response(judgement(ITEM_CRACK, 0.85)).build());

            JudgementResult result = judge();

            assertThat(result.cases())
                    .isNotEmpty()
                    .allSatisfy(view -> assertThat(view.imageUrl()).startsWith("/cases/"));
        }

        @Test
        @DisplayName("결함이 없으면 사례를 붙이지 않는다")
        void attachesNoCaseWhenNoDefect() {
            client.respondWith(JudgementResponse.NO, List.of());

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
            client.respondWith(response(judgement(ITEM_CRACK, 0.85)).build());

            String first = judge().imageHash();
            String second = judge().imageHash();

            assertThat(first).isEqualTo(second).hasSize(64);
        }

        @Test
        @DisplayName("전처리를 거친 이미지를 판정에 넘긴다")
        void passesPreprocessedImageToClient() {
            client.respondWith(response(judgement(ITEM_CRACK, 0.85)).build());

            judge();

            assertThat(client.lastImage)
                    .as("원본이 아니라 전처리 결과가 넘어가야 한다")
                    .isNotNull()
                    .isNotEqualTo(photo);
            assertThat(client.lastMediaType).isEqualTo(ImagePreprocessor.JPEG);
            assertThat(client.lastBuildingType).isEqualTo(BUILDING_TYPE);
            assertThat(client.lastPositionType).isEqualTo(POSITION_TYPE);
        }
    }

    @Nested
    @DisplayName("캐싱")
    class Caching {

        @Test
        @DisplayName("같은 사진을 다시 판정하면 모델을 호출하지 않는다")
        void reusesCachedResponse() {
            client.respondWith(response(judgement(ITEM_CRACK, 0.85)).build());

            judge();
            judge();

            assertThat(client.callCount).isEqualTo(1);
        }

        @Test
        @DisplayName("위치구분이 다르면 다시 판정한다")
        void judgesAgainForDifferentPosition() {
            client.respondWith(response(judgement(ITEM_CRACK, 0.85)).build());

            service.judge(photo, BUILDING_TYPE, "난간");
            service.judge(photo, BUILDING_TYPE, "창호");

            assertThat(client.callCount).isEqualTo(2);
        }
    }

    // ------------------------------------------------------------------
    // 도우미
    // ------------------------------------------------------------------

    private JudgementResult judge() {
        return service.judge(photo, BUILDING_TYPE, POSITION_TYPE);
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
        private String noticeText = "";

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

        ResponseBuilder noticeText(String noticeText) {
            this.noticeText = noticeText;
            return this;
        }

        JudgementResponse build() {
            return new JudgementResponse(
                    JudgementResponse.YES, judgements, notice,
                    new JudgementResponse.ImageQuality(true, ""),
                    needsWiderShot, noticeText);
        }
    }

    /** 호출 인자를 기록하고 지정한 응답을 돌려준다. */
    private static final class StubJudgementClient implements JudgementClient {

        private JudgementResponse response;

        private byte[] lastImage;
        private String lastMediaType;
        private String lastBuildingType;
        private String lastPositionType;
        private int callCount;

        void respondWith(JudgementResponse response) {
            this.response = response;
        }

        void respondWith(String defectObserved, List<JudgementResponse.Judgement> judgements) {
            this.response = new JudgementResponse(
                    defectObserved, judgements, "",
                    new JudgementResponse.ImageQuality(true, ""), false, "");
        }

        @Override
        public JudgementResponse judge(byte[] image, String mediaType,
                                       String buildingType, String positionType) {
            callCount++;
            this.lastImage = image;
            this.lastMediaType = mediaType;
            this.lastBuildingType = buildingType;
            this.lastPositionType = positionType;
            return response;
        }
    }
}
