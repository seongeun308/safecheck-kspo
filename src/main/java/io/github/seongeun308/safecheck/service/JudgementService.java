package io.github.seongeun308.safecheck.service;

import io.github.seongeun308.safecheck.client.JudgementClient;
import io.github.seongeun308.safecheck.config.JudgementProperties;
import io.github.seongeun308.safecheck.domain.DefectCase;
import io.github.seongeun308.safecheck.domain.InspectionItem;
import io.github.seongeun308.safecheck.dto.JudgementResponse;
import io.github.seongeun308.safecheck.dto.JudgementResult;
import io.github.seongeun308.safecheck.repository.DefectCaseRepository;
import io.github.seongeun308.safecheck.support.ImagePreprocessor;
import io.github.seongeun308.safecheck.support.JudgementCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 사진 한 장을 받아 판정 결과를 만든다.
 *
 * <p>흐름은 다음과 같다.
 * <ol>
 *   <li>전처리 — 리사이즈, EXIF 보정, 해시
 *   <li>판정 요청
 *   <li>검증 — 공단 22종에 없는 항목을 걸러낸다
 *   <li>임계 판단 — 확신도가 낮으면 추가 촬영을 요청한다
 *   <li>근거 보강 — 공단 실제 점검 사례를 붙인다
 * </ol>
 */

@Slf4j
@Service
@RequiredArgsConstructor
public class JudgementService {

    private static final String GUIDE_WIDER_SHOT = """
            이 사진만으로는 부재를 특정하기 어렵습니다. \
            조금 물러서서 주변이 함께 보이는 사진을 한 장 더 올려주세요.""";

    private static final String GUIDE_NO_DEFECT = """
            제시된 사진에서 체육시설 안전점검 항목에 해당하는 결함이 관찰되지 않았습니다.""";

    private final ImagePreprocessor preprocessor;
    private final JudgementClient client;
    private final JudgementCache cache;
    private final DefectCaseRepository repository;
    private final JudgementProperties properties;

    public JudgementResult judge(byte[] image, String buildingType, String positionType) {
        if (!repository.getBuildingTypes().contains(buildingType)) {
            throw new IllegalArgumentException("알 수 없는 건물구분입니다: " + buildingType);
        }
        if (!repository.getPositionTypes().contains(positionType)) {
            throw new IllegalArgumentException("알 수 없는 위치구분입니다: " + positionType);
        }

        ImagePreprocessor.PreparedImage prepared = preprocessor.prepare(image);

        JudgementResponse response = cache
                .get(prepared.hash(), buildingType, positionType)
                .orElseGet(() -> {
                    JudgementResponse fresh = client.judge(
                            prepared.bytes(), prepared.mediaType(), buildingType, positionType);
                    cache.put(prepared.hash(), buildingType, positionType, fresh);
                    return fresh;
                });

        List<JudgementResult.JudgedItem> judgements = validate(response);
        double topConfidence = judgements.isEmpty() ? 0.0 : judgements.getFirst().confidence();

        JudgementResult.Status status = decideStatus(response, judgements, topConfidence);

        log.info("판정 완료: status={}, 항목={}건, 최고확신도={}, hash={}",
                status, judgements.size(), topConfidence,
                prepared.hash().substring(0, 8));

        return new JudgementResult(
                status,
                judgements,
                status == JudgementResult.Status.NO_DEFECT ? "" : nullToEmpty(response.notice()),
                guidanceFor(status, response),
                casesFor(judgements),
                prepared.hash());
    }

    /**
     * 모델이 돌려준 판정을 공단 22종과 대조한다.
     *
     * <p>목록에 없는 itemId를 만들어내는 경우가 있으므로 걸러낸다.
     * 항목명도 모델이 준 값이 아니라 공단 원문으로 바꿔 넣는다.
     */
    private List<JudgementResult.JudgedItem> validate(JudgementResponse response) {
        List<JudgementResult.JudgedItem> validated = response.judgementsOrEmpty().stream()
                .map(this::toValidatedItem)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingDouble(
                        JudgementResult.JudgedItem::confidence).reversed())
                .limit(properties.maxJudgements())
                .toList();

        int dropped = response.judgementsOrEmpty().size() - validated.size();
        if (dropped > 0) {
            log.warn("공단 점검항목에 없거나 초과된 판정 {}건을 제외했습니다.", dropped);
        }
        return validated;
    }

    private Optional<JudgementResult.JudgedItem> toValidatedItem(JudgementResponse.Judgement raw) {
        Optional<InspectionItem> item = repository.findItem(raw.itemId());
        if (item.isEmpty()) {
            log.warn("알 수 없는 itemId를 제외했습니다: {} ({})", raw.itemId(), raw.itemName());
            return Optional.empty();
        }
        InspectionItem found = item.get();
        return Optional.of(new JudgementResult.JudgedItem(
                found.id(),
                found.shortName(),
                found.officialName(),
                clamp(raw.confidence()),
                nullToEmpty(raw.evidence())));
    }

    private JudgementResult.Status decideStatus(JudgementResponse response,
                                                List<JudgementResult.JudgedItem> judgements,
                                                double topConfidence) {
        if (judgements.isEmpty()) {
            return JudgementResult.Status.NO_DEFECT;
        }
        // 모델이 직접 요청했거나, 확신도가 임계에 못 미치면 추가 촬영을 권한다.
        if (response.needsWiderShot() || topConfidence < properties.confidenceThreshold()) {
            return JudgementResult.Status.NEEDS_BETTER_SHOT;
        }
        return JudgementResult.Status.DEFECT_FOUND;
    }

    private static String guidanceFor(JudgementResult.Status status, JudgementResponse response) {
        String extra = nullToEmpty(response.noticeText());
        return switch (status) {
            case NEEDS_BETTER_SHOT -> extra.isBlank() ? GUIDE_WIDER_SHOT : GUIDE_WIDER_SHOT + " " + extra;
            case NO_DEFECT -> extra.isBlank() ? GUIDE_NO_DEFECT : extra;
            case DEFECT_FOUND -> extra;
        };
    }

    /**
     * 확신도가 가장 높은 항목의 공단 실제 사례를 붙인다.
     *
     * <p>판정 결과가 어떤 근거에서 나왔는지 사용자가 대조할 수 있게 하는 부분으로,
     * 공단 개방 데이터가 화면에서 직접 작동하는 지점이다.
     */
    private List<JudgementResult.CaseView> casesFor(List<JudgementResult.JudgedItem> judgements) {
        if (judgements.isEmpty()) {
            return List.of();
        }
        int topItemId = judgements.getFirst().itemId();
        return repository.casesOf(topItemId, properties.caseDisplayLimit()).stream()
                .map(JudgementService::toCaseView)
                .toList();
    }

    private static JudgementResult.CaseView toCaseView(DefectCase source) {
        return new JudgementResult.CaseView(
                source.notice(),
                "/cases/" + source.image(),
                source.inspectedOn());
    }

    private static double clamp(double confidence) {
        return Math.clamp(confidence, 0.0, 1.0);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
