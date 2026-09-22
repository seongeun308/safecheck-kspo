package io.github.seongeun308.safecheck.controller;

import io.github.seongeun308.safecheck.domain.GradeStats;
import io.github.seongeun308.safecheck.repository.GradeStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 공단 안전점검 등급 분포.
 *
 * <p>배포 시점에 고정된 집계라 요청마다 바뀌지 않는다. 브라우저가 하루 동안
 * 다시 받지 않도록 캐시 헤더를 붙인다.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GradeStatsController {

    private final GradeStatsRepository repository;

    @GetMapping("/grade-stats")
    public ResponseEntity<GradeStats> gradeStats() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(repository.stats());
    }
}
