package io.github.seongeun308.safecheck.support;

import io.github.seongeun308.safecheck.TestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class RateLimiterTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 2026-09-21 10:00 KST */
    private static final Instant MORNING = Instant.parse("2026-09-21T01:00:00Z");

    private static final String CLIENT_A = "203.0.113.10";
    private static final String CLIENT_B = "203.0.113.20";

    private final MutableClock clock = new MutableClock(MORNING);

    @Nested
    @DisplayName("주소별 시간당 상한")
    class PerClient {

        @Test
        @DisplayName("상한까지는 허용한다")
        void allowsUpToLimit() {
            RateLimiter limiter = limiter(3, 100);

            assertThatNoException().isThrownBy(() -> {
                limiter.acquire(CLIENT_A);
                limiter.acquire(CLIENT_A);
                limiter.acquire(CLIENT_A);
            });
        }

        @Test
        @DisplayName("상한을 넘으면 거부한다")
        void rejectsOverLimit() {
            RateLimiter limiter = limiter(2, 100);
            limiter.acquire(CLIENT_A);
            limiter.acquire(CLIENT_A);

            assertThatThrownBy(() -> limiter.acquire(CLIENT_A))
                    .isInstanceOf(RateLimiter.RateLimitExceededException.class)
                    .satisfies(e -> assertThat(((RateLimiter.RateLimitExceededException) e).scope())
                            .isEqualTo(RateLimiter.Scope.CLIENT));
        }

        @Test
        @DisplayName("주소가 다르면 따로 센다")
        void countsClientsSeparately() {
            RateLimiter limiter = limiter(1, 100);
            limiter.acquire(CLIENT_A);

            assertThatNoException().isThrownBy(() -> limiter.acquire(CLIENT_B));
        }

        @Test
        @DisplayName("한 시간이 지나면 다시 허용한다")
        void resetsAfterWindow() {
            RateLimiter limiter = limiter(1, 100);
            limiter.acquire(CLIENT_A);

            clock.advance(Duration.ofHours(1));

            assertThatNoException().isThrownBy(() -> limiter.acquire(CLIENT_A));
        }

        @Test
        @DisplayName("거부할 때 남은 대기 시간을 알려준다")
        void reportsRetryAfter() {
            RateLimiter limiter = limiter(1, 100);
            limiter.acquire(CLIENT_A);
            clock.advance(Duration.ofMinutes(20));

            assertThatThrownBy(() -> limiter.acquire(CLIENT_A))
                    .satisfies(e -> assertThat(((RateLimiter.RateLimitExceededException) e).retryAfter())
                            .isEqualTo(Duration.ofMinutes(40)));
        }
    }

    @Nested
    @DisplayName("전체 일일 상한")
    class Daily {

        @Test
        @DisplayName("주소가 달라도 합산해서 거부한다")
        void rejectsWhenDailyTotalReached() {
            RateLimiter limiter = limiter(100, 2);
            limiter.acquire(CLIENT_A);
            limiter.acquire(CLIENT_B);

            assertThatThrownBy(() -> limiter.acquire("203.0.113.30"))
                    .satisfies(e -> assertThat(((RateLimiter.RateLimitExceededException) e).scope())
                            .isEqualTo(RateLimiter.Scope.DAILY));
        }

        @Test
        @DisplayName("한국 시간 자정이 지나면 초기화한다")
        void resetsAtKstMidnight() {
            RateLimiter limiter = limiter(100, 1);
            limiter.acquire(CLIENT_A);

            // 10:00 KST → 다음 날 00:00 KST
            clock.advance(Duration.ofHours(14));

            assertThatNoException().isThrownBy(() -> limiter.acquire(CLIENT_A));
        }

        @Test
        @DisplayName("남은 횟수를 알려준다")
        void reportsRemaining() {
            RateLimiter limiter = limiter(100, 5);
            limiter.acquire(CLIENT_A);
            limiter.acquire(CLIENT_B);

            assertThat(limiter.remainingToday()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("거부된 요청은 어느 상한에서도 차감하지 않는다")
    void doesNotConsumeWhenRejected() {
        RateLimiter limiter = limiter(1, 10);
        limiter.acquire(CLIENT_A);

        assertThatThrownBy(() -> limiter.acquire(CLIENT_A))
                .isInstanceOf(RateLimiter.RateLimitExceededException.class);

        assertThat(limiter.remainingToday()).isEqualTo(9);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("접속 주소 판별")
    class ClientIp {

        @Test
        @DisplayName("프록시 헤더가 없으면 연결 주소를 쓴다")
        void usesRemoteAddrWithoutHeader() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr(CLIENT_A);

            assertThat(ClientIpResolver.resolve(request)).isEqualTo(CLIENT_A);
        }

        @Test
        @DisplayName("프록시 헤더가 있으면 맨 뒤 주소를 쓴다")
        void usesLastForwardedHop() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("169.254.1.1");
            request.addHeader("X-Forwarded-For", CLIENT_A);

            assertThat(ClientIpResolver.resolve(request)).isEqualTo(CLIENT_A);
        }

        @Test
        @DisplayName("클라이언트가 헤더를 위조해도 프록시가 붙인 주소를 쓴다")
        void ignoresSpoofedLeadingHops() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Forwarded-For", "1.2.3.4, " + CLIENT_A);

            assertThat(ClientIpResolver.resolve(request)).isEqualTo(CLIENT_A);
        }
    }

    // ------------------------------------------------------------------

    private RateLimiter limiter(int perClientHourly, int dailyTotal) {
        return new RateLimiter(TestProperties.rateLimitOf(perClientHourly, dailyTotal), clock);
    }

    /** 테스트 안에서 시간을 앞으로 돌린다. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return KST;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
