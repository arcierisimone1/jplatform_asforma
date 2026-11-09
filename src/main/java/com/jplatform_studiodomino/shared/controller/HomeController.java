package com.jplatform_studiodomino.shared.controller;

import com.jplatform_studiodomino.shared.config.Configurazione;
import com.jplatform_studiodomino.shared.entity.Utente;
import com.jplatform_studiodomino.shared.enums.ModuloApplicativo;
import com.jplatform_studiodomino.shared.service.ConfigurazioneService;
import com.jplatform_studiodomino.shared.service.DashboardService;
import com.jplatform_studiodomino.shared.util.ViewUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
@RequiredArgsConstructor
@Slf4j
public class HomeController {

    private final ConfigurazioneService configurazioneService;
    private final DashboardService dashboardService;

    /**
     * Dashboard generico con parametro L2
     * Usato quando utente non ha un modulo specifico
     */
    @GetMapping("/dashboard/{l2}")
    public String dashboard(
            @PathVariable String l2,
            HttpServletRequest request,
            Model model) {

        HttpSession session = request.getSession();

        // ✅ USA ConfigurationService per ottenere utente
        Utente utente = configurazioneService.getAmministratore(session);

        // Verifica login
        if (utente == null) {
            log.warn("Utente non loggato, redirect a login");
            return "redirect:/login";
        }

        // ✅ USA il metodo corretto
        Configurazione config = configurazioneService.getOrCreateConfiguration(request);

        log.info("=== DASHBOARD L2={} === user: {}", l2, utente.getUsername());

        // Ottiene descrizione modulo
        String moduloDescrizione = ModuloApplicativo.getDescrizione(l2);

        model.addAttribute("config", config);
        model.addAttribute("l2", l2);
        model.addAttribute("moduloDescrizione", moduloDescrizione);
        model.addAttribute("stats", dashboardService.getStatistiche(
                config.getSito() != null ? config.getSito().getId() : null));

        // Template generico basato su L2
        return ViewUtils.resolveProtectedTemplate("front/dashboard");
    }

    /**
     * Home page personalizzata
     * Mostra link a tutti i moduli accessibili dall'utente
     */
    @GetMapping("/home")
    public String home(HttpServletRequest request, Model model) {

        HttpSession session = request.getSession();

        // ✅ USA ConfigurationService
        Utente utente = configurazioneService.getAmministratore(session);

        if (utente == null) {
            log.warn("Utente non loggato, redirect a login");
            return "redirect:/login";
        }

        Configurazione config = configurazioneService.getOrCreateConfiguration(request);

        log.info("=== HOME === user: {}", utente.getUsername());

        model.addAttribute("config", config);

        return "home";
    }

    /**
     * Redirect "indefinito" - quando utente ha l2 vuoto
     */
    @GetMapping("/indefinito")
    public String indefinito(HttpServletRequest request, Model model) {

        HttpSession session = request.getSession();
        Utente utente = configurazioneService.getAmministratore(session);

        if (utente == null) {
            return "redirect:/login";
        }

        Configurazione config = configurazioneService.getOrCreateConfiguration(request);

        log.info("=== INDEFINITO === user: {}, l2: {}",
                utente.getUsername(), utente.getL2());

        model.addAttribute("config", config);
        model.addAttribute("message", "Nessun modulo assegnato. Contatta l'amministratore.");

        return ViewUtils.resolveProtectedTemplate("front/indefinito");
    }
}