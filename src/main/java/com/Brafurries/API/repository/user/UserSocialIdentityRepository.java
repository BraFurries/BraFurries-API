package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserSocialIdentity;
import com.Brafurries.API.entity.user.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserSocialIdentityRepository extends JpaRepository<UserSocialIdentity, Long> {
    @EntityGraph(attributePaths = "user")
    Optional<UserSocialIdentity> findByProviderAndProviderSubject(String provider, String providerSubject);

    List<UserSocialIdentity> findByUserIdAndProvider(Integer userId, String provider);
    boolean existsByUser(User user);
}
