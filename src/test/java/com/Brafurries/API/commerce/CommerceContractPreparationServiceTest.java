package com.Brafurries.API.commerce;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CommerceContractPreparationServiceTest {
    @Mock PlatformAuthorizationService authorization;
    @Mock CommercePrivateDocumentStorage storage;
    @Mock CommerceContractPdfService pdf;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    @Mock MultipartFile file;
    private CommerceContractPreparationService service;

    @BeforeEach void init() {
        service=new CommerceContractPreparationService(authorization,storage,pdf,jdbc);
        User actor=new User();
        actor.setId(11);
        lenient().when(authorization.require(eq(auth),eq(PlatformPermission.CONTRACTS_MANAGE)))
            .thenReturn(new PlatformAuthorizationService.Actor(
                actor,new PlatformAuthorizationService.Access(true,true,java.util.Set.of())));
    }

    private CommerceContractPreparationDtos.PrepareContract input() {
        return new CommerceContractPreparationDtos.PrepareContract(
            2L,LocalDate.of(2026,11,12),null,BigDecimal.ZERO,
            new CommerceContractPreparationDtos.ContractParty(
                "Contratante", "123.456.789-00", "Rua A",null),
            new CommerceContractPreparationDtos.ContractParty(
                "Artista","987.654.321-00","Rua B",null)
        );
    }

    private void artwork() {
        when(jdbc.queryForList(contains("FROM commerce_artworks"),eq(41L)))
            .thenReturn(List.of(Map.of(
                "id",41L,"title","Arte 41","status","DRAFT","artist_user_id",37)));
    }

    @Test void neverLoadsPrivateLegalPartiesWithoutContractPermission() {
        when(authorization.require(auth,PlatformPermission.CONTRACTS_MANAGE))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,()->service.list(auth,41L)).getStatusCode());
        verifyNoInteractions(jdbc,storage,pdf);
    }

    @Test void listExcludesIdentityDocumentsAndPrivateStorageKeys() {
        artwork();
        service.list(auth,41L);
        verify(jdbc).queryForList(argThat(sql ->
            sql.contains("commerce_contract_preparations")
            && !sql.contains("generated_storage_key")
            && !sql.contains("contractor_document")
            && !sql.contains("artist_document")
            && !sql.contains("SELECT *")),eq(41L));
    }

    @Test void refusesActiveArtworkBeforeRenderingOrStoringAnotherDraft() {
        artwork();
        when(jdbc.queryForList(contains("FROM commerce_artwork_contracts"),eq(41L)))
            .thenReturn(List.of(Map.of("id",5L)));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.prepare(auth,41L,input())).getStatusCode());
        verifyNoInteractions(pdf,storage);
    }

    @Test void refusesLegacyTemplateWithoutFullTextBeforeStorage() {
        artwork();
        when(jdbc.queryForList(contains("FROM commerce_contract_preparations"),eq(41L)))
            .thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM commerce_contract_templates"),eq(2L)))
            .thenReturn(List.of(Map.of("name","Antigo","modality","ROYALTY",
                "royalty_method","NONE")));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.prepare(auth,41L,input())).getStatusCode());
        verifyNoInteractions(pdf,storage);
    }

    @Test void rejectsSignedUploadIfArtworkOwnerNoLongerMatchesPreparedDocument() {
        artwork();
        when(storage.verifiedPdf(file)).thenReturn(new byte[]{1,2,3});
        when(jdbc.queryForList(contains("FROM commerce_contract_preparations"),eq(5L),eq(41L)))
            .thenReturn(List.of(Map.of("status","PENDING","artist_user_id",50)));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.uploadSigned(auth,41L,5L,true,file)).getStatusCode());
        verify(storage,never()).upload(anyLong(),any());
    }

    @Test void doesNotAcceptUnreviewedUploadedPdfAsAnAuthenticatedSignature() {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                ()->service.uploadSigned(auth,41L,5L,false,file)).getStatusCode());
        verifyNoInteractions(jdbc,storage);
    }

    @Test void signedPreparationIsNotDeletedOrCancelled() {
        artwork();
        when(jdbc.queryForList(contains("FROM commerce_contract_preparations"),eq(5L),eq(41L)))
            .thenReturn(List.of(Map.of("status","SIGNED")));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                ()->service.cancel(auth,41L,5L)).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
