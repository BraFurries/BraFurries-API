package com.Brafurries.API.commerce;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class CommercePrivateDocumentStorageTest {
    private CommercePrivateDocumentStorage storage(String bucket,String publicBucket) {
        return new CommercePrivateDocumentStorage(
            "https://r2.example.invalid",bucket,publicBucket,"access","secret");
    }

    @Test
    void acceptsOnlySmallValidPdfFiles() throws java.io.IOException {
        var storage=storage("private-contracts","public-media");
        var valid=new MockMultipartFile("file","signed.pdf","application/pdf",
            "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals(valid.getBytes(),storage.verifiedPdf(valid));

        var renamedImage=new MockMultipartFile("file","signed.pdf","application/pdf",
            "not really a PDF".getBytes(StandardCharsets.UTF_8));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->storage.verifiedPdf(renamedImage)).getStatusCode());

        var wrongType=new MockMultipartFile("file","signed.pdf","application/octet-stream",
            valid.getBytes());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->storage.verifiedPdf(wrongType)).getStatusCode());

        var oversized=new MockMultipartFile("file","signed.pdf","application/pdf",
            new byte[(int)CommercePrivateDocumentStorage.MAX_BYTES+1]);
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->storage.verifiedPdf(oversized)).getStatusCode());
    }

    @Test
    void refusesToUploadIntoPublicR2Bucket() throws java.io.IOException {
        var storage=storage("public-media","public-media");
        var valid=new MockMultipartFile("file","signed.pdf","application/pdf",
            "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(StandardCharsets.US_ASCII));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ResponseStatusException.class,
            ()->storage.upload(14L,valid)).getStatusCode());
    }

    @Test
    void missingPrivateR2CredentialsFailClosedBeforeNetworkAccess() {
        var storage = new CommercePrivateDocumentStorage(
            "https://r2.example.invalid", "private-contracts",
            "public-media", "", ""
        );
        var valid = new MockMultipartFile(
            "file", "signed.pdf", "application/pdf",
            "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.US_ASCII)
        );
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
            assertThrows(ResponseStatusException.class,
                () -> storage.upload(14L, valid)).getStatusCode());
    }

    @Test
    void refusesObjectKeysOutsideTheArtworkPrefixBeforeReadingAnything() {
        var storage=storage("private-contracts","public-media");
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->storage.read(14L,"commerce/contracts/22/unsafe.pdf","abc")).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->storage.read(14L,"../../etc/passwd","abc")).getStatusCode());
    }
}
