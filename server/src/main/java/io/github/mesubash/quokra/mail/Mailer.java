package io.github.mesubash.quokra.mail;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

// Sends plain-text emails, only while MailCheck says email works. Returns whether it was sent.
@Slf4j
@Component
public class Mailer {

    private final ObjectProvider<JavaMailSender> sender;
    private final MailCheck check;
    private final String from;

    public Mailer(ObjectProvider<JavaMailSender> sender, MailCheck check, @Value("${quokra.mail.from:}") String from) {
        this.sender = sender;
        this.check = check;
        this.from = from;
    }

    public boolean send(List<String> to, String subject, String text) {
        if (to.isEmpty() || !check.ok()) {
            return false;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to.toArray(String[]::new));
        message.setSubject(subject);
        message.setText(text);
        try {
            sender.getObject().send(message);
            return true;
        } catch (RuntimeException e) {
            log.warn("Couldn't send \"{}\" to {} address(es)", subject, to.size(), e);
            return false;
        }
    }
}
