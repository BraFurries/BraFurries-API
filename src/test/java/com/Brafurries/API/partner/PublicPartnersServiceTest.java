package com.Brafurries.API.partner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.misc.Partner;
import com.Brafurries.API.entity.misc.PartnerExternalLink;
import com.Brafurries.API.repository.misc.PartnerRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicPartnersServiceTest {
    private final PartnerRepository repository = mock(PartnerRepository.class);
    private final PublicPartnersService service = new PublicPartnersService(repository);

    @Test
    void primaryOverridesLegacyWebsiteAndOnlyPublicFieldsAreReturned() {
        Partner partner = partner(1, "Comunidade", "https://legacy.example");
        partner.setSecret("never-public");
        partner.setContactEmail("private@example.com");
        partner.getExternalLinks().add(link(partner, 1, false, "https://social.example"));
        partner.getExternalLinks().add(link(partner, 2, true, "https://principal.example"));
        when(repository.findActivePublicPartners()).thenReturn(List.of(partner));

        var result = service.listActive();
        assertEquals(1, result.size());
        assertEquals("https://principal.example", result.getFirst().url());
        assertEquals(5, result.getFirst().getClass().getRecordComponents().length);
        verify(repository).findActivePublicPartners();
    }

    @Test
    void legacyFallbackUsesWebsiteThenFirstValidLinkAndSupportsNoLink() {
        Partner old = partner(1, "Antigo", "https://site.example");
        old.getExternalLinks().add(link(old, 1, false, "https://discord.gg/example"));
        Partner withoutWebsite = partner(2, "Segundo", "javascript:alert(1)");
        withoutWebsite.getExternalLinks().add(link(withoutWebsite, 2, false, "https://discord.gg/outro"));
        Partner withoutLink = partner(3, "Sem link", null);
        when(repository.findActivePublicPartners()).thenReturn(List.of(old, withoutWebsite, withoutLink));

        var items = service.listActive();
        assertEquals("https://site.example", items.get(0).url());
        assertEquals("https://discord.gg/outro", items.get(1).url());
        assertNull(items.get(2).url());
    }

    @Test
    void unsafeSchemesAndCredentialsNeverBecomeClickableLinks() {
        assertNull(PublicPartnersService.safeHttpUrl("javascript:alert(1)"));
        assertNull(PublicPartnersService.safeHttpUrl("https://user:pass@example.com"));
        assertNull(PublicPartnersService.safeHttpUrl("data:text/html,abc"));
        assertNull(PublicPartnersService.safeHttpUrl("not-a-url"));
        assertEquals("https://discord.gg/indie",
            PublicPartnersService.safeHttpUrl(" https://discord.gg/indie "));
    }

    private Partner partner(int id, String name, String website) {
        Partner p = new Partner();
        p.setId(id);
        p.setName(name);
        p.setCategory("community");
        p.setStatus("active");
        p.setWebsiteUrl(website);
        return p;
    }

    private PartnerExternalLink link(Partner p, int id, boolean primary, String url) {
        PartnerExternalLink l = new PartnerExternalLink();
        l.setId(id);
        l.setPartner(p);
        l.setPrimary(primary);
        l.setType("website");
        l.setUrl(url);
        return l;
    }
}
