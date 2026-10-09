package com.Brafurries.API.partner;

import com.Brafurries.API.entity.misc.Partner;
import com.Brafurries.API.repository.misc.PartnerRepository;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PartnerSlugService {

    private static final Pattern SLUG_SANITIZER = Pattern.compile("[^a-z0-9-]");
    private static final Pattern MULTIPLE_DASHES = Pattern.compile("-+");

    private final PartnerRepository partnerRepository;

    public PartnerSlugService(PartnerRepository partnerRepository) {
        this.partnerRepository = partnerRepository;
    }

    public String uniqueSlug(String value, Integer currentPartnerId) {
        String base = slugify(value);
        String slug = base;
        int suffix = 2;
        while (partnerRepository.existsBySlug(slug)) {
            if (currentPartnerId != null) {
                Partner current = partnerRepository.findById(currentPartnerId).orElse(null);
                if (current != null && slug.equals(current.getSlug())) {
                    return slug;
                }
            }
            slug = base + "-" + suffix++;
        }
        return slug;
    }

    private String slugify(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slug obrigatorio");
        }
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replace(' ', '-');
        normalized = MULTIPLE_DASHES.matcher(SLUG_SANITIZER.matcher(normalized).replaceAll("-")).replaceAll("-")
            .replaceAll("^-|-$", "");
        if (normalized.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slug invalido");
        }
        return normalized;
    }
}
