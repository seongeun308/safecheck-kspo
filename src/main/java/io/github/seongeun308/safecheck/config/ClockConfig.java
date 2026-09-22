package io.github.seongeun308.safecheck.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 시간에 의존하는 컴포넌트가 공유하는 시계.
 *
 * <p>현재 시각을 직접 구하지 않고 이 빈을 주입받는다. 테스트에서는 시계를
 * 바꿔 끼워 만료나 시간 창 같은 동작을 기다리지 않고 검증한다.
 *
 * <p>사용처: {@code RateLimiter}(시간당·일일 상한), {@code JudgementCache}(유효기간)
 */
@Configuration
public class ClockConfig {

    /** 일일 상한의 날짜 경계는 한국 자정이다. */
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(KST);
    }
}
