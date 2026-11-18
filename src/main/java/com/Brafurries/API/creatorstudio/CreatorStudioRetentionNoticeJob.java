package com.Brafurries.API.creatorstudio;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailSendException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Opt-in reminder job. During the pilot, notices-enabled=false.
 *
 * Each notice is claimed in a committed transaction before invoking SMTP.
 * A crashed/ambiguous send remains SENDING or UNKNOWN, never retried blindly.
 * SMTP acceptance is not proof of delivery; deletion remains independently gated.
 */
@Component
@ConditionalOnProperty(prefix="app.creator-studio.retention",
    name="notices-enabled",havingValue="true")
public class CreatorStudioRetentionNoticeJob {
    private static final Logger log =
        LoggerFactory.getLogger(CreatorStudioRetentionNoticeJob.class);
    private final CreatorStudioRetentionNoticeLedger ledger;
    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String frontendUrl;

    // Zoho CPaaS supports client_reference via X-TM-CLIENT-REF on SMTP.
    // Disabled by default; generic Zoho Mail SMTP acceptance is not evidence.
    @Value("${app.creator-studio.retention.cpaas-smtp:false}")
    private boolean cpaasSmtp;

    public CreatorStudioRetentionNoticeJob(CreatorStudioRetentionNoticeLedger ledger,
            JavaMailSender mailSender,
            @Value("${app.creator-studio.retention.sender-address:${spring.mail.username:}}") String fromAddress,
            @Value("${app.frontend-url:https://brafurries.com.br}") String frontendUrl) {
        this.ledger=ledger;
        this.mailSender=mailSender;
        this.fromAddress=fromAddress;
        this.frontendUrl=frontendUrl;
    }

    @Scheduled(cron="0 25 3 * * *",zone="UTC")
    public void queueAndSendNotices() {
        if (fromAddress==null || fromAddress.isBlank() || !fromAddress.contains("@") ||
            fromAddress.indexOf('\n')>=0 || fromAddress.indexOf('\r')>=0) {
            log.warn("Creator Studio reminders skipped: SMTP sender not configured");
            return;
        }
        // A verified-email requirement is enforced in the queue/claim SQL.
        ledger.enqueue("D30");
        ledger.enqueue("D7");
        int sent=0;
        int unknown=0;
        for (long id : ledger.pendingIds()) {
            var claimed=ledger.claim(id);
            if (claimed==null) continue; // closed, reopened, held or another worker
            try {
                if (claimed.email()==null || claimed.email().isBlank() ||
                    claimed.email().contains("\n") || claimed.email().contains("\r"))
                    throw new IllegalArgumentException("Unsafe mail address");
                String subject="BraFurries Creators: aviso sobre retenção dos seus dados";
                String window="D30".equals(claimed.kind())?"30":"7";
                String messageBody="Olá!\n\n"
                    +"Uma commission concluída no Creator Studio está a até "
                    +window+" dias do prazo de retenção de 12 meses.\n"
                    +"Entre na sua conta para conferir os detalhes e exportar "
                    +"seus dados antes do fim desse prazo:\n"
                    +frontendUrl+"/painel/creators\n\n"
                    +"Se o prazo ainda não estiver habilitado durante o piloto, "
                    +"este aviso é informativo.\n"
                    +"Equipe BraFurries";
                if (cpaasSmtp) {
                    // Explicit correlation from the signed CPaaS callback to
                    // the exact ledger record; no artist/client PII in header.
                    sendTrackableCpaasMail(claimed.id(), claimed.email(), subject, messageBody);
                } else {
                    SimpleMailMessage message=new SimpleMailMessage();
                    message.setFrom(fromAddress);
                    message.setTo(claimed.email());
                    message.setSubject(subject);
                    message.setText(messageBody);
                    mailSender.send(message);
                }
                ledger.markSmtpAccepted(id);
                sent++;
            } catch (RuntimeException exception) {
                // The SMTP server may have accepted the message even if the
                // client failed to see the acknowledgment. Never auto-retry.
                ledger.markDeliveryUnknown(id);
                unknown++;
            }
        }
        if (sent>0 || unknown>0)
            log.info("Creator retention reminders: smtpAccepted={},unknown={}",sent,unknown);
    }

    private void sendTrackableCpaasMail(long noticeId, String recipient,
                                       String subject, String text) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            message.setFrom(fromAddress);
            message.setRecipients(Message.RecipientType.TO, recipient);
            message.setSubject(subject, "UTF-8");
            message.setText(text, "UTF-8");
            message.setHeader("X-TM-CLIENT-REF", "cs-ret-" + noticeId);
            mailSender.send(message);
        } catch (MessagingException error) {
            throw new MailSendException("Unable to prepare tracked retention mail");
        }
    }
}
