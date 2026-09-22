package io.github.seongeun308.safecheck.support;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 요청을 보낸 실제 접속 주소를 구한다.
 *
 * <p>Cloud Run 앞단의 Google 프록시를 거치므로 {@code getRemoteAddr()}는
 * 프록시 주소가 된다. 그대로 쓰면 모든 사용자가 한 주소로 묶여, 한 명이
 * 상한을 채우면 전원이 막힌다.
 *
 * <p>프록시는 실제 접속 주소를 {@code X-Forwarded-For}의 <b>맨 뒤</b>에
 * 덧붙인다. 맨 앞 값은 클라이언트가 임의로 넣을 수 있으므로 쓰지 않는다.
 * 앞단 프록시가 하나라는 전제이며, 로드밸런서를 추가하면 다시 확인해야 한다.
 */
public final class ClientIpResolver {

    static final String FORWARDED_FOR = "X-Forwarded-For";

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            String last = hops[hops.length - 1].trim();
            if (!last.isEmpty()) {
                return last;
            }
        }
        return request.getRemoteAddr();
    }
}
