package com.Brafurries.API.entity.form;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "form_decisions")
public class FormDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private FormSubmission submission;

    @Column(nullable = false, length = 20)
    private String decision;

    @Column(name = "decided_by", nullable = false)
    private Long decidedBy;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;
}
