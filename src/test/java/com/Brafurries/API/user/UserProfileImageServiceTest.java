package com.Brafurries.API.user;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.ProfileImageStorageService.StoredProfileImage;
import com.Brafurries.API.user.dto.UserProfileDtos.UserProfileImageResponse;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileImageServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProfileImageStorageService profileImageStorageService;

    @Test
    void uploadProfileImageStoresMetadataAndDeletesPreviousImage() {
        User user = user();
        user.setProfileImageKey("users/42/profile/old.jpg");

        MockMultipartFile file = new MockMultipartFile("file", "avatar.webp", "image/webp", new byte[] {1, 2, 3});
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(profileImageStorageService.uploadProfileImage(42, file))
            .thenReturn(new StoredProfileImage("users/42/profile/new.webp", "https://cdn.example.com/users/42/profile/new.webp", "image/webp"));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProfileImageService service = new UserProfileImageService(userRepository, profileImageStorageService);

        UserProfileImageResponse response = service.uploadProfileImage(" USER@EXAMPLE.COM ", file);

        assertEquals(42, response.userId());
        assertEquals("https://cdn.example.com/users/42/profile/new.webp", response.profileImageUrl());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();
        assertEquals("users/42/profile/new.webp", savedUser.getProfileImageKey());
        assertEquals("https://cdn.example.com/users/42/profile/new.webp", savedUser.getProfileImageUrl());
        assertEquals("image/webp", savedUser.getProfileImageContentType());
        assertNotNull(savedUser.getProfileImageUpdatedAt());
        verify(profileImageStorageService).deleteProfileImage("users/42/profile/old.jpg");
    }

    @Test
    void deleteProfileImageClearsMetadataAndDeletesStoredImage() {
        User user = user();
        user.setProfileImageKey("users/42/profile/avatar.png");
        user.setProfileImageUrl("https://cdn.example.com/users/42/profile/avatar.png");
        user.setProfileImageContentType("image/png");

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserProfileImageService service = new UserProfileImageService(userRepository, profileImageStorageService);

        UserProfileImageResponse response = service.deleteProfileImage("user@example.com");

        assertEquals(42, response.userId());
        assertNull(response.profileImageUrl());
        assertNull(user.getProfileImageKey());
        assertNull(user.getProfileImageUrl());
        assertNull(user.getProfileImageContentType());
        assertNull(user.getProfileImageUpdatedAt());
        verify(profileImageStorageService).deleteProfileImage("users/42/profile/avatar.png");
    }

    private User user() {
        User user = new User();
        user.setId(42);
        user.setEmail("user@example.com");
        user.setDisplayName("User");
        return user;
    }
}
