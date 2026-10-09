package com.Brafurries.API.user;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.ProfileImageStorageService.StoredProfileImage;
import com.Brafurries.API.user.dto.UserProfileDtos.UserProfileImageResponse;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserProfileImageService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileImageService.class);

    private final UserRepository userRepository;
    private final ProfileImageStorageService profileImageStorageService;

    public UserProfileImageService(
        UserRepository userRepository,
        ProfileImageStorageService profileImageStorageService
    ) {
        this.userRepository = userRepository;
        this.profileImageStorageService = profileImageStorageService;
    }

    @Transactional
    public UserProfileImageResponse uploadProfileImage(String email, MultipartFile file) {
        User user = findUser(email);
        String previousKey = user.getProfileImageKey();

        StoredProfileImage storedImage = profileImageStorageService.uploadProfileImage(user.getId(), file);

        user.setProfileImageKey(storedImage.key());
        user.setProfileImageUrl(storedImage.url());
        user.setProfileImageContentType(storedImage.contentType());
        user.setProfileImageUpdatedAt(LocalDateTime.now());
        User saved = userRepository.save(user);

        if (previousKey != null && !previousKey.isBlank() && !previousKey.equals(storedImage.key())) {
            try {
                profileImageStorageService.deleteProfileImage(previousKey);
            } catch (RuntimeException ex) {
                log.warn("Falha ao remover foto de perfil anterior do usuário {}", user.getId(), ex);
            }
        }

        return new UserProfileImageResponse(saved.getId(), saved.getProfileImageUrl());
    }

    @Transactional
    public UserProfileImageResponse deleteProfileImage(String email) {
        User user = findUser(email);
        String previousKey = user.getProfileImageKey();

        user.setProfileImageKey(null);
        user.setProfileImageUrl(null);
        user.setProfileImageContentType(null);
        user.setProfileImageUpdatedAt(null);
        User saved = userRepository.save(user);

        profileImageStorageService.deleteProfileImage(previousKey);

        return new UserProfileImageResponse(saved.getId(), saved.getProfileImageUrl());
    }

    private User findUser(String email) {
        return userRepository.findByEmail(email.trim().toLowerCase())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário autenticado não encontrado"));
    }
}
