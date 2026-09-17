package io.github.seongeun308.safecheck;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/*
 * 실제 LLM 클라이언트가 없는 동안에는 mock 프로파일에서만 JudgementClient 빈이 등록된다.
 * mock에서는 LlmApiKeyValidator도 빠지므로 API 키를 주입할 필요가 없다.
 */
@SpringBootTest
@ActiveProfiles("mock")
class SafecheckApplicationTests {

    @Test
    void contextLoads() {
    }

}
