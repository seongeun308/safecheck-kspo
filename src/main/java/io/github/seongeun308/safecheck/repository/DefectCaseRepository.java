package io.github.seongeun308.safecheck.repository;

import io.github.seongeun308.safecheck.domain.DefectCase;
import io.github.seongeun308.safecheck.domain.InspectionItem;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Repository
public class DefectCaseRepository {

    private static final String ITEMS_PATH = "data/inspection_items.json";
    private static final String CASES_PATH = "data/defect_cases.json";

    /**
     * 같은 점검항목이라면 해상도가 높은 사례를 먼저 노출한다.
     * 공단 이미지는 총 화소 중앙값이 약 8만(330x248)이고 편차가 크므로,
     * 판독 가능한 쪽을 우선한다. 동일 해상도면 순번 순.
     */
    private static final Comparator<DefectCase> DISPLAY_ORDER =
            Comparator.comparingInt(DefectCase::pixelCount).reversed()
                    .thenComparingInt(DefectCase::seq);

    private final List<InspectionItem> items;
    private final Map<Integer, InspectionItem> itemById;
    private final Map<Integer, List<DefectCase>> casesByItemId;

    @Getter
    private final int totalCaseCount;
    @Getter
    private final List<String> buildingTypes;
    @Getter
    private final List<String> positionTypes;

    public DefectCaseRepository(ObjectMapper objectMapper) {
        List<InspectionItem> loadedItems =
                readJson(objectMapper, ITEMS_PATH, new TypeReference<>() {});
        List<DefectCase> loadedCases =
                readJson(objectMapper, CASES_PATH, new TypeReference<>() {});

        this.items = List.copyOf(loadedItems);
        this.itemById = loadedItems.stream()
                .collect(Collectors.toUnmodifiableMap(InspectionItem::id, item -> item));
        this.casesByItemId = Map.copyOf(loadedCases.stream()
                .collect(Collectors.groupingBy(
                        DefectCase::itemId,
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                list -> list.stream().sorted(DISPLAY_ORDER).toList()))));
        this.totalCaseCount = loadedCases.size();
        this.buildingTypes = loadedCases.stream()
                .map(DefectCase::buildingType)
                .distinct().sorted().toList();
        this.positionTypes = loadedCases.stream()
                .map(DefectCase::positionType)
                .distinct().sorted().toList();

        verify();

        log.info("공단 데이터 적재 완료: 점검항목 {}종, 사례 {}건", items.size(), totalCaseCount);
    }

    private static <T> T readJson(ObjectMapper objectMapper, String path, TypeReference<T> type) {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readValue(in, type);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "공단 데이터 적재에 실패했습니다. classpath 경로를 확인하십시오: " + path, e);
        }
    }

    /**
     * 적재 직후 무결성을 확인한다.
     * 데이터 파일을 다시 생성했을 때 조용히 어긋나는 것을 막는다.
     */
    private void verify() {
        if (items.isEmpty()) {
            throw new IllegalStateException("점검항목이 비어 있습니다.");
        }

        // 사례가 참조하는 itemId가 모두 실재하는지
        List<Integer> orphans = casesByItemId.keySet().stream()
                .filter(id -> !itemById.containsKey(id))
                .sorted()
                .toList();
        if (!orphans.isEmpty()) {
            throw new IllegalStateException(
                    "점검항목에 없는 itemId를 참조하는 사례가 있습니다: " + orphans);
        }

        // inspection_items.json의 caseCount와 실제 사례 수가 일치하는지
        for (InspectionItem item : items) {
            int actual = casesOf(item.id()).size();
            if (actual != item.caseCount()) {
                throw new IllegalStateException(String.format(
                        "사례 수가 일치하지 않습니다. itemId=%d(%s) 선언=%d 실제=%d",
                        item.id(), item.shortName(), item.caseCount(), actual));
            }
        }

        // 사례가 없는 항목은 중단 사유는 아니나 화면 설계에 영향이 있으므로 남긴다
        List<String> empty = items.stream()
                .filter(item -> casesOf(item.id()).isEmpty())
                .map(InspectionItem::shortName)
                .toList();
        if (!empty.isEmpty()) {
            log.warn("참조 사례가 없는 점검항목: {}", empty);
        }
    }

    /** 점검항목 22종 전체. 프롬프트의 분류체계 조립에 사용한다. */
    public List<InspectionItem> items() {
        return items;
    }

    public Optional<InspectionItem> findItem(int itemId) {
        return Optional.ofNullable(itemById.get(itemId));
    }

    /**
     * 모델이 반환한 itemId가 22종 안에 있는지 확인한다.
     * 존재하지 않는 항목을 만들어낸 경우를 걸러내기 위한 것이다.
     */
    public boolean isValidItemId(int itemId) {
        return itemById.containsKey(itemId);
    }

    /** 해당 점검항목의 공단 실제 점검 사례. 해상도 내림차순. */
    public List<DefectCase> casesOf(int itemId) {
        return casesByItemId.getOrDefault(itemId, List.of());
    }

    public List<DefectCase> casesOf(int itemId, int limit) {
        List<DefectCase> all = casesOf(itemId);
        return all.size() <= limit ? all : List.copyOf(all.subList(0, limit));
    }
}
