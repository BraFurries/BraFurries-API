package com.Brafurries.API.entity.community;

import com.Brafurries.API.entity.user.User;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "communities")
public class Community {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 100)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id")
    private User ownerUser;

    @OneToOne(mappedBy = "community", fetch = FetchType.LAZY)
    private CommunityDiscord discord;

    @OneToOne(mappedBy = "community", fetch = FetchType.LAZY)
    private CommunityTelegram telegram;

    @OneToMany(mappedBy = "community", fetch = FetchType.LAZY)
    private List<CommunityStore> storeItems = new ArrayList<>();
}
