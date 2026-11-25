package org.myweb.uniplace.global.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

@Component
public class ApiListCacheControlInterceptor implements HandlerInterceptor {

    private static final List<String> COMMUNITY_PATH_PATTERNS = List.of(
            "/boards", "/boards/**",
            "/replies", "/replies/**",
            "/notifications", "/notifications/**"
    );

    private static final List<String> BOOKING_AVAILABILITY_PATHS = List.of(
            "/tour-reservations/slots", "/space-reservations/spaces", "/space-reservations/buildings"
    );

    private static final List<String> LIST_CACHE_PATH_PATTERNS = List.of(
            "/buildings",
            "/rooms",
            "/spaces",
            "/products",
            "/banners",
            "/banners/active",
            "/reviews",
            "/boards",
            "/boards/search",
            "/notices",
            "/faqs",
            "/tour-reservations/rooms",
            "/admin/common-codes/PRODUCT_CATEGORY"
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final String cacheControlValue;

    public ApiListCacheControlInterceptor(
            @Value("${app.cache.list-api.max-age-seconds:30}") long maxAgeSeconds,
            @Value("${app.cache.list-api.stale-while-revalidate-seconds:60}") long staleWhileRevalidateSeconds
    ) {
        this.cacheControlValue = "public, max-age=" + maxAgeSeconds
                + ", stale-while-revalidate=" + staleWhileRevalidateSeconds;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // @ResponseBody may commit the response before postHandle on a real
        // servlet container, so set the cache policy before serialization.
        if ("GET".equalsIgnoreCase(request.getMethod()) && handler instanceof HandlerMethod
                && requiresNoStore(requestPath(request))) {
            response.setHeader("Cache-Control", "private, no-store");
        }
        return true;
    }

    @Override
    public void postHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            @Nullable ModelAndView modelAndView
    ) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return;
        }
        if (!(handler instanceof HandlerMethod)) {
            return;
        }
        if (response.getStatus() >= 400) {
            return;
        }

        String path = requestPath(request);

        // Community visibility varies by viewer and booking availability changes with approval and bookings.
        if (requiresNoStore(path)) {
            response.setHeader("Cache-Control", "private, no-store");
            return;
        }

        if (StringUtils.hasText(response.getHeader("Cache-Control"))) {
            return;
        }
        // Avoid caching potentially personalized responses from authenticated API calls.
        if (StringUtils.hasText(request.getHeader("Authorization"))) {
            return;
        }

        if (isListCacheTarget(path)) {
            response.setHeader("Cache-Control", cacheControlValue);
        }
    }

    private boolean isListCacheTarget(String requestPath) {
        return LIST_CACHE_PATH_PATTERNS.stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, requestPath));
    }

    private boolean requiresNoStore(String requestPath) {
        return BOOKING_AVAILABILITY_PATHS.contains(requestPath) || COMMUNITY_PATH_PATTERNS.stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, requestPath));
    }

    private static String requestPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        return StringUtils.hasText(contextPath) && path.startsWith(contextPath)
                ? path.substring(contextPath.length()) : path;
    }
}
