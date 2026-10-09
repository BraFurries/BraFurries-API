package com.Brafurries.API.entity.community;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "community_network_sync_runs")
public class CommunityNetworkSyncRun {

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

    @Column(name = "`trigger`", nullable = false, length = 32)
    private String trigger;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "observed_members")
    private Integer observedMembers;

    @Column(name = "updated_members")
    private Integer updatedMembers;

    @Column(name = "error_code", length = 64)
    private String errorCode;
}
