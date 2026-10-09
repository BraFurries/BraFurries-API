package com.Brafurries.API.entity.form;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "form_submissions")
public class FormSubmission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flow_id", nullable = false)
    private FormFlow flow;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "guild_id", nullable = false)
    private Long guildId;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "submission", fetch = FetchType.LAZY)
    private List<FormDecision> decisions = new ArrayList<>();
}
