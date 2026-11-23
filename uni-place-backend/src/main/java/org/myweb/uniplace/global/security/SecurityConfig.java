package org.myweb.uniplace.global.security;

import lombok.RequiredArgsConstructor;
import org.myweb.uniplace.domain.user.repository.UserRepository;
import org.myweb.uniplace.domain.ownership.application.publicapi.OwnershipAccess;
import org.myweb.uniplace.global.security.oauth.CustomOAuth2UserService;
import org.myweb.uniplace.global.security.oauth.CustomAuthorizationRequestResolver;
import org.myweb.uniplace.global.security.oauth.HttpCookieOAuth2AuthorizationRequestRepository;
import org.myweb.uniplace.global.security.oauth.OAuth2FailureHandler;
import org.myweb.uniplace.global.security.oauth.OAuth2SuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;
    private final OwnershipAccess ownershipAccess;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final OAuth2FailureHandler oAuth2FailureHandler;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;
    private final ClientRegistrationRepository clientRegistrationRepository;
    @Value("${APP_CORS_ALLOWED_ORIGINS:*}")
    private String corsAllowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        JwtAuthFilter jwtAuthFilter = new JwtAuthFilter(jwtProvider, userRepository, ownershipAccess);
        var consumerOrAnonymous = new WebExpressionAuthorizationManager(
                "isAnonymous() or hasAnyRole('GUEST', 'USER', 'TENANT', 'OWNER')");

        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(eh -> eh
                .authenticationEntryPoint(restAuthenticationEntryPoint)
                .accessDeniedHandler(restAccessDeniedHandler)
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(new RegexRequestMatcher("(?i)^/static-files/(?:contract|room|building|space)(?:[/?].*)?$", null)).denyAll()
                .requestMatchers("/files/admin", "/files/admin/**").denyAll()
                .requestMatchers("/admin/users", "/admin/users/**",
                        "/admin/owner-applications", "/admin/owner-applications/**",
                        "/admin/asset-claims", "/admin/asset-claims/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/admin/common-codes/PRODUCT_CATEGORY").permitAll()
                .requestMatchers("/admin", "/admin/**").denyAll()
                .requestMatchers("/owner", "/owner/**").hasRole("OWNER")
                .requestMatchers("/users/me", "/users/me/**", "/notifications", "/notifications/**")
                        .hasAnyRole("ADMIN", "USER", "TENANT", "OWNER")
                .requestMatchers("/auth/signup").permitAll()
                .requestMatchers("/auth/login").permitAll()
                .requestMatchers("/auth/guest-token").permitAll()
                .requestMatchers("/auth/refresh").permitAll()
                .requestMatchers("/auth/logout").permitAll()
                .requestMatchers("/auth/check-nickname").permitAll()
                .requestMatchers("/auth/oauth2/kakao/complete").permitAll()
                .requestMatchers("/auth/oauth2/google/complete").permitAll()
                // Global business analytics are not account-administration capabilities.
                .requestMatchers("/ai/chat/admin-chatbot").denyAll()
                .requestMatchers("/ai/**").hasAnyRole("USER", "TENANT", "OWNER")
                .requestMatchers("/error").permitAll()
                .requestMatchers("/member/login").permitAll()
                .requestMatchers("/login/**").permitAll()
                .requestMatchers("/oauth2/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/swagger-ui/**", "/v4/api-docs/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/payments/callback/*/approval").permitAll()
                .requestMatchers(HttpMethod.GET, "/payments/callback/*/cancel").permitAll()
                .requestMatchers(HttpMethod.GET, "/payments/callback/*/fail").permitAll()
                .requestMatchers("/payments/webhook/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/tour-reservations/rooms").permitAll()
                .requestMatchers(HttpMethod.GET, "/tour-reservations/slots").permitAll()
                .requestMatchers(HttpMethod.POST, "/tour-reservations").access(consumerOrAnonymous)
                .requestMatchers(HttpMethod.POST, "/tour-reservations/lookup").access(consumerOrAnonymous)
                .requestMatchers(HttpMethod.PUT, "/tour-reservations/cancel/**").access(consumerOrAnonymous)
                .requestMatchers(HttpMethod.GET, "/buildings/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/buildings").permitAll()
                .requestMatchers(HttpMethod.GET, "/rooms/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/rooms").permitAll()
                .requestMatchers(HttpMethod.GET, "/spaces/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/spaces").permitAll()
                .requestMatchers(HttpMethod.GET, "/products").permitAll()
                .requestMatchers(HttpMethod.GET, "/products/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/banners").permitAll()
                .requestMatchers(HttpMethod.GET, "/banners/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/reviews").permitAll()
                .requestMatchers(HttpMethod.GET, "/reviews/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/boards").permitAll()
                .requestMatchers(HttpMethod.GET, "/boards/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/reviews/*/likes").hasAnyRole("USER", "TENANT", "OWNER")
                .requestMatchers(HttpMethod.DELETE, "/reviews/*/likes").hasAnyRole("USER", "TENANT", "OWNER")
                .requestMatchers(HttpMethod.POST, "/reviews").hasAnyRole("USER", "TENANT", "OWNER")
                .requestMatchers(HttpMethod.PUT, "/reviews/**").hasAnyRole("USER", "TENANT", "OWNER")
                .requestMatchers(HttpMethod.DELETE, "/reviews/**").hasAnyRole("USER", "TENANT", "OWNER")
                .requestMatchers(HttpMethod.GET, "/notices").permitAll()
                .requestMatchers(HttpMethod.GET, "/notices/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/notices").denyAll()
                .requestMatchers(HttpMethod.PUT, "/notices/**").denyAll()
                .requestMatchers(HttpMethod.DELETE, "/notices/**").denyAll()
                .requestMatchers(HttpMethod.GET, "/faqs").permitAll()
                .requestMatchers(HttpMethod.GET, "/faqs/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/faqs").denyAll()
                .requestMatchers(HttpMethod.PUT, "/faqs/**").denyAll()
                .requestMatchers(HttpMethod.DELETE, "/faqs/**").denyAll()
                .requestMatchers(HttpMethod.GET, "/complains/me").hasAnyRole("USER", "TENANT", "OWNER")
                .requestMatchers(HttpMethod.GET, "/complains").hasAnyRole("USER", "TENANT", "OWNER")
                .requestMatchers(HttpMethod.GET, "/complains/**").hasAnyRole("USER", "TENANT", "OWNER")
                .requestMatchers(HttpMethod.POST, "/complains").hasRole("TENANT")
                .requestMatchers(HttpMethod.PUT, "/complains/**").hasRole("TENANT")
                .requestMatchers(HttpMethod.PATCH, "/complains/**").denyAll()
                .requestMatchers(HttpMethod.DELETE, "/complains/**").hasRole("TENANT")
                .requestMatchers(HttpMethod.GET, "/qna/all").denyAll()
                .requestMatchers(HttpMethod.GET, "/qna").hasAnyRole("USER", "TENANT", "OWNER")
                .requestMatchers(HttpMethod.GET, "/qna/**").hasAnyRole("USER", "TENANT", "OWNER")
                .requestMatchers(HttpMethod.POST, "/qna").hasRole("TENANT")
                .requestMatchers(HttpMethod.POST, "/qna/**").hasRole("TENANT")
                .requestMatchers(HttpMethod.PUT, "/qna/**").hasRole("TENANT")
                .requestMatchers(HttpMethod.PATCH, "/qna/**").denyAll()
                .requestMatchers(HttpMethod.DELETE, "/qna/**").hasRole("TENANT")
                .requestMatchers(HttpMethod.GET, "/files/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/files").permitAll()            
                .requestMatchers(HttpMethod.POST, "/files").hasAnyRole("USER", "TENANT", "OWNER")
                .requestMatchers("/auth/find-email").permitAll()
                .requestMatchers("/auth/reset-password/request").permitAll()
                .requestMatchers("/auth/reset-password/verify").permitAll()
                .requestMatchers("/auth/reset-password/confirm").permitAll()
                .requestMatchers("/auth/face/login").permitAll()     // 얼굴 로그인 (하위호환)
                .requestMatchers("/auth/face/match").permitAll()     // 1단계: 얼굴 매칭
                .requestMatchers("/auth/face/select").permitAll()    // 2단계: 계정 선택
                .requestMatchers("/auth/face/register").hasAnyRole("ADMIN", "USER", "TENANT", "OWNER")
                .requestMatchers("/auth/face/count").hasAnyRole("ADMIN", "USER", "TENANT", "OWNER")
                .requestMatchers("/auth/face").hasAnyRole("ADMIN", "USER", "TENANT", "OWNER")
                .requestMatchers("/v1/ai/tools/execute").access(consumerOrAnonymous)
                .requestMatchers(HttpMethod.POST, "/v1/ai/files/upload").hasAnyRole("USER", "TENANT", "OWNER")
                .requestMatchers("/auth/email/send-code").permitAll()
                .requestMatchers("/auth/email/verify-code").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/files").hasAnyRole("USER", "TENANT", "OWNER")
                .anyRequest().hasAnyRole("USER", "TENANT", "OWNER")
            )
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .authorizationEndpoint(authorization -> authorization
                    .authorizationRequestResolver(new CustomAuthorizationRequestResolver(clientRegistrationRepository))
                    .authorizationRequestRepository(new HttpCookieOAuth2AuthorizationRequestRepository())
                )
                .successHandler(oAuth2SuccessHandler)
                .failureHandler(oAuth2FailureHandler)
                .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/member/logout")
                .logoutSuccessUrl("/")
                .permitAll()
            );

        http.addFilterBefore(new JwtExceptionFilter(), UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(jwtAuthFilter, JwtExceptionFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(
            Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList()
        );
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
