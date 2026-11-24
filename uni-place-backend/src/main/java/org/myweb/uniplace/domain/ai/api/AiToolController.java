package org.myweb.uniplace.domain.ai.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.myweb.uniplace.domain.ai.application.tool.AiToolExecutor;
import org.myweb.uniplace.domain.ai.application.tool.AiToolRequest;
import org.myweb.uniplace.domain.ai.application.tool.AiToolResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

/**
 * AI Tool 실행 컨트롤러 — 내부 서버 간 통신 전용.
 *
 * Python tool_orchestrator → 여기서 DB 조회 → 결과 반환.
 *
 * The internal token authenticates the service. Personal queries additionally
 * require an authenticated member in the executor.
 */
@RestController
@RequestMapping("/v1/ai/tools")
@RequiredArgsConstructor
public class AiToolController {

    private final AiToolExecutor toolExecutor;
    @Value("${ai.internal.token:}")
    private String internalToken;

    @PostMapping("/execute")
    public ResponseEntity<AiToolResponse> execute(
        @RequestHeader(value = "X-Internal-Token", required = false) String callerToken,
        @RequestBody AiToolRequest request
    ) {
        if (!StringUtils.hasText(internalToken)) {
            return respond(HttpStatus.SERVICE_UNAVAILABLE, AiToolResponse.fail("INTERNAL_AUTH_MISCONFIGURED"));
        }
        if (callerToken == null || !MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8), callerToken.getBytes(StandardCharsets.UTF_8))) {
            return respond(HttpStatus.UNAUTHORIZED, AiToolResponse.fail("INTERNAL_AUTH_INVALID"));
        }

        AiToolResponse response = toolExecutor.execute(request);
        HttpStatus status = !response.isSuccess() && "AUTH_REQUIRED".equals(response.getError())
                ? HttpStatus.UNAUTHORIZED : HttpStatus.OK;
        return respond(status, response);
    }

    private ResponseEntity<AiToolResponse> respond(HttpStatus status, AiToolResponse response) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(response);
    }
}
