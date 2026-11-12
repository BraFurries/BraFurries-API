package com.Brafurries.API.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.commerce.CommerceUnsignedPreparationDeletionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class AdminUnsignedPreparationDeletionControllerTest {
    private static final String KEY="commerce/contracts/41/12345678-1234-1234-1234-123456789abc.pdf";
    private final AdminStorageService storage=mock(AdminStorageService.class);
    private final CommerceUnsignedPreparationDeletionService deletion=
        mock(CommerceUnsignedPreparationDeletionService.class);
    private final Authentication auth=mock(Authentication.class);
    private final AdminUnsignedPreparationDeletionController controller=
        new AdminUnsignedPreparationDeletionController(storage,deletion);

    private AdminStorageService.ObjectView inspected(String source,String status,String etag) {
        return new AdminStorageService.ObjectView(KEY,123L,null,"application/pdf",etag,
            AdminStorageService.Category.CONTRACT_DOCUMENT,
            AdminStorageService.ReferenceStatus.LINKED,
            "Minuta",false,List.of(
                new AdminStorageService.ReferenceView(source,5L,41L,status)));
    }

    private AdminUnsignedPreparationDeletionController.DeleteRequest request(
        AdminStorageService.Scope bucket,String etag) {
        return new AdminUnsignedPreparationDeletionController.DeleteRequest(
            bucket,KEY,etag,41L,5L);
    }

    @Test void onlyFreshUniqueUnsignedPreparationCanBeDeleted() {
        when(storage.inspect(auth,AdminStorageService.Scope.PRIVATE,KEY))
            .thenReturn(inspected("PREPARATION","CANCELLED","etag"));
        controller.delete(auth,request(AdminStorageService.Scope.PRIVATE,"etag"));
        verify(deletion).delete(auth,41L,5L,KEY);
    }

    @Test void publicObjectsNeverReachInspectionOrDeletion() {
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->controller.delete(auth,request(AdminStorageService.Scope.PUBLIC,"etag")))
                .getStatusCode());
        verifyNoInteractions(storage,deletion);
    }

    @Test void signedOrOtherReferencesCannotUsePreparationDeletion() {
        when(storage.inspect(auth,AdminStorageService.Scope.PRIVATE,KEY))
            .thenReturn(inspected("PREPARATION","SIGNED","etag"),
                inspected("CONTRACT","ACTIVE","etag"));
        for(int i=0;i<2;i++) {
            assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
                ()->controller.delete(auth,request(AdminStorageService.Scope.PRIVATE,"etag")))
                    .getStatusCode());
        }
        verifyNoInteractions(deletion);
    }

    @Test void staleEtagCannotDeleteChangedObject() {
        when(storage.inspect(auth,AdminStorageService.Scope.PRIVATE,KEY))
            .thenReturn(inspected("PREPARATION","PENDING","new-etag"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->controller.delete(auth,request(AdminStorageService.Scope.PRIVATE,"old-etag")))
                .getStatusCode());
        verifyNoInteractions(deletion);
    }
}
