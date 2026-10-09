package com.Brafurries.API.admin;

import static com.Brafurries.API.config.CacheConfig.ADMIN_ANNOUNCEMENTS_CONFIG;

import com.Brafurries.API.admin.dto.AdminDtos.AnnouncementConfig;
import com.Brafurries.API.admin.dto.AdminDtos.AnnouncementDestination;
import com.Brafurries.API.admin.dto.AdminDtos.AnnouncementResult;
import com.Brafurries.API.admin.dto.AdminDtos.CreateAnnouncementResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminAnnouncementsService {

    private final AtomicInteger idSequence = new AtomicInteger(1);

    @Value("${app.announcements.discord.enabled:false}")
    private boolean discordEnabled;

    @Value("${app.announcements.discord.target-name:Discord}")
    private String discordTargetName;

    @Value("${app.announcements.telegram.enabled:false}")
    private boolean telegramEnabled;

    @Value("${app.announcements.telegram.target-name:Telegram}")
    private String telegramTargetName;

    @Value("${app.announcements.max-image-size-mb:5}")
    private int maxImageSizeMb;

    @Value("${app.announcements.allowed-image-types:image/png,image/jpeg,image/webp,image/gif}")
    private List<String> allowedImageTypes;

    @Cacheable(cacheNames = ADMIN_ANNOUNCEMENTS_CONFIG, key = "'config'")
    public AnnouncementConfig getConfig() {
        return new AnnouncementConfig(
            List.of(
                new AnnouncementDestination("discord", "Discord", discordEnabled, discordTargetName),
                new AnnouncementDestination("telegram", "Telegram", telegramEnabled, telegramTargetName)
            ),
            maxImageSizeMb,
            allowedImageTypes
        );
    }

    public CreateAnnouncementResponse send(
        List<String> destinations,
        String title,
        String message,
        MultipartFile image
    ) {
        if (destinations == null || destinations.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe ao menos um destino");
        }
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Titulo obrigatorio");
        }
        if (message == null || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mensagem obrigatoria");
        }
        validateImage(image);

        List<AnnouncementResult> results = destinations.stream()
            .map(this::sendToDestination)
            .toList();
        long sent = results.stream().filter(result -> "sent".equals(result.status())).count();
        String status = sent == results.size() ? "sent" : sent == 0 ? "failed" : "partial";

        return new CreateAnnouncementResponse(idSequence.getAndIncrement(), status, results);
    }

    private AnnouncementResult sendToDestination(String destination) {
        String normalized = destination == null ? "" : destination.trim().toLowerCase();
        return switch (normalized) {
            case "discord" -> discordEnabled
                ? new AnnouncementResult("discord", "sent", null)
                : new AnnouncementResult("discord", "failed", "Destino Discord desabilitado");
            case "telegram" -> telegramEnabled
                ? new AnnouncementResult("telegram", "sent", null)
                : new AnnouncementResult("telegram", "failed", "Destino Telegram desabilitado");
            default -> new AnnouncementResult(normalized, "failed", "Destino invalido");
        };
    }

    private void validateImage(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            return;
        }
        long maxBytes = (long) maxImageSizeMb * 1024 * 1024;
        if (image.getSize() > maxBytes) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Imagem excede o tamanho maximo permitido");
        }
        String contentType = image.getContentType();
        if (contentType == null || allowedImageTypes.stream().noneMatch(contentType::equalsIgnoreCase)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de imagem nao permitido");
        }
    }
}
