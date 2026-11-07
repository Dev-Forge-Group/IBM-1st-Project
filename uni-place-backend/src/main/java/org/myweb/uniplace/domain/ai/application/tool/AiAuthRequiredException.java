package org.myweb.uniplace.domain.ai.application.tool;

/** The query reads personal data, so the caller has to sign in and use query_my_data. */
public class AiAuthRequiredException extends RuntimeException {

    public AiAuthRequiredException() {
        super("로그인이 필요한 데이터입니다.");
    }
}
