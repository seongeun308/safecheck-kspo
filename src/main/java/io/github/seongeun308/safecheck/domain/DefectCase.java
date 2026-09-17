package io.github.seongeun308.safecheck.domain;

import java.time.LocalDate;

public record DefectCase(
        int seq,
        int itemId,
        String round,
        String notice,
        String buildingType,
        String positionType,
        String facilityType,
        LocalDate inspectedOn,
        String image,
        int width,
        int height
) {
    public int pixelCount() {
        return width * height;
    }
}
