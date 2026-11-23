package org.myweb.uniplace.domain.ai.application.usecase;

import org.myweb.uniplace.domain.ai.application.gateway.AiGateway;
import org.myweb.uniplace.domain.ai.application.gateway.dto.AiGatewayRequest;
import org.myweb.uniplace.domain.ai.application.gateway.dto.AiGatewayResponse;
import org.myweb.uniplace.domain.ai.domain.AiIntent;
import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class AdminChatbotUseCase extends AbstractForwardUseCase {

    public AdminChatbotUseCase(AiGateway aiGateway) {
        super(aiGateway);
    }

    @Override
    public AiGatewayResponse execute(AiGatewayRequest request) {
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    @Override
    protected AiIntent getIntent() {
        return AiIntent.ADMIN_CHATBOT;
    }
}
