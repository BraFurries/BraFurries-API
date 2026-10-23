package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.user.recruitment.RecruitmentExamNoticeRepository.Notice;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/communities/{communityId}/recruitment/applications/{applicationId}/exam-notices")
class CandidateExamNoticeController {
    private final RecruitmentExamNoticeService service;
    CandidateExamNoticeController(RecruitmentExamNoticeService service) { this.service=service; }

    @GetMapping
    public ResponseEntity<List<Notice>> list(Authentication auth,
        @PathVariable Integer communityId,@PathVariable Long applicationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.candidate(auth,communityId,applicationId));
    }

    @PostMapping("/{noticeId}/read")
    public ResponseEntity<Void> read(Authentication auth,
        @PathVariable Integer communityId,@PathVariable Long applicationId,
        @PathVariable Long noticeId) {
        service.candidateRead(auth,communityId,applicationId,noticeId);
        return ResponseEntity.noContent().build();
    }
}

@RestController
@RequestMapping("/user/me/communities/{communityId}/recruitment/processes/{processId}/exam-notices")
class StaffExamNoticeController {
    private final RecruitmentExamNoticeService service;
    StaffExamNoticeController(RecruitmentExamNoticeService service) { this.service=service; }

    @GetMapping
    public ResponseEntity<List<Notice>> list(Authentication auth,
        @PathVariable Integer communityId,@PathVariable Long processId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.staff(auth,communityId,processId));
    }

    @PostMapping("/{noticeId}/read")
    public ResponseEntity<Void> read(Authentication auth,
        @PathVariable Integer communityId,@PathVariable Long processId,
        @PathVariable Long noticeId) {
        service.staffRead(auth,communityId,processId,noticeId);
        return ResponseEntity.noContent().build();
    }
}
