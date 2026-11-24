package org.myweb.uniplace.domain.ai.application.gateway;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.net.SocketTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.myweb.uniplace.domain.ai.application.gateway.dto.AiGatewayRequest;
import org.myweb.uniplace.domain.ai.domain.AiIntent;
import org.myweb.uniplace.domain.ai.exception.AiServiceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AiGatewayImplTest {
    private static final String UPSTREAM = "https://ai.example.test";
    private static final String PRIVATE_TEXT = "synthetic-private-upstream-text";

    private MockRestServiceServer upstream;
    private AiGatewayImpl gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder client = RestClient.builder().baseUrl(UPSTREAM);
        upstream = MockRestServiceServer.bindTo(client).build();
        AiGatewayProperties properties = new AiGatewayProperties();
        properties.setBaseUrl(UPSTREAM);
        gateway = new AiGatewayImpl(client.build(), properties);
    }

    @Test
    void upstreamErrorsKeepTheStatusWithoutTheResponseBody() {
        upstream.expect(requestTo(UPSTREAM + "/api/v1/ai/chat/agent-chatbot"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"" + PRIVATE_TEXT + "\"}"));

        assertThatThrownBy(() -> gateway.execute(request()))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("status=503")
                .hasMessageNotContaining(PRIVATE_TEXT)
                .hasNoCause();
        upstream.verify();
    }

    @Test
    void transportFailuresOmitTheUpstreamExceptionDetails() {
        upstream.expect(requestTo(UPSTREAM + "/api/v1/ai/chat/agent-chatbot"))
                .andRespond(withException(new SocketTimeoutException(PRIVATE_TEXT)));

        assertThatThrownBy(() -> gateway.execute(request()))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("upstream unavailable")
                .hasMessageNotContaining(PRIVATE_TEXT)
                .hasNoCause();
        upstream.verify();
    }

    private AiGatewayRequest request() {
        return AiGatewayRequest.builder().intent(AiIntent.AI_AGENT_CHATBOT)
                .prompt("Synthetic request").build();
    }
}
