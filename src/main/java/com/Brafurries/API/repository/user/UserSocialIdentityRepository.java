package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserSocialIdentity;
import com.Brafurries.API.entity.user.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserSocialIdentityRepository extends JpaRepository<UserSocialIdentity, Long> {
    @EntityGraph(attributePaths = "user")
    Optional<UserSocialIdentity> findByProviderAndProviderSubject(String provider, String providerSubject);

    /**
     * Current locking read, not a cached JPA identity snapshot. Called only
     * after locking the owning User during OAuth login issuance.
     */
    @Query(value = """
        SELECT login_enabled FROM user_social_identities
         WHERE provider = :provider AND provider_subject = :subject
         FOR UPDATE
        """, nativeQuery = true)
    Optional<Boolean> currentLoginEnabledForUpdate(
        @Param("provider") String provider, @Param("subject") String subject);

    List<UserSocialIdentity> findByUserId(Integer userId);
    List<UserSocialIdentity> findByUserIdAndProvider(Integer userId, String provider);
    boolean existsByUser(User user);
}
