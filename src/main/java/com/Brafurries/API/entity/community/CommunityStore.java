package com.Brafurries.API.entity.community;

import com.Brafurries.API.entity.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "community_store")
public class CommunityStore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    private Community community;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id")
    private User ownerUser;

    @Column(name = "item_name", nullable = false, length = 50)
    private String itemName;

    @Column(name = "item_price", nullable = false)
    private Integer itemPrice;

    @Column(name = "item_image_url", length = 256)
    private String itemImageUrl;

    @Column(name = "quantity_available")
    private Integer quantityAvailable;

    @Column(name = "quantity_total")
    private Integer quantityTotal;

    @Column(name = "allow_multiple", nullable = false)
    private Boolean allowMultiple;

    @Column(name = "is_service", nullable = false)
    private Boolean isService;

    @Column(name = "duration")
    private Long duration;
}
