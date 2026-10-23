package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.Brafurries.API.user.recruitment.RecruitmentExamNoticeRepository.Notice;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Notification visibility is checked at read time, not fixed when staff roles change. */
@Service
public class RecruitmentExamNoticeService {
    private final RecruitmentFeatureGate gate;
    private final CommunityAuthorizationService authz;
    private final RecruitmentStageDecisionRepository decisions;
    private final RecruitmentExamNoticeRepository notices;

    public RecruitmentExamNoticeService(RecruitmentFeatureGate gate,
        CommunityAuthorizationService authz,RecruitmentStageDecisionRepository decisions,
        RecruitmentExamNoticeRepository notices) {
        this.gate=gate;this.authz=authz;this.decisions=decisions;this.notices=notices;
    }

    @Transactional(readOnly=true)
    public List<Notice> candidate(Authentication auth,Integer communityId,Long applicationId) {
        int candidateId=candidateId(auth,communityId,applicationId);
        return notices.candidate(communityId,applicationId,candidateId);
    }

    @Transactional
    public void candidateRead(Authentication auth,Integer communityId,Long applicationId,
        Long noticeId) {
        int userId=candidateId(auth,communityId,applicationId);
        if(noticeId==null || noticeId<=0
           || !notices.candidateNoticeExists(communityId,applicationId,noticeId,userId))
            throw missing();
        notices.markRead(communityId,noticeId,userId);
    }

    @Transactional(readOnly=true)
    public List<Notice> staff(Authentication auth,Integer communityId,Long processId) {
        int userId=managerId(auth,communityId,processId);
        return notices.staff(communityId,processId,userId);
    }

    @Transactional
    public void staffRead(Authentication auth,Integer communityId,Long processId,
        Long noticeId) {
        int userId=managerId(auth,communityId,processId);
        if(noticeId==null || noticeId<=0
           || !notices.staffNoticeExists(communityId,processId,noticeId,userId))
            throw missing();
        notices.markRead(communityId,noticeId,userId);
    }

    private int candidateId(Authentication auth,Integer communityId,Long applicationId) {
        gate.requireEnabledFor(communityId);
        if(applicationId==null || applicationId<=0) throw missing();
        var access=authz.resolve(auth,communityId);
        if(!access.activeMember()) throw missing();
        Integer owner=decisions.candidateId(communityId,applicationId);
        if(owner==null || owner!=access.user().getId()) throw missing();
        return owner;
    }

    private int managerId(Authentication auth,Integer communityId,Long processId) {
        gate.requireEnabledFor(communityId);
        if(processId==null || processId<=0) throw missing();
        var access=authz.requireCapability(auth,communityId,
            CommunityCapability.RECRUITMENT_MANAGE_PROCESSES);
        if(!access.activeMember()) throw missing();
        return access.user().getId();
    }

    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Aviso indisponível");
    }
}
