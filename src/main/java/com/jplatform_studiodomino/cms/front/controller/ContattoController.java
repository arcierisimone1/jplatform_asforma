package com.jplatform_studiodomino.cms.front.controller;

import com.jplatform_studiodomino.cms.admin.service.EmailSenderService;
import com.jplatform_studiodomino.cms.service.CommentoService;
import com.jplatform_studiodomino.crm.service.AnagraficaService;
import com.jplatform_studiodomino.shared.config.Configurazione;
import com.jplatform_studiodomino.shared.entity.UtenteEsterno;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Riceve l'invio del form "Richiedi Informazioni" (fragment
 * site01/fragments/jspUser/contatti2) e salva un Messaggio Web
 * (tabella commenti, tipologia="web") in attesa di essere
 * gestito e profilato in Lead dallo staff CRM.
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ContattoController {

    private static final String DEFAULT_RETURN = "/front/370/Contatti";
    private static final String EMAIL_NOTIFICA_STAFF = "info@asforma.it";

    private final ConfigurazioneService configurazioneService;
    private final CommentoService commentoService;
    private final EmailSenderService emailSenderService;
    private final AnagraficaService anagraficaService;

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

        // dati scritti dall'utente: li rimandiamo indietro se qualcosa non va
        Map<String, String> datiForm = new LinkedHashMap<>();
        datiForm.put("nome", nome);
        datiForm.put("cognome", cognome);
        datiForm.put("email", email);
        datiForm.put("telefono", telefono);
        datiForm.put("messaggioInformativo", messaggioInformativo);
        datiForm.put("terms_conditions", terms_conditions);

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
                redirectAttributes.addFlashAttribute("captchaErrore", true);
                redirectAttributes.addFlashAttribute("contattoForm", datiForm);
                return "redirect:" + redirect + "#informazioniGeneriche";
            }

            // ===== VERIFICA PRIVACY =====
            if (terms_conditions == null || terms_conditions.isBlank()) {
                redirectAttributes.addFlashAttribute("contattoErrore",
                        "Devi accettare l'informativa privacy per inviare la richiesta.");
                redirectAttributes.addFlashAttribute("contattoForm", datiForm);
                return "redirect:" + redirect + "#informazioniGeneriche";
            }

            Configurazione config = configurazioneService.getOrCreateConfiguration(request);

            // ===== ANAGRAFICA: riusa quella esistente per email, o creane una nuova =====
            Integer idAnagrafica = null;
            String emailTrim = email != null ? email.trim() : "";
            if (!emailTrim.isBlank()) {
                idAnagrafica = anagraficaService.getIdByEmail(emailTrim);
                if (idAnagrafica == null) {
                    UtenteEsterno nuovoUtente = new UtenteEsterno();
                    nuovoUtente.setNome(nome != null ? nome.trim() : "");
                    nuovoUtente.setCognome(cognome != null ? cognome.trim() : "");
                    nuovoUtente.setEmail(emailTrim);
                    nuovoUtente.setUsername(emailTrim);
                    nuovoUtente.setTelefono(telefono != null ? telefono.trim() : "");
                    nuovoUtente.setPassword(String.valueOf(Math.abs(new Random().nextInt()) % 1000000));
                    nuovoUtente.setDatacreazione(LocalDate.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy")));
                    nuovoUtente.setDataultimoaccesso("");
                    UtenteEsterno creato = anagraficaService.crea(nuovoUtente);
                    idAnagrafica = creato.getId();
                    log.info("Nuova anagrafica creata da form contatti: id={} email={}", idAnagrafica, emailTrim);
                }
            }

            String oggettoFinale = (oggetto != null && !oggetto.isBlank()) ? oggetto : "Informazioni generali";
            String testoMessaggio = oggettoFinale + " : " + (messaggioInformativo != null ? messaggioInformativo.trim() : "");

            var commento = commentoService.creaCommento(
                    idoggetto,
                    nome != null ? nome.trim() : "",
                    cognome != null ? cognome.trim() : "",
                    email != null ? email.trim() : "",
                    testoMessaggio,
                    "0",
                    "web");
            commento.setL1(telefono != null ? telefono.trim() : "");
            commento.setIduser(idAnagrafica != null ? String.valueOf(idAnagrafica) : "");
            commentoService.save(commento);

            // ===== NOTIFICA STAFF VIA EMAIL =====
            // il messaggio resta salvato nel CRM anche se l'invio email fallisce (es. SMTP giù)
            try {
                String testoEmail = "<p>Nuova richiesta ricevuta dal sito web.</p>"
                        + "<p><strong>Oggetto:</strong> " + oggettoFinale + "</p>"
                        + "<p><strong>Nome:</strong> " + commento.getNome() + "<br>"
                        + "<strong>Cognome:</strong> " + commento.getCognome() + "<br>"
                        + "<strong>Email:</strong> " + commento.getEmail() + "<br>"
                        + "<strong>Telefono:</strong> " + commento.getL1() + "</p>"
                        + "<p><strong>Messaggio:</strong><br>"
                        + (messaggioInformativo != null ? messaggioInformativo.trim() : "") + "</p>";

                emailSenderService.inviaEmail(
                        EMAIL_NOTIFICA_STAFF, null,
                        "Nuova richiesta informazioni dal sito - " + oggettoFinale,
                        testoEmail);
            } catch (Exception emailEx) {
                log.warn("Messaggio salvato ma invio email di notifica fallito: {}", emailEx.getMessage());
            }

            // ===== CONFERMA VIA EMAIL AL MITTENTE =====
            try {
                String testoConferma = "<p>Gentile " + commento.getNome() + " " + commento.getCognome() + ",</p>"
                        + "<p>la sua richiesta è stata inviata con successo. "
                        + "Le forniremo una risposta nel più breve tempo possibile.</p>"
                        + "<p>Cordiali saluti.</p>";

                if (commento.getEmail() != null && !commento.getEmail().isBlank()) {
                    emailSenderService.inviaEmail(
                            commento.getEmail(), null,
                            "Richiesta ricevuta - " + oggettoFinale,
                            testoConferma);
                }
            } catch (Exception emailEx) {
                log.warn("Messaggio salvato ma invio email di conferma al mittente fallito: {}", emailEx.getMessage());
            }

            redirectAttributes.addFlashAttribute("contattoOk", true);

        } catch (Exception e) {
            log.error("Errore invio form contatti", e);
            redirectAttributes.addFlashAttribute("contattoErrore",
                    "Errore durante l'invio della richiesta, riprova più tardi.");
            redirectAttributes.addFlashAttribute("contattoForm", datiForm);

        }

        return "redirect:" + redirect;
    }
}