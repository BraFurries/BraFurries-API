package com.Brafurries.API.commerce;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class CommerceArtworkFileStorageTest {
    private CommerceArtworkFileStorage storage(String privateBucket,String publicBucket) {
        return new CommerceArtworkFileStorage("https://r2.example.invalid",
            privateBucket,publicBucket,"access","secret");
    }

    @Test
    void supportsSourceAndExportFormatsButRejectsRenamedContent() {
        var storage=storage("private","public");
        byte[] psd=new byte[30];
        psd[0]='8';psd[1]='B';psd[2]='P';psd[3]='S';psd[5]=1;
        var file=new MockMultipartFile("file","design.psd","application/octet-stream",psd);
        var checked=storage.inspect(file);
        assertEquals("psd",checked.extension());
        assertEquals(30,checked.bytes().length);
        assertEquals(64,checked.sha256().length());

        var invalid=new MockMultipartFile("file","design.psd",
            "application/octet-stream","not a PSD".getBytes(StandardCharsets.UTF_8));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->storage.inspect(invalid)).getStatusCode());

        var pdf=new MockMultipartFile("file","print.ai","application/octet-stream",
            "%PDF-1.7\n%%EOF".getBytes(StandardCharsets.US_ASCII));
        assertEquals("ai",storage.inspect(pdf).extension());
    }

    @Test
    void rejectsUnsafeNamesFormatsAndOversizedPayloads() {
        var storage=storage("private","public");
        byte[] png=new byte[]{(byte)137,80,78,71,13,10,26,10,0};
        for(String name:new String[]{"../image.png","nested/file.png",
                "image.png\r\nx","executable.exe"}) {
            var f=new MockMultipartFile("file",name,"image/png",png);
            assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
                ()->storage.inspect(f)).getStatusCode());
        }
        var tooLarge=new MockMultipartFile("file","art.png","image/png",
            new byte[(int)CommerceArtworkFileStorage.MAX_BYTES+1]);
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->storage.inspect(tooLarge)).getStatusCode());
    }

    @Test
    void rejectsCrossArtworkStorageKeyAndPublicR2Bucket() {
        var storage=storage("private","public");
        assertFalse(storage.validKey(12L,"commerce/artworks/13/00000000-0000-4000-8000-000000000000.pdf"));
        assertFalse(storage.validKey(12L,"../art.pdf"));
        assertTrue(storage.validKey(12L,"commerce/artworks/12/00000000-0000-4000-8000-000000000000.psd"));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->storage.read(12L,"commerce/contracts/12/x.pdf","abcd")).getStatusCode());

        var same=storage("public","public");
        var png=new MockMultipartFile("file","art.png","image/png",
            new byte[]{(byte)137,80,78,71,13,10,26,10,0});
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ResponseStatusException.class,
            ()->same.upload(12L,png)).getStatusCode());
    }
}
