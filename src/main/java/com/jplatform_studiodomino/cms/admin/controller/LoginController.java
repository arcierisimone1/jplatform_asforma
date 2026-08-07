package com.jplatform_studiodomino.cms.admin.controller;

import com.jplatform_studiodomino.shared.config.Configurazione;
import com.jplatform_studiodomino.shared.entity.Site;
import com.jplatform_studiodomino.shared.entity.Utente;
import com.jplatform_studiodomino.shared.service.ConfigurazioneService;
import com.jplatform_studiodomino.shared.service.RememberMeService;
import com.jplatform_studiodomino.shared.service.SiteService;
import com.jplatform_studiodomino.shared.service.UtenteService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
@Slf4j
public class LoginController {

    private final UtenteService utenteService;
    private final ConfigurazioneService configurazioneService;
    private final SiteService siteService;
    private final RememberMeService rememberMeService;

    /**
     * GET /login - Mostra form di login, oppure auto-login via remember me
     */
    @GetMapping("/login")
    public String showLogin(HttpServletRequest request, HttpServletResponse response, Model model) {
        HttpSession session = request.getSession();
        Configurazione configCore = configurazioneService.getOrCreateConfiguration(request);

        // Se già loggato, redirect diretto
        Utente utente = configurazioneService.getAmministratore(session);
        if (utente != null) {
            return "redirect:/admin";
        }

        // ✅ Controlla cookie remember me
        String rememberedUsername = rememberMeService.resolveUsername(request);
        if (rememberedUsername != null) {
            Utente rememberedUser = utenteService.findByUsername(rememberedUsername);
            if (rememberedUser != null) {
                log.info("Auto-login via remember me per: {}", rememberedUsername);
                eseguiLogin(rememberedUser, session, request, response, true);
                return "redirect:/admin";
            } else {
                // username non più valido, pulisci il cookie
                rememberMeService.deleteRememberMeCookie(request, response);
            }
        }

        model.addAttribute("config", configCore);
        return "auth/login";
    }

    /**
     * POST /login - Processa login manuale
     */
    @PostMapping("/login")
    public String login(
            @RequestParam String username,
            @RequestParam String password,
            @RequestParam(required = false, defaultValue = "false") boolean remember,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {

        log.info("=== LOGIN === username: {}", username);

        HttpSession session = request.getSession();
        Configurazione configCore = configurazioneService.getOrCreateConfiguration(request);

        Utente utente = utenteService.authenticate(username, password);

        if (utente == null) {
            log.warn("✗ Login fallito per: {}", username);
            model.addAttribute("erroreLogin", "Credenziali errate. Riprova.");
            model.addAttribute("config", configCore);
            return "auth/login";
        }

        log.info("✓ Login riuscito per: {}", username);

        eseguiLogin(utente, session, request, response, remember);

        return "redirect:/admin";
    }

    /**
     * GET /logout - Invalida sessione e cancella cookie remember me
     */
    @GetMapping("/logout")
    public String logout(HttpServletRequest request, HttpServletResponse response) {
        log.info("=== LOGOUT ===");
        HttpSession session = request.getSession();
        configurazioneService.invalidateSession(session);

        // ✅ Cancella cookie remember me
        rememberMeService.deleteRememberMeCookie(request, response);

        return "redirect:/login";
    }

    // =========================================================
    // UTILITY
    // =========================================================

    /**
     * Operazioni comuni dopo un login riuscito (manuale o automatico)
     */
    private void eseguiLogin(Utente utente, HttpSession session,
                             HttpServletRequest request, HttpServletResponse response,
                             boolean remember) {

        utenteService.aggiornaStatisticheAccesso(utente, request);
        configurazioneService.setAmministratore(session, utente);

        // ✅ Crea cookie 30 giorni se remember=true
        if (remember) {
            rememberMeService.createRememberMeCookie(utente.getUsername(), response);
        }

        // Ripristina sito richiesto prima del login
        Integer requestedSiteId = configurazioneService.getAndClearRequestedSite(session);
        if (requestedSiteId != null) {
            Site requestedSite = siteService.findById(requestedSiteId);
            if (requestedSite != null) {
                configurazioneService.setSite(session, requestedSite);
            }
        }
    }
}