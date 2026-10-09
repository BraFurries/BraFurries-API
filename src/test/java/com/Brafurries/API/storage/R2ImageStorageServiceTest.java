package com.Brafurries.API.storage;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class R2ImageStorageServiceTest {

    @Test
    void uploadRejectsEmptyInvalidAndTooLargeFiles() {
        R2ImageStorageService service = unconfiguredService();
        assertBadRequest(() -> service.upload("partners/1/image/a.webp", new MockMultipartFile("file", new byte[0]), "imagem da parceria"));
        assertBadRequest(() -> service.upload("partners/1/image/a.webp", new MockMultipartFile("file", "a.gif", "image/gif", new byte[] {1}), "imagem da parceria"));
        assertBadRequest(() -> service.upload("partners/1/image/a.webp", new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[(5 * 1024 * 1024) + 1]), "imagem da parceria"));
        assertBadRequest(() -> service.upload("partners/1/image/a.webp", new MockMultipartFile("file", "fake.png", "image/png", new byte[] {1, 2, 3}), "imagem da parceria"));
    }

    @Test
    void convertsJpegPngAndWebpAndRejectsNonDecodableImage() throws Exception {
        R2ImageStorageService service = unconfiguredService();
        for (String format : new String[] {"jpg", "png", "webp"}) {
            byte[] converted = service.convertToWebp(new MockMultipartFile("file", "image." + format, "image/" + ("jpg".equals(format) ? "jpeg" : format), imageBytes(format)), "imagem da parceria");
            assertTrue(converted.length > 4);
            assertEquals('R', converted[0]);
        }
        assertBadRequest(() -> service.convertToWebp(new MockMultipartFile("file", "fake.png", "image/png", new byte[] {1, 2, 3}), "imagem da parceria"));
    }

    @Test
    void resolvesOnlyPublicUrlsUnderTheRequiredPartnerPrefix() {
        R2ImageStorageService service = new R2ImageStorageService("", "", "https://cdn.example/", "", "");
        String prefix = "partners/10/image/";

        assertEquals("partners/10/image/a.webp", service.resolveManagedKey("https://cdn.example/partners/10/image/a.webp", prefix));
        assertEquals(null, service.resolveManagedKey("https://cdn.example/partners/20/image/a.webp", prefix));
        assertEquals(null, service.resolveManagedKey("https://cdn.example/users/10/profile/a.webp", prefix));
        assertEquals(null, service.resolveManagedKey("https://cdn.example/events/10/logo/a.webp", prefix));
        assertEquals(null, service.resolveManagedKey("https://outside.example/partners/10/image/a.webp", prefix));
        assertEquals(null, service.resolveManagedKey("https://cdn.example/arbitrary/a.webp", prefix));
    }

    private void assertBadRequest(org.junit.jupiter.api.function.Executable executable) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, executable);
        assertEquals(400, error.getStatusCode().value());
    }

    private R2ImageStorageService unconfiguredService() { return new R2ImageStorageService("", "", "", "", ""); }

    private byte[] imageBytes(String format) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.RED.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }
}
