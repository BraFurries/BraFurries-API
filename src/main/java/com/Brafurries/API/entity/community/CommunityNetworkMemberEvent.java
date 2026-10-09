package com.Brafurries.API.entity.community;

import com.Brafurries.API.entity.user.User;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "community_network_member_events")
public class CommunityNetworkMemberEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "community_id", nullable = false)
    private Community community;

    @Column(name = "network_type", nullable = false, length = 32)
    private String networkType;

    @Column(name = "external_network_id", nullable = false)
    private Long externalNetworkId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "observed_at", nullable = false, insertable = false)
    private LocalDateTime observedAt;

    @Column(nullable = false, length = 32)
    private String source;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sync_run_id")
    private CommunityNetworkSyncRun syncRun;
}
