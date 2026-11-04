package com.Brafurries.API.partner;

import com.Brafurries.API.entity.misc.Partner;
import com.Brafurries.API.entity.misc.PartnerExternalLink;
import com.Brafurries.API.repository.misc.PartnerRepository;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public projection only: no representative identities, contacts, private
 * references, metrics, tokens, or pending/suspended partners leave the API.
 */
@Service
public class PublicPartnersService {
    private final PartnerRepository partners;

    public PublicPartnersService(PartnerRepository partners) {
        this.partners = partners;
    }

    public record PublicPartnerItem(Integer id, String name, String category,
                                    String imageUrl, String url) {}

    @Transactional(readOnly = true)
    public List<PublicPartnerItem> listActive() {
        return partners.findActivePublicPartners().stream()
            .map(this::toPublic)
            .toList();
    }

    private PublicPartnerItem toPublic(Partner partner) {
        List<PartnerExternalLink> links = partner.getExternalLinks().stream()
            .sorted(Comparator.comparing(PartnerExternalLink::getId,
                Comparator.nullsLast(Integer::compareTo)))
            .toList();
        String selected = links.stream().filter(PartnerExternalLink::isPrimary)
            .map(PartnerExternalLink::getUrl).map(PublicPartnersService::safeHttpUrl)
            .filter(url -> url != null).findFirst().orElse(null);

        // Legacy partners have no is_primary flag until edited. Retain a usable
        // destination without inventing a link or accidentally exposing contacts.
        if (selected == null) selected = safeHttpUrl(partner.getWebsiteUrl());
        if (selected == null) {
            selected = links.stream().map(PartnerExternalLink::getUrl)
                .map(PublicPartnersService::safeHttpUrl).filter(url -> url != null)
                .findFirst().orElse(null);
        }
        return new PublicPartnerItem(partner.getId(), partner.getName(),
            partner.getCategory(), safeHttpUrl(partner.getImageUrl()), selected);
    }

    static String safeHttpUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String url = raw.trim();
        if (url.length() > 1024) return null;
        try {
            URI parsed = URI.create(url);
            String scheme = parsed.getScheme();
            return scheme != null && ("https".equalsIgnoreCase(scheme)
                || "http".equalsIgnoreCase(scheme))
                && parsed.getHost() != null && parsed.getUserInfo() == null
                ? url : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
