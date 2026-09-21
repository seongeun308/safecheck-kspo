package io.github.seongeun308.safecheck.controller;

import io.github.seongeun308.safecheck.domain.InspectionItem;
import io.github.seongeun308.safecheck.dto.JudgementResult;
import io.github.seongeun308.safecheck.repository.DefectCaseRepository;
import io.github.seongeun308.safecheck.service.JudgementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

@RestController
@RequestMapping("/api")
@Slf4j
@RequiredArgsConstructor
public class JudgementController {

    private final JudgementService judgementService;
    private final DefectCaseRepository repository;

    /**
     * 사진 한 장을 판정한다.
     *
     * <pre>
     * curl -X POST http://localhost:8080/api/judgements \
     *   -F "image=@photo.jpg" \
     *   -F "buildingType=건물 내외부" \
     *   -F "positionType=난간"
     * </pre>
     */
    @PostMapping(value = "/judgements", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public JudgementResult judge(
            @RequestParam("image") MultipartFile image,
            @RequestParam("buildingType") String buildingType,
            @RequestParam("positionType") String positionType) {

        log.info("판정 요청: {}바이트, 건물구분={}, 위치구분={}",
                image.getSize(), buildingType, positionType);

        return judgementService.judge(readBytes(image), buildingType, positionType);
    }

    /** 입력 폼에 쓸 건물구분·위치구분 목록. 공단 데이터에 실재하는 값만 내린다. */
    @GetMapping("/locations")
    public LocationOptions locations() {
        return new LocationOptions(repository.getBuildingTypes(), repository.getPositionTypes());
    }

    /** 공단 공식 점검항목 22종. */
    @GetMapping("/inspection-items")
    public List<InspectionItem> inspectionItems() {
        return repository.getItems();
    }

    private static byte[] readBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("사진을 첨부해주세요.");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public record LocationOptions(List<String> buildingTypes, List<String> positionTypes) {}
}
