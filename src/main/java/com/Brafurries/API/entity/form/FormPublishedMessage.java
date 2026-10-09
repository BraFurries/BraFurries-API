package com.Brafurries.API.entity.form;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "form_published_messages")
public class FormPublishedMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flow_id", nullable = false)
    private FormFlow flow;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "guild_id", nullable = false)
    private Long guildId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
