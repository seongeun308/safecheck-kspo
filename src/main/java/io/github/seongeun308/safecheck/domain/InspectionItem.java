package io.github.seongeun308.safecheck.domain;

public record InspectionItem(
        int id,
        String shortName,
        String officialName,
        int caseCount
) {}
