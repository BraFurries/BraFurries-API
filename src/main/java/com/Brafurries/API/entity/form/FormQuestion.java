package com.Brafurries.API.entity.form;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "form_questions")
public class FormQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flow_id", nullable = false)
    private FormFlow flow;

    @Column(name = "question_text", nullable = false, columnDefinition = "text")
    private String questionText;

    @Column(name = "placeholder_text", length = 100)
    private String placeholderText;

    @Column(name = "position", nullable = false)
    private Integer position;

    @Column(name = "required", nullable = false)
    private Boolean required;
}
