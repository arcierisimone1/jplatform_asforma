package com.jplatform_studiodomino.shared.service;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * "Ricorda dati di accesso": il cookie contiene username + scadenza + firma HMAC.
 * Nessuno stato in memoria, quindi sopravvive ai riavvii del server.
 * Chiave segreta: proprieta' remember.secret (in docker: variabile REMEMBER_SECRET).
 */
@Service
@Slf4j
public class RememberMeService {

    private static final String COOKIE_NAME = "jplatform_remember";
    private static final int COOKIE_DAYS = 7;
    private static final int COOKIE_MAX_AGE = COOKIE_DAYS * 24 * 60 * 60;
    private static final String HMAC_ALGO = "HmacSHA256";

    @Value("${remember.secret:}")
    private String configuredSecret;

    private byte[] secretKey;

    @PostConstruct
    void init() {
        if (configuredSecret != null && configuredSecret.length() >= 32) {
            secretKey = configuredSecret.getBytes(StandardCharsets.UTF_8);
        } else {
            // Nessuna chiave configurata: ne genero una casuale (valida fino al riavvio).
            secretKey = new byte[32];
            new SecureRandom().nextBytes(secretKey);
            log.warn("remember.secret mancante o troppo corta (minimo 32 caratteri): "
                    + "il 'ricordami' si azzerera' a ogni riavvio. Configurala in application.properties.");
        }
    }

    /** Crea il cookie remember me (30 giorni). */
    public void createRememberMeCookie(String username, HttpServletResponse response) {
        long scadenza = (System.currentTimeMillis() / 1000L) + COOKIE_MAX_AGE;
        String userB64 = b64(username.getBytes(StandardCharsets.UTF_8));
        String payload = userB64 + "." + scadenza;
        String token = payload + "." + firma(payload);

        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setMaxAge(COOKIE_MAX_AGE);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);

        log.info("Remember me cookie creato per: {}", username);
    }

    /** Restituisce lo username se il cookie e' valido e non scaduto, altrimenti null. */
    public String resolveUsername(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;

        for (Cookie cookie : cookies) {
            if (!COOKIE_NAME.equals(cookie.getName())) continue;

            String[] parti = cookie.getValue().split("\\.");
            if (parti.length != 3) return null;

            String payload = parti[0] + "." + parti[1];
            byte[] firmaAttesa = firma(payload).getBytes(StandardCharsets.UTF_8);
            byte[] firmaRicevuta = parti[2].getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(firmaAttesa, firmaRicevuta)) return null;

            try {
                long scadenza = Long.parseLong(parti[1]);
                if (scadenza < System.currentTimeMillis() / 1000L) return null;
                String username = new String(Base64.getUrlDecoder().decode(parti[0]), StandardCharsets.UTF_8);
                log.info("Remember me valido per: {}", username);
                return username;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    /** Cancella il cookie nel browser. */
    public void deleteRememberMeCookie(HttpServletRequest request, HttpServletResponse response) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return;

        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                Cookie deleteCookie = new Cookie(COOKIE_NAME, "");
                deleteCookie.setMaxAge(0);
                deleteCookie.setPath("/");
                deleteCookie.setHttpOnly(true);
                response.addCookie(deleteCookie);

                log.info("Remember me cookie cancellato");
                break;
            }
        }
    }

    private String firma(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGO));
            return b64(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Errore nel calcolo della firma", e);
        }
    }

    private static String b64(byte[] dati) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(dati);
    }
}