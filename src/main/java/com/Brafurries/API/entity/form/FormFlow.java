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
@Table(name = "form_flows")
public class FormFlow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(name = "target_channel_id", nullable = false)
    private Long targetChannelId;

    @Column(name = "approved_target_channel_id")
    private Long approvedTargetChannelId;

    @Column(name = "rejected_target_channel_id")
    private Long rejectedTargetChannelId;

    @Column(name = "rejection_feedback_enabled", nullable = false)
    private Boolean rejectionFeedbackEnabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "flow", fetch = FetchType.LAZY)
    private List<FormQuestion> questions = new ArrayList<>();

    @OneToMany(mappedBy = "flow", fetch = FetchType.LAZY)
    private List<FormSubmission> submissions = new ArrayList<>();

    @OneToMany(mappedBy = "flow", fetch = FetchType.LAZY)
    private List<FormPublishedMessage> publishedMessages = new ArrayList<>();
}
