package com.app.CallNotifier.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api")
public class CallController {

    private static final Logger log = LoggerFactory.getLogger(CallController.class);
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm:ss a").withZone(ZoneId.systemDefault());

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromAddress;

    public CallController(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    public record CallEvent(String number, long timestamp, String notifyEmail) {}

    @PostMapping("/calls")
    public ResponseEntity<String> receiveCall(@RequestBody CallEvent event) {
        log.info("Call event: number={}, notify={}", event.number(), event.notifyEmail());
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromAddress);
            msg.setTo(event.notifyEmail());
            msg.setSubject("Call from " + event.number());
            msg.setText("You got a call from " + event.number()
                    + "\nTime: " + FORMAT.format(Instant.ofEpochMilli(event.timestamp())));
            mailSender.send(msg);
            log.info("Email sent to {}", event.notifyEmail());
            return ResponseEntity.ok("sent");
        } catch (Exception e) {
            log.error("Email failed", e);
            // The reason comes back in the response, so curl and the app can show it
            return ResponseEntity.status(500).body("email failed: " + e.getMessage());
        }
    }
}