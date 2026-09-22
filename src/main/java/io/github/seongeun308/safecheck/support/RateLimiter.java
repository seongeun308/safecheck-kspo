package io.github.seongeun308.safecheck.support;

import io.github.seongeun308.safecheck.config.RateLimitProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static io.github.seongeun308.safecheck.config.ClockConfig.KST;

/**
 * 판정 모델 호출 횟수를 제한한다.
 *
 * <p>서비스 URL이 공개되면 누구나 호출할 수 있고, 호출마다 비용이 든다.
 * 지출 한도에 걸리면 심사 기간에 서비스 전체가 멈추므로, 그보다 앞에서
 * 막는다.
 *
 * <p>두 가지를 동시에 본다.
 * <ul>
 *   <li>접속 주소별 시간당 상한 — 한 사람의 반복 호출을 막는다
 *   <li>전체 일일 상한 — 여러 주소에서 몰려도 하루 비용을 묶어둔다
 * </ul>
 *
 * <p>캐시 적중은 비용이 없으므로 세지 않는다. 호출하는 쪽에서 캐시를 먼저
 * 확인하고, 실제로 모델을 부를 때만 {@link #acquire}를 호출한다.
 *
 * <p>인스턴스 하나만 띄우는 구성이라 메모리에 둔다. 두 상한을 함께 확인하고
 * 함께 차감해야 하므로 메서드 단위로 동기화한다. 호출 빈도가 낮아 경합은
 * 문제가 되지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);

    /** 주소별 기록이 이 수를 넘으면 만료된 항목을 정리한다. */
    private static final int PURGE_THRESHOLD = 10_000;

    private final RateLimitProperties properties;
    private final Clock clock;

    private final Map<String, Window> windows = new HashMap<>();
    private LocalDate day;
    private int dayCount;

    @PostConstruct
    private void init() {
        log.info("호출 제한: 주소별 시간당 {}회, 전체 일일 {}회", properties.perClientHourly(), properties.dailyTotal());
    }

    /**
     * 호출 한 번을 차감한다. 상한을 넘으면 예외를 던지고 아무것도 차감하지 않는다.
     *
     * @throws RateLimitExceededException 상한 초과
     */
    public synchronized void acquire(String clientKey) {
        Instant now = clock.instant();

        resetDayIfChanged(now);
        if (dayCount >= properties.dailyTotal()) {
            log.warn("전체 일일 상한 도달: {}회", properties.dailyTotal());
            throw new RateLimitExceededException(
                    Scope.DAILY, untilNextDay(now),
                    "오늘 판정 가능 횟수를 모두 사용했습니다. 내일 다시 이용해주세요.");
        }

        Window window = windows.get(clientKey);
        if (window == null || window.isExpired(now)) {
            window = new Window(now, 0);
        }
        if (window.count() >= properties.perClientHourly()) {
            Duration retryAfter = Duration.between(now, window.start().plus(WINDOW));
            log.warn("주소별 상한 도달: {} ({}회/시간)", mask(clientKey), properties.perClientHourly());
            throw new RateLimitExceededException(
                    Scope.CLIENT, retryAfter,
                    "요청이 많습니다. %d분 후에 다시 시도해주세요."
                            .formatted(Math.max(1, retryAfter.toMinutes())));
        }

        windows.put(clientKey, new Window(window.start(), window.count() + 1));
        dayCount++;

        log.info("판정 호출 허용: 오늘 {}/{}회", dayCount, properties.dailyTotal());
        purgeIfLarge(now);
    }

    /** 오늘 남은 호출 수. 운영 중 확인용. */
    public synchronized int remainingToday() {
        resetDayIfChanged(clock.instant());
        return Math.max(0, properties.dailyTotal() - dayCount);
    }

    // ------------------------------------------------------------------

    private void resetDayIfChanged(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, KST);
        if (!today.equals(day)) {
            day = today;
            dayCount = 0;
        }
    }

    private static Duration untilNextDay(Instant now) {
        Instant nextMidnight = LocalDate.ofInstant(now, KST)
                .plusDays(1)
                .atStartOfDay(KST)
                .toInstant();
        return Duration.between(now, nextMidnight);
    }

    private void purgeIfLarge(Instant now) {
        if (windows.size() > PURGE_THRESHOLD) {
            windows.values().removeIf(w -> w.isExpired(now));
        }
    }

    /** 로그에 주소 전체를 남기지 않는다. */
    private static String mask(String clientKey) {
        int cut = clientKey.lastIndexOf('.');
        return cut > 0 ? clientKey.substring(0, cut) + ".*" : "***";
    }

    private record Window(Instant start, int count) {
        boolean isExpired(Instant now) {
            return !now.isBefore(start.plus(WINDOW));
        }
    }

    public enum Scope {
        /** 한 접속 주소의 시간당 상한 */
        CLIENT,
        /** 서비스 전체의 일일 상한 */
        DAILY
    }

    public static class RateLimitExceededException extends RuntimeException {

        private final Scope scope;
        private final Duration retryAfter;

        public RateLimitExceededException(Scope scope, Duration retryAfter, String message) {
            super(message);
            this.scope = scope;
            this.retryAfter = retryAfter;
        }

        public Scope scope() {
            return scope;
        }

        public Duration retryAfter() {
            return retryAfter;
        }
    }
}
