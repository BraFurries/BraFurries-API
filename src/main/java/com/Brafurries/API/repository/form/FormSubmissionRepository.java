package com.Brafurries.API.repository.form;

import com.Brafurries.API.entity.form.FormSubmission;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FormSubmissionRepository extends JpaRepository<FormSubmission, Integer> {
    List<FormSubmission> findByFlowIdAndStatus(Integer flowId, String status);
}
