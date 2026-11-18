package com.Brafurries.API.creatorstudio;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.LocalDateTime;
import java.util.Properties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CreatorStudioRetentionNoticeJobTest {
    @Mock CreatorStudioRetentionNoticeLedger ledger;
    @Mock JavaMailSender sender;

    @Test void noSmtpSenderFailsClosedWithoutQueuingOrClaiming() {
        new CreatorStudioRetentionNoticeJob(ledger,sender,"",
            "https://brafurries.com.br").queueAndSendNotices();
        verifyNoInteractions(ledger,sender);
    }

    @Test void acceptedSmtpMessageIsLoggedOnlyOnceAndContainsNoCommissionDetails() {
        when(ledger.pendingIds()).thenReturn(List.of(41L));
        when(ledger.claim(41L)).thenReturn(new CreatorStudioRetentionNoticeLedger.ClaimedNotice(
            41L,7,51L,"D30","artist@example.invalid",
            LocalDateTime.of(2025,11,17,12,0)));
        new CreatorStudioRetentionNoticeJob(ledger,sender,
            "alerts@brafurries.com.br","https://brafurries.com.br").queueAndSendNotices();
        verify(ledger).enqueue("D30");
        verify(ledger).enqueue("D7");
        ArgumentCaptor<SimpleMailMessage> sent=ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(sent.capture());
        assertArrayEquals(new String[]{"artist@example.invalid"},sent.getValue().getTo());
        assertTrue(sent.getValue().getText().contains("/painel/creators"));
        assertTrue(sent.getValue().getText().contains("30 dias"));
        assertFalse(sent.getValue().getText().contains("commission #51"));
        verify(ledger).markSmtpAccepted(41L);
        verify(ledger,never()).markDeliveryUnknown(anyLong());
    }

    @Test void cpaasMailIncludesTraceableNoticeIdButNoArtistIdentityInHeader()
            throws Exception {
        when(ledger.pendingIds()).thenReturn(List.of(43L));
        when(ledger.claim(43L)).thenReturn(new CreatorStudioRetentionNoticeLedger.ClaimedNotice(
            43L,7,52L,"D7","artist@example.invalid",
            LocalDateTime.of(2025,11,17,12,0)));
        when(sender.createMimeMessage()).thenReturn(
            new MimeMessage(Session.getInstance(new Properties())));
        var job=new CreatorStudioRetentionNoticeJob(ledger,sender,
            "alerts@brafurries.com.br","https://brafurries.com.br");
        ReflectionTestUtils.setField(job,"cpaasSmtp",true);
        job.queueAndSendNotices();
        ArgumentCaptor<MimeMessage> sent=ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        assertEquals("cs-ret-43", sent.getValue().getHeader("X-TM-CLIENT-REF")[0]);
        assertTrue(sent.getValue().getSubject().contains("retenção"));
        verify(ledger).markSmtpAccepted(43L);
    }

    @Test void ambiguousSmtpResultNeverAutoRetriesOrClaimsDelivery() {
        when(ledger.pendingIds()).thenReturn(List.of(42L));
        when(ledger.claim(42L)).thenReturn(new CreatorStudioRetentionNoticeLedger.ClaimedNotice(
            42L,7,52L,"D7","artist@example.invalid",
            LocalDateTime.of(2025,11,17,12,0)));
        doThrow(new IllegalStateException("SMTP timeout")).when(sender)
            .send(any(SimpleMailMessage.class));
        new CreatorStudioRetentionNoticeJob(ledger,sender,
            "alerts@brafurries.com.br","https://brafurries.com.br").queueAndSendNotices();
        verify(ledger).markDeliveryUnknown(42L);
        verify(ledger,never()).markSmtpAccepted(anyLong());
        verify(sender,times(1)).send(any(SimpleMailMessage.class));
    }
}
