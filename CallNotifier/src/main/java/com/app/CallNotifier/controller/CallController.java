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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class CallController {

    private static final Logger log = LoggerFactory.getLogger(CallController.class);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,255}$");
    private static final int MAX_EMAILS_PER_MINUTE = 20;

    // Environment variables on Render. The app fails to start if any is missing.
    @Value("${BREVO_API_KEY}")
    private String brevoKey;

    @Value("${MAIL_SENDER}")
    private String sender;

    @Value("${API_KEY}")
    private String appKey;

    private final RestClient http;
    private int windowCount = 0;
    private long windowStart = System.currentTimeMillis();

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

    public record CallEvent(String number, long timestamp, String notifyEmail, String name, String timezone) {}

    @PostMapping("/calls")
    public ResponseEntity<String> receiveCall(
            @RequestHeader(value = "X-API-Key", required = false) String providedKey,
            @RequestBody CallEvent event) {

        if (providedKey == null || !MessageDigest.isEqual(
                providedKey.getBytes(StandardCharsets.UTF_8), appKey.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).body("unauthorized");
        }

        String number = clean(event.number(), 32);
        String name = clean(event.name(), 100);
        String to = event.notifyEmail() == null ? "" : event.notifyEmail().trim();
        if (number.isEmpty() || !EMAIL.matcher(to).matches()) {
            return ResponseEntity.status(400).body("invalid request");
        }
        if (!allowed()) {
            return ResponseEntity.status(429).body("too many requests");
        }

        // Never log full numbers, names, or emails
        log.info("Call event received, number=***{}", number.length() > 4 ? number.substring(number.length() - 4) : number);

        try {
            String who = name.isEmpty() ? number : name + " (" + number + ")";
            String text = "Caller: " + (name.isEmpty() ? "Unknown" : name)
                    + "\nNumber: " + number
                    + "\nTime: " + formatTime(event.timestamp(), event.timezone());

            Map<String, Object> body = Map.of(
                    "sender", Map.of("name", "Call Notifier", "email", sender),
                    "to", List.of(Map.of("email", to)),
                    "subject", "Call from " + who,
                    "textContent", text
            );

            http.post()
                    .uri("https://api.brevo.com/v3/smtp/email")
                    .header("api-key", brevoKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Email request accepted by provider");
            return ResponseEntity.ok("sent");
        } catch (Exception e) {
            log.error("Email failed", e);
            return ResponseEntity.status(500).body("email failed");
        }
    }

    // Simple fixed-window limit so a call flood can't burn through your email quota
    private synchronized boolean allowed() {
        long now = System.currentTimeMillis();
        if (now - windowStart > 60_000) {
            windowStart = now;
            windowCount = 0;
        }
        return ++windowCount <= MAX_EMAILS_PER_MINUTE;
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[\\r\\n\\t]", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String formatTime(long timestamp, String tz) {
        ZoneId zone;
        try {
            zone = (tz == null || tz.isBlank()) ? ZoneId.of("UTC") : ZoneId.of(tz);
        } catch (Exception e) {
            zone = ZoneId.of("UTC");
        }
        return DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm:ss a (z)")
                .withZone(zone)
                .format(Instant.ofEpochMilli(timestamp));
    }
}