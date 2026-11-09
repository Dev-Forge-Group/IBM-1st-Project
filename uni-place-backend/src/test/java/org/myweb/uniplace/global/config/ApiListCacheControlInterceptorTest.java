package org.myweb.uniplace.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class ApiListCacheControlInterceptorTest {

    private final ApiListCacheControlInterceptor interceptor = new ApiListCacheControlInterceptor(30, 60);

    @Test
    void viewerCachePolicyIsPresentBeforeAResponseBodyCommitsTheHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/boards/7");
        request.setContextPath("/api");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, handler())).isTrue();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
        response.flushBuffer();
        assertThat(response.isCommitted()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/boards", "/boards/search", "/boards/me", "/boards/7",
            "/boards/7/replies", "/boards/7/replies/8/children",
            "/replies", "/replies/me", "/replies/8",
            "/notifications", "/notifications/unread"
    })
    void communityReadsCannotBeSharedBetweenViewersEvenWhenTheyPreviouslyHadPublicCaching(String path)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("Cache-Control", "public, max-age=30");

        interceptor.postHandle(request, response, handler(), null);

        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    }

    @Test
    void authenticatedCommunityReadsRemainPrivateWithAnApplicationContextPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/boards/7/replies");
        request.setContextPath("/api");
        request.addHeader("Authorization", "Bearer test-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.postHandle(request, response, handler(), null);

        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/buildings", "/rooms", "/products", "/reviews", "/banners/active"})
    void otherPublicProductListsKeepTheirExistingCachingPolicy(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.postHandle(request, response, handler(), null);

        assertThat(response.getHeader("Cache-Control"))
                .isEqualTo("public, max-age=30, stale-while-revalidate=60");
    }

    @Test
    void anExistingNonCommunityCacheHeaderIsPreserved() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rooms");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("Cache-Control", "no-cache");

        interceptor.postHandle(request, response, handler(), null);

        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache");
    }

    @Test
    void authenticatedProductReadsDoNotAcquirePublicCaching() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rooms");
        request.addHeader("Authorization", "Bearer test-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.postHandle(request, response, handler(), null);

        assertThat(response.getHeader("Cache-Control")).isNull();
    }

    @Test
    void aSimilarPrefixDoesNotBecomeACommunityRoute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/boards-public");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.postHandle(request, response, handler(), null);

        assertThat(response.getHeader("Cache-Control")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE"})
    void nonGetRequestsKeepTheirPreviousHandling(String method) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/boards/7");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.postHandle(request, response, handler(), null);

        assertThat(response.getHeader("Cache-Control")).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 500})
    void errorResponsesKeepTheirPreviousHandling(int status) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/boards/7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);

        interceptor.postHandle(request, response, handler(), null);

        assertThat(response.getHeader("Cache-Control")).isNull();
    }

    @Test
    void nonControllerHandlersKeepTheirPreviousHandling() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/boards/7");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.postHandle(request, response, new Object(), null);

        assertThat(response.getHeader("Cache-Control")).isNull();
    }

    private static HandlerMethod handler() throws NoSuchMethodException {
        return new HandlerMethod(new TestController(), TestController.class.getDeclaredMethod("handle"));
    }

    static class TestController {
        public void handle() {
        }
    }
}
