package io.github.seongeun308.safecheck;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// 실제 키는 배포 환경변수로만 주입한다. 컨텍스트 로딩 확인에는 더미 값으로 충분하다.
@SpringBootTest(properties = "safecheck.llm.api-key=test-api-key")
class SafecheckApplicationTests {

    @Test
    void contextLoads() {
    }

}
