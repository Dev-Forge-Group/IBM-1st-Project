package org.myweb.uniplace.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.myweb.uniplace.domain.user.domain.entity.User;
import org.myweb.uniplace.domain.user.repository.UserRepository;
import org.myweb.uniplace.global.exception.BusinessException;
import org.myweb.uniplace.global.exception.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;

    public JwtAuthFilter(JwtProvider jwtProvider, UserRepository userRepository) {
        this.jwtProvider = jwtProvider;
        this.userRepository = userRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return "/auth/guest-token".equals(path);
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        // No token: continue for public endpoints.
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);

        // 1) Signature/expiration validation.
        jwtProvider.validate(token);

        // 2) Validate access type and required claims before looking up a member.
        Authentication authentication = jwtProvider.getAuthentication(token);
        AuthUser tokenPrincipal = (AuthUser) authentication.getPrincipal();

        // 3) Guests keep their signed guest identity; member roles come from the DB.
        boolean guestRole = "guest".equalsIgnoreCase(tokenPrincipal.getRole());
        if (!guestRole) {
            User user = userRepository.findById(tokenPrincipal.getUserId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
            if (!user.canLogin() || user.getUserRole() == null) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED);
            }
            AuthUser currentPrincipal = new AuthUser(user.getUserId(), user.getUserRole().name());
            authentication = UsernamePasswordAuthenticationToken.authenticated(
                currentPrincipal, null, currentPrincipal.getAuthorities());
        }

        // 4) Publish the same current role to principal consumers and authorization checks.
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }
}
