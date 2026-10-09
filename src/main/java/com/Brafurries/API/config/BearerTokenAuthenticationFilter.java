package com.Brafurries.API.config;

import com.Brafurries.API.auth.common.TokenService;
import com.Brafurries.API.entity.user.UserTokenType;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTokenRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private final TokenService tokenService;
    private final UserRepository userRepository;
    private final UserTokenRepository userTokenRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String authorizationHeader = request.getHeader("Authorization");

        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            String token = authorizationHeader.substring(7).trim();
            Authentication authentication = tokenService.authenticateAccessToken(token);
            if (authentication != null && isAccountAllowed(authentication.getName())) {
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAccountAllowed(String principal) {
        return userRepository.findByEmail(principal)
            .map(user -> !userTokenRepository.existsByUserAndTypeAndUsedAtIsNullAndRevokedAtIsNull(
                user,
                UserTokenType.EMAIL_VERIFICATION
            ))
            .orElse(false);
    }
}
