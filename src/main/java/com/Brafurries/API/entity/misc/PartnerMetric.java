package com.Brafurries.API.entity.misc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "partner_metrics")
public class PartnerMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "partner_id", nullable = false)
    private Partner partner;

    @Column(name = "metric_date", nullable = false)
    private LocalDate metricDate;

    @Column(nullable = false)
    private Long clicks = 0L;

    @Column(nullable = false)
    private Long invites = 0L;

    @Column(nullable = false)
    private Long conversions = 0L;
}
