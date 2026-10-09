package com.Brafurries.API.repository.form;

import com.Brafurries.API.entity.form.FormQuestion;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FormQuestionRepository extends JpaRepository<FormQuestion, Integer> {
    List<FormQuestion> findByFlowIdOrderByPositionAsc(Integer flowId);
}
