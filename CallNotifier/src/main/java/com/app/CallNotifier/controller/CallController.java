package com.app.CallNotifier.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CallController {

    private static final Logger log = LoggerFactory.getLogger(CallController.class);
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm:ss a").withZone(ZoneId.systemDefault());

    // Set these as environment variables on Render
    @Value("${BREVO_API_KEY}")
    private String apiKey;

    @Value("${MAIL_SENDER}")
    private String sender;

    private final RestClient http;

    public CallController() {
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.http = RestClient.builder().requestFactory(factory).build();
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
            Map<String, Object> body = Map.of(
                    "sender", Map.of("name", "Call Notifier", "email", sender),
                    "to", List.of(Map.of("email", event.notifyEmail())),
                    "subject", "Call from " + event.number(),
                    "textContent", "You got a call from " + event.number()
                            + "\nTime: " + FORMAT.format(Instant.ofEpochMilli(event.timestamp()))
            );

            http.post()
                    .uri("https://api.brevo.com/v3/smtp/email")
                    .header("api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Email sent to {}", event.notifyEmail());
            return ResponseEntity.ok("sent");
        } catch (Exception e) {
            log.error("Email failed", e);
            return ResponseEntity.status(500).body("email failed: " + e.getMessage());
        }
    }
}