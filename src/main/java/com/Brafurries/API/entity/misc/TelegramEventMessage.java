package com.Brafurries.API.entity.misc;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity(name = "MiscTelegramEventMessage")
@Table(name = "telegram_event_messages")
public class TelegramEventMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "channel_name", nullable = false, length = 32)
    private String channelName;

    @Column(name = "message_id", nullable = false)
    private Integer messageId;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "send_new_message", nullable = false)
    private Boolean sendNewMessage;
}
