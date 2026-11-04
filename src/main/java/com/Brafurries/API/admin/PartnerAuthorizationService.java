package com.Brafurries.API.admin;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Allows legacy global administrators plus explicitly delegated BraFurries
 * partner managers. No Community Admin or frontend flag is trusted.
 */
@Service("partnerAuthorizationService")
public class PartnerAuthorizationService {
    private final PlatformAuthorizationService authorization;

    public PartnerAuthorizationService(PlatformAuthorizationService authorization) {
        this.authorization = authorization;
    }

    public boolean canManage(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        // Preserve existing global /admin/partners access without changing
        // other /admin/** authorization contracts.
        if (authentication.getAuthorities() != null && authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
            return true;
        }
        // Resolver checks explicit owner-approved person AND an active role
        // assigned inside the configured official BraFurries Community.
        return authorization.resolve(authentication).access().has(PlatformPermission.PARTNERS_MANAGE);
    }
}
