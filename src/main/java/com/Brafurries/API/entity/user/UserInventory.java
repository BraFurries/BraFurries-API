package com.Brafurries.API.entity.user;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityStore;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "user_inventory")
public class UserInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    private Community community;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_item_id", nullable = false)
    private CommunityStore storeItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private User ownerUser;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "used_in")
    private LocalDateTime usedIn;

    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    @Column(name = "service_metadata", columnDefinition = "longtext")
    private String serviceMetadata;
}
