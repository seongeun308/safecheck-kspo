package io.github.seongeun308.safecheck.support;

import jakarta.servlet.http.HttpServletRequest;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.regex.Pattern;

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

    /** 숫자와 구분자만 허용한다. 호스트 이름이 들어오면 DNS 조회를 하게 되므로 막는다. */
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9a-fA-F:.]+");

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

    /**
     * 호출 제한에 쓸 키.
     *
     * <p>IPv6는 기기가 뒤 64비트를 주기적으로 바꾸므로 앞 64비트를 한 사용자로 본다.
     * IPv4는 주소 그대로 쓴다.
     */
    public static String rateLimitKey(String ip) {
        if (ip == null || !IP_LITERAL.matcher(ip).matches()) {
            return "unknown";
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            if (address instanceof Inet6Address) {
                return "v6:" + HexFormat.of().formatHex(address.getAddress(), 0, 8);
            }
            return address.getHostAddress();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    /** 로그용. IPv4는 마지막 자리, IPv6는 앞 두 묶음만 남긴다. */
    public static String mask(String ip) {
        if (ip == null) return "***";
        if (ip.contains(":")) {
            String[] groups = ip.split(":");
            return groups.length >= 2 ? groups[0] + ":" + groups[1] + ":*" : "***";
        }
        int cut = ip.lastIndexOf('.');
        return cut > 0 ? ip.substring(0, cut) + ".*" : "***";
    }
}
