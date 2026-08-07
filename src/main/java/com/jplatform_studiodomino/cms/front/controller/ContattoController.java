package com.jplatform_studiodomino.cms.front.controller;

import com.jplatform_studiodomino.cms.admin.service.EmailSenderService;
import com.jplatform_studiodomino.crm.entity.RegistroLead;
import com.jplatform_studiodomino.crm.service.RegistroLeadService;
import com.jplatform_studiodomino.shared.config.Configurazione;
import com.jplatform_studiodomino.shared.service.ConfigurazioneService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Riceve l'invio del form "Richiedi Informazioni" (fragment
 * site01/fragments/jspUser/contatti2), salva una riga RegistroLead
 * (direzione=entrata, store=diretto) e reindirizza alla pagina di
 * provenienza mostrando esito/errore.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ContattoController {

    private static final DateTimeFormatter DATA_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String DEFAULT_RETURN = "/front/370/Contatti";
    private static final String EMAIL_NOTIFICA_STAFF = "info@studiodominoweb.com";

    private final ConfigurazioneService configurazioneService;
    private final RegistroLeadService registroLeadService;
    private final EmailSenderService emailSenderService;

    @PostMapping("/contatti/invia")
    public String invia(
            @RequestParam String nome,
            @RequestParam String cognome,
            @RequestParam String email,
            @RequestParam String telefono,
            @RequestParam String messaggioInformativo,
            @RequestParam(required = false) String oggetto,
            @RequestParam(required = false, defaultValue = "0") String idoggetto,
            @RequestParam(required = false) String captcha,
            @RequestParam(required = false) String terms_conditions,
            @RequestParam(required = false, name = "return") String returnUrl,
            HttpServletRequest request,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        String redirect = (returnUrl != null && !returnUrl.isBlank()) ? returnUrl : DEFAULT_RETURN;

        try {
            // ===== VERIFICA CAPTCHA =====
            Object sessionSum = session.getAttribute("captchaSum");
            session.removeAttribute("captchaSum");
            session.removeAttribute("captchaA");
            session.removeAttribute("captchaB");

            boolean captchaOk = sessionSum != null
                    && captcha != null
                    && captcha.trim().equals(String.valueOf(sessionSum));

            if (!captchaOk) {
                redirectAttributes.addFlashAttribute("contattoErrore",
                        "Codice di controllo errato, riprova.");
                return "redirect:" + redirect;
            }

            // ===== VERIFICA PRIVACY =====
            if (terms_conditions == null || terms_conditions.isBlank()) {
                redirectAttributes.addFlashAttribute("contattoErrore",
                        "Devi accettare l'informativa privacy per inviare la richiesta.");
                return "redirect:" + redirect;
            }

            Configurazione config = configurazioneService.getOrCreateConfiguration(request);

            RegistroLead lead = new RegistroLead();
            lead.setDirezione("e");
            lead.setIdutente(0);
            lead.setIdleadstore(parseIntSafe(idoggetto));
            lead.setStore("diretto");
            lead.setStato("0");
            lead.setData(LocalDate.now().format(DATA_FORMAT));
            lead.setL1(nome != null ? nome.trim() : "");
            lead.setL2(cognome != null ? cognome.trim() : "");
            lead.setL3(email != null ? email.trim() : "");
            lead.setL4(telefono != null ? telefono.trim() : "");

            String oggettoFinale = (oggetto != null && !oggetto.isBlank()) ? oggetto : "Informazioni generali";
            lead.setNotalead(oggettoFinale + " : " + (messaggioInformativo != null ? messaggioInformativo.trim() : ""));

            registroLeadService.crea(lead, config);

            // ===== NOTIFICA STAFF VIA EMAIL =====
            // il lead resta salvato nel CRM anche se l'invio email fallisce (es. SMTP giù)
            try {
                String testoEmail = "<p>Nuova richiesta ricevuta dal sito web.</p>"
                        + "<p><strong>Oggetto:</strong> " + oggettoFinale + "</p>"
                        + "<p><strong>Nome:</strong> " + lead.getL1() + "<br>"
                        + "<strong>Cognome:</strong> " + lead.getL2() + "<br>"
                        + "<strong>Email:</strong> " + lead.getL3() + "<br>"
                        + "<strong>Telefono:</strong> " + lead.getL4() + "</p>"
                        + "<p><strong>Messaggio:</strong><br>"
                        + (messaggioInformativo != null ? messaggioInformativo.trim() : "") + "</p>";

                emailSenderService.inviaEmail(
                        EMAIL_NOTIFICA_STAFF, null,
                        "Nuova richiesta informazioni dal sito - " + oggettoFinale,
                        testoEmail);
            } catch (Exception emailEx) {
                log.warn("Lead salvato ma invio email di notifica fallito: {}", emailEx.getMessage());
            }

            redirectAttributes.addFlashAttribute("contattoOk", true);

        } catch (Exception e) {
            log.error("Errore invio form contatti", e);
            redirectAttributes.addFlashAttribute("contattoErrore",
                    "Errore durante l'invio della richiesta, riprova più tardi.");
        }

        return "redirect:" + redirect;
    }

    private int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return 0;
        }
    }
}
