package com.jplatform_studiodomino.cms.admin.controller;

import com.jplatform_studiodomino.shared.config.Configurazione;
import com.jplatform_studiodomino.shared.service.ConfigurazioneService;
import com.jplatform_studiodomino.shared.service.DashboardService;
import com.jplatform_studiodomino.shared.util.ViewUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final ConfigurazioneService configurazioneService;
    private final DashboardService dashboardService;

    @GetMapping
    public String dashboard(HttpServletRequest request, Model model) {
        HttpSession session = request.getSession();

        // ✅ OTTIENE ConfigurazioneCore
        Configurazione configCore = configurazioneService.getConfig(session);

        // Verifica login
        if (!configCore.isLogged()) {
            return "redirect:/login";
        }

        log.info("=== ADMIN DASHBOARD === user: {}", configCore.getUsername());

        // ✅ PASSA ConfigurazioneCore al template
        model.addAttribute("config", configCore);
        model.addAttribute("stats", dashboardService.getStatistiche(
                configCore.getSito() != null ? configCore.getSito().getId() : null));

        return ViewUtils.resolveProtectedTemplate("front/dashboard");
    }

    @GetMapping("/ping")
    @ResponseBody
    public ResponseEntity<String> ping(HttpServletRequest request) {
        request.getSession().setMaxInactiveInterval(600);
        return ResponseEntity.ok("ok");
    }
}