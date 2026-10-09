package com.Brafurries.API.auth.common;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

@Service
public class AccessTokenService {

    private final Map<String, AccessTokenData> tokens = new ConcurrentHashMap<>();

    public void save(String token, String principal, Collection<String> roles, Collection<String> permissions, Instant expiresAt) {
        tokens.put(token, new AccessTokenData(principal, toAuthorities(roles, permissions), expiresAt));
    }

    public Authentication authenticate(String token) {
        AccessTokenData tokenData = tokens.get(token);
        if (tokenData == null || tokenData.expiresAt().isBefore(Instant.now())) {
            tokens.remove(token);
            return null;
        }

        return new UsernamePasswordAuthenticationToken(tokenData.principal(), token, tokenData.authorities());
    }

    private Collection<GrantedAuthority> toAuthorities(Collection<String> roles, Collection<String> permissions) {
        java.util.List<GrantedAuthority> authorities = new java.util.ArrayList<>();

        roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        permissions.forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));

        return authorities;
    }

    private record AccessTokenData(String principal, Collection<GrantedAuthority> authorities, Instant expiresAt) {
    }
}
