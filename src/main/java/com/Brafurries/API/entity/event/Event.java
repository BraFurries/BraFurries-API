package com.Brafurries.API.entity.event;

import com.Brafurries.API.entity.misc.Locale;
import com.Brafurries.API.entity.user.User;
import org.hibernate.annotations.DynamicUpdate;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@DynamicUpdate
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_user_id", nullable = false)
    private User hostUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "locale_id", nullable = false)
    private Locale locale;

    @Column(name = "event_name", nullable = false, length = 64, unique = true)
    private String eventName;

    @Column(length = 512)
    private String description;

    @Column(nullable = false, length = 32)
    private String city;

    @Column(nullable = false, length = 256)
    private String address;

    @Column(name = "point_name", length = 64)
    private String pointName;

    @Column(nullable = false)
    private Double price;

    @Column(name = "max_price")
    private Double maxPrice;

    @Column(name = "price_confirmed", nullable = false)
    private Boolean priceConfirmed;

    @Column(name = "group_chat_link", length = 256)
    private String groupChatLink;

    @Column(length = 64)
    private String website;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "event_logo_url", length = 256)
    private String eventLogoUrl;

    @Column(name = "ticket_url", length = 256)
    private String ticketUrl;

    @Column(name = "out_of_tickets", nullable = false)
    private Boolean outOfTickets;

    @Column(name = "sales_ended", nullable = false)
    private Boolean salesEnded;

    @Column(nullable = false)
    private Boolean approved;

    @Column(name = "is_event", nullable = false)
    private Boolean isEvent;

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EventStaff> staffs = new ArrayList<>();
}
