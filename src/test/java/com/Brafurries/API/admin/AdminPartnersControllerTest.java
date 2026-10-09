package com.Brafurries.API.admin;

import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.Brafurries.API.admin.dto.AdminDtos.AdminPartnerItem;
import com.Brafurries.API.admin.dto.AdminDtos.PartnerExternalLinkItem;
import com.Brafurries.API.admin.dto.AdminDtos.PartnerLinkedResource;
import com.Brafurries.API.admin.dto.AdminDtos.PartnerLinkCandidate;
import com.Brafurries.API.admin.dto.AdminDtos.PartnerImageResponse;
import com.Brafurries.API.admin.dto.AdminDtos.PartnerRepresentative;
import com.Brafurries.API.admin.dto.AdminDtos.PartnerPrefill;
import com.Brafurries.API.config.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class AdminPartnersControllerTest {

    private AdminPartnersService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(AdminPartnersService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AdminPartnersController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void getExistingPartnerReturnsCompleteSafeContract() throws Exception {
        when(service.get(7)).thenReturn(new AdminPartnerItem(
            7,
            "Parceiro",
            "Representante",
            11,
            new PartnerRepresentative(11, "Representante", "https://cdn.example/avatar.webp", "rep@example.com", "discord-fox", "telegram-fox"),
            "active",
            "community",
            "Descricao",
            "https://cdn.example/image.png",
            "https://example.com",
            "Contato",
            "contato@example.com",
            new PartnerLinkedResource("event", 20, null, "Evento"),
            List.of(new PartnerExternalLinkItem("instagram", "Instagram", "https://instagram.com/example")),
            123,
            45,
            "2026-01-02T03:04"
        ));

        mvc.perform(get("/admin/partners/7"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(7))
            .andExpect(jsonPath("$.name").value("Parceiro"))
            .andExpect(jsonPath("$.representativeName").value("Representante"))
            .andExpect(jsonPath("$.representativeUserId").value(11))
            .andExpect(jsonPath("$.representative.email").value("rep@example.com"))
            .andExpect(jsonPath("$.representative.discordUsername").value("discord-fox"))
            .andExpect(jsonPath("$.status").value("active"))
            .andExpect(jsonPath("$.category").value("community"))
            .andExpect(jsonPath("$.description").value("Descricao"))
            .andExpect(jsonPath("$.imageUrl").value("https://cdn.example/image.png"))
            .andExpect(jsonPath("$.websiteUrl").value("https://example.com"))
            .andExpect(jsonPath("$.contactName").value("Contato"))
            .andExpect(jsonPath("$.contactEmail").value("contato@example.com"))
            .andExpect(jsonPath("$.linkedResource.type").value("event"))
            .andExpect(jsonPath("$.linkedResource.id").value(20))
            .andExpect(jsonPath("$.links[0].type").value("instagram"))
            .andExpect(jsonPath("$.links[0].url").value("https://instagram.com/example"))
            .andExpect(jsonPath("$.clicks").value(123))
            .andExpect(jsonPath("$.invites").value(45))
            .andExpect(jsonPath("$.createdAt").value("2026-01-02T03:04"))
            .andExpect(jsonPath("$.secret").doesNotExist())
            .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void getMissingPartnerReturnsNotFound() throws Exception {
        when(service.get(99)).thenThrow(
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Parceria nao encontrada")
        );

        mvc.perform(get("/admin/partners/99"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("Parceria nao encontrada"));
    }

    @Test
    void linkableResourcesReturnNormalizedCandidateContract() throws Exception {
        when(service.listLinkableResources("artist", "fox", 20)).thenReturn(List.of(
            new PartnerLinkCandidate("user", 12, null, "Fox", "fox@example.com", null,
                new PartnerPrefill("Fox", null, null, null, "Fox", "fox@example.com", List.of()))
        ));

        mvc.perform(get("/admin/partners/linkable-resources")
                .param("category", "artist")
                .param("search", "fox")
                .param("limit", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].resourceType").value("user"))
            .andExpect(jsonPath("$[0].id").value(12))
            .andExpect(jsonPath("$[0].prefill.contactEmail").value("fox@example.com"))
            .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void deleteExistingPartnerReturnsNoContent() throws Exception {
        mvc.perform(delete("/admin/partners/7"))
            .andExpect(status().isNoContent());

        verify(service).delete(7);
    }

    @Test
    void uploadPartnerImageUsesMultipartFileContract() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png", new byte[] {1, 2, 3});
        when(service.uploadImage(eq(7), any())).thenReturn(new PartnerImageResponse(7, "https://cdn.example/partners/7/image/logo.webp"));

        mvc.perform(multipart("/admin/partners/7/image").file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.partnerId").value(7))
            .andExpect(jsonPath("$.imageUrl").value("https://cdn.example/partners/7/image/logo.webp"));

        verify(service).uploadImage(eq(7), any());
    }
}
