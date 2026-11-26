package com.Brafurries.API.commerce;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CommerceVariantServiceTest {
    @Mock PlatformAuthorizationService authorization;
    @Mock CommerceFinanceSupport support;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    CommerceVariantService service;
    @BeforeEach void before() {
        service=new CommerceVariantService(authorization,support,jdbc);
    }

    void grant() {
        User user=new User();user.setId(7);
        when(authorization.require(auth,PlatformPermission.PRODUCTS_MANAGE)).thenReturn(
            new PlatformAuthorizationService.Actor(user,new PlatformAuthorizationService.Access(false,true,Set.of(PlatformPermission.PRODUCTS_MANAGE))));
        when(jdbc.queryForList("SELECT id FROM commerce_products WHERE id=? AND active=1 FOR UPDATE",Long.class,10L))
            .thenReturn(List.of(10L));
    }

    @Test void deniedAccessDoesNotTouchCatalog() {
        when(authorization.require(auth,PlatformPermission.PRODUCTS_MANAGE))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.create(auth,10,new CommerceVariantService.Create(List.of(new CommerceVariantService.Attribute("Tamanho","G")),null,null))).getStatusCode());
        assertThrows(ResponseStatusException.class,()->service.list(auth,10));
        verifyNoInteractions(jdbc,support);
    }

    @Test void simpleProductAndCustomItemDoNotInventVariant() {
        assertNull(CommerceVariantService.selection(jdbc,10L,null).id());
        assertNull(CommerceVariantService.selection(jdbc,null,null).id());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->CommerceVariantService.selection(jdbc,null,1L)).getStatusCode());
    }

    @Test void productCannotOmitVariantWhenAllCombinationsAreInactive() {
        when(jdbc.queryForList("SELECT id FROM commerce_product_variants WHERE product_id=? LIMIT 1 FOR UPDATE",Long.class,10L))
            .thenReturn(List.of(99L));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->CommerceVariantService.selection(jdbc,10L,null)).getStatusCode());
    }

    @Test void onlyActiveVariantOfSameProductIsAccepted() {
        doReturn(List.of(Map.of("label","Tamanho: G"))).when(jdbc).queryForList(
            contains("product_id=? AND id=? AND active=1"),eq(10L),eq(99L));
        doReturn(List.of()).when(jdbc).queryForList(
            contains("product_id=? AND id=? AND active=1"),eq(11L),eq(99L));
        assertEquals(new CommerceVariantService.Selection(99L,"Tamanho: G"),CommerceVariantService.selection(jdbc,10L,99L));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->CommerceVariantService.selection(jdbc,11L,99L)).getStatusCode());
    }

    @Test void existingCombinationUsesCanonicalAttributeAndValueIds() {
        grant();
        doReturn(List.of(Map.of("id",21L,"name","Tamanho"))).when(jdbc).queryForList(
            eq("SELECT id,name FROM commerce_product_attributes WHERE product_id=? ORDER BY id FOR UPDATE"),eq(10L));
        doReturn(List.of(Map.of("id",21L,"name","Tamanho"))).when(jdbc).queryForList(
            contains("commerce_product_attributes WHERE product_id=? AND name=?"),eq(10L),eq("tamanho"));
        doReturn(List.of(Map.of("id",31L,"label","G"))).when(jdbc).queryForList(
            contains("commerce_product_attribute_values"),eq(10L),eq(21L),eq("g"));
        String hash=CommerceFinanceSupport.fingerprint(List.of("21","31"));
        doReturn(List.of(99L)).when(jdbc).queryForList(contains("combination_sha256=?"),eq(Long.class),eq(10L),eq(hash));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.create(auth,10,new CommerceVariantService.Create(List.of(new CommerceVariantService.Attribute("tamanho","g")),null,null))).getStatusCode());
        verifyNoInteractions(support);
    }

    @Test void batchRequiresCatalogPermissionBeforeSql() {
        when(authorization.require(auth,PlatformPermission.PRODUCTS_MANAGE))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.createBatch(auth,10,new CommerceVariantService.BatchCreate(List.of(
                new CommerceVariantService.Create(List.of(new CommerceVariantService.Attribute("Tamanho","G")),null,null))))).getStatusCode());
        verifyNoInteractions(jdbc,support);
    }

    @Test void batchRejectsEmptyOrOversizedRequestsBeforeSql() {
        User user=new User();user.setId(7);
        when(authorization.require(auth,PlatformPermission.PRODUCTS_MANAGE)).thenReturn(
            new PlatformAuthorizationService.Actor(user,new PlatformAuthorizationService.Access(false,true,Set.of(PlatformPermission.PRODUCTS_MANAGE))));
        var create=new CommerceVariantService.Create(List.of(new CommerceVariantService.Attribute("Tamanho","G")),null,null);
        assertThrows(ResponseStatusException.class,()->service.createBatch(auth,10,new CommerceVariantService.BatchCreate(List.of())));
        assertThrows(ResponseStatusException.class,()->service.createBatch(auth,10,new CommerceVariantService.BatchCreate(java.util.Collections.nCopies(101,create))));
        verifyNoInteractions(jdbc,support);
    }

    @Test void batchCreatesOnlySelectedCombinationsUnderOneProductLock() {
        grant();
        doReturn(List.of(Map.of("id",21L,"name","Tamanho"))).when(jdbc).queryForList(
            eq("SELECT id,name FROM commerce_product_attributes WHERE product_id=? ORDER BY id FOR UPDATE"),eq(10L));
        doReturn(List.of(Map.of("id",21L,"name","Tamanho"))).when(jdbc).queryForList(
            contains("commerce_product_attributes WHERE product_id=? AND name=?"),eq(10L),eq("Tamanho"));
        doReturn(List.of(Map.of("id",31L,"label","G"))).when(jdbc).queryForList(
            contains("commerce_product_attribute_values"),eq(10L),eq(21L),eq("G"));
        doReturn(List.of(Map.of("id",32L,"label","M"))).when(jdbc).queryForList(
            contains("commerce_product_attribute_values"),eq(10L),eq(21L),eq("M"));
        doReturn(List.of()).when(jdbc).queryForList(contains("combination_sha256=?"),eq(Long.class),eq(10L),anyString());
        doReturn(101L).when(support).insert(contains("INSERT INTO commerce_product_variants"),eq(10L),anyString(),eq("Tamanho: G"),isNull(),isNull(),eq(7));
        doReturn(102L).when(support).insert(contains("INSERT INTO commerce_product_variants"),eq(10L),anyString(),eq("Tamanho: M"),isNull(),isNull(),eq(7));
        var result=service.createBatch(auth,10,new CommerceVariantService.BatchCreate(List.of(
            new CommerceVariantService.Create(List.of(new CommerceVariantService.Attribute("Tamanho","G")),null,null),
            new CommerceVariantService.Create(List.of(new CommerceVariantService.Attribute("Tamanho","M")),null,null))));
        assertEquals(List.of(101L,102L),result.ids());
        verify(jdbc,times(1)).queryForList("SELECT id FROM commerce_products WHERE id=? AND active=1 FOR UPDATE",Long.class,10L);
        verify(jdbc).update(contains("INSERT INTO commerce_product_variant_values"),eq(10L),eq(101L),eq(21L),eq(31L));
        verify(jdbc).update(contains("INSERT INTO commerce_product_variant_values"),eq(10L),eq(102L),eq(21L),eq(32L));
    }

    @Test void financePermissionIsRequiredBeforeReadingStatistics() {
        when(authorization.require(auth,PlatformPermission.FINANCE_VIEW)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,()->service.statistics(auth,10,null,null)).getStatusCode());
        verifyNoInteractions(jdbc,support);
    }
}
