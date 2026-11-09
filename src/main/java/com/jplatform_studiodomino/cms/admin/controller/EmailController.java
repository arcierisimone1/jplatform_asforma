package com.jplatform_studiodomino.cms.admin.controller;

import com.jplatform_studiodomino.cms.admin.service.EmailSenderService;
import com.jplatform_studiodomino.shared.config.Configurazione;
import com.jplatform_studiodomino.shared.entity.Account;
import com.jplatform_studiodomino.shared.entity.MessaggioUtente;
import com.jplatform_studiodomino.shared.repository.AccountRepository;
import com.jplatform_studiodomino.shared.repository.MessaggioUtenteRepository;
import com.jplatform_studiodomino.shared.service.ConfigurazioneService;
import com.jplatform_studiodomino.shared.service.ImapService;
import com.jplatform_studiodomino.shared.util.ViewUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/email")
@RequiredArgsConstructor
@Slf4j
public class EmailController {

    private final ConfigurazioneService configurazioneService;
    private final EmailSenderService emailSenderService;
    private final ImapService imapService;
    private final AccountRepository accountRepository;
    private final MessaggioUtenteRepository messaggioUtenteRepository;

    // Helper: prende solo il primo ID se multipli (es. "13,3" → "13")
    private String pulisciAccountId(String id) {
        if (id == null || id.isBlank()) return null;
        return id.contains(",") ? id.split(",")[0].trim() : id.trim();
    }

    @GetMapping({"", "/inbox", "/inbox/{idAccount}", "/{idAccount}/cartella/{folder}"})
    public String inbox(
            @PathVariable(value = "idAccount", required = false) String idAccount,
            @PathVariable(value = "folder", required = false) String folderPath,
            @RequestParam(value = "folder", defaultValue = "INBOX") String folderParam,
            HttpServletRequest request,
            Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        String folder = (folderPath != null) ? folderPath : folderParam;

        // Lista account associati per sidebar
        List<Account> associati = new ArrayList<>();
        String idEmailRaw = config.getAmministratore().getIdaccountemail();
        String idPecRaw   = config.getAmministratore().getIdaccountpec();
        String tuttiIds   = (idEmailRaw != null ? idEmailRaw : "") + "," + (idPecRaw != null ? idPecRaw : "");

        Set<Integer> setIds = new HashSet<>();
        for (String s : tuttiIds.split(",")) {
            String t = s.trim();
            if (!t.isEmpty() && !"0".equals(t)) {
                try { setIds.add(Integer.parseInt(t)); } catch (NumberFormatException ignored) {}
            }
        }
        if (!setIds.isEmpty()) associati = accountRepository.findAllById(setIds);
        model.addAttribute("listaAccountAssociati", associati);

        String accountDaCaricare = pulisciAccountId(idAccount);

        if (accountDaCaricare == null || "0".equals(accountDaCaricare)) {
            accountDaCaricare = pulisciAccountId(idEmailRaw);
            if ("0".equals(accountDaCaricare)) accountDaCaricare = null;
        }

        try {
            List<Map<String, Object>> emails = Collections.emptyList();
            if (accountDaCaricare != null && !"0".equals(accountDaCaricare)) {
                config.setActualMailboxId(accountDaCaricare);
                config.setActualMailboxFolderName(folder);
                configurazioneService.saveConfig(session, config);
                emails = imapService.leggiInbox(Integer.parseInt(accountDaCaricare), folder);
            }
            model.addAttribute("listaEmailArrivo", emails);
            model.addAttribute("folderAttivo", folder);
            model.addAttribute("currentAccountId", accountDaCaricare);
            model.addAttribute("erroreMailer", accountDaCaricare == null ? "configurazione_assente" : null);
        } catch (Exception e) {
            log.error("Errore caricamento inbox account={} folder={}", accountDaCaricare, folder, e);
            Throwable causa = e;
            boolean authFallita = false;
            while (causa != null) {
                if (causa instanceof jakarta.mail.AuthenticationFailedException) { authFallita = true; break; }
                causa = causa.getCause();
            }
            model.addAttribute("listaEmailArrivo", Collections.emptyList());
            model.addAttribute("folderAttivo", folder);
            model.addAttribute("currentAccountId", accountDaCaricare);
            model.addAttribute("erroreMailer", authFallita ? "autenticazione_fallita" : "errore_generico");
        }

        model.addAttribute("config", config);
        return ViewUtils.resolveProtectedTemplate("email/elencoEmail");
    }

    @GetMapping("/inbox/{idAccount}/nonletti")
    public String inboxNonLetti(
            @PathVariable String idAccount,
            HttpServletRequest request,
            Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        String accountDaCaricare = pulisciAccountId(idAccount);

        // Lista account associati per sidebar
        List<Account> associati = new ArrayList<>();
        String idEmailRaw = config.getAmministratore().getIdaccountemail();
        String idPecRaw   = config.getAmministratore().getIdaccountpec();
        String tuttiIds   = (idEmailRaw != null ? idEmailRaw : "") + "," + (idPecRaw != null ? idPecRaw : "");
        Set<Integer> setIds = new HashSet<>();
        for (String s : tuttiIds.split(",")) {
            String t = s.trim();
            if (!t.isEmpty() && !"0".equals(t)) {
                try { setIds.add(Integer.parseInt(t)); } catch (NumberFormatException ignored) {}
            }
        }
        if (!setIds.isEmpty()) associati = accountRepository.findAllById(setIds);
        model.addAttribute("listaAccountAssociati", associati);

        try {
            config.setActualMailboxId(accountDaCaricare);
            config.setActualMailboxFolderName("INBOX");
            configurazioneService.saveConfig(session, config);

            List<Map<String, Object>> tutte = imapService.leggiInbox(Integer.parseInt(accountDaCaricare), "INBOX");
            List<Map<String, Object>> nonLette = tutte.stream()
                    .filter(e -> Boolean.FALSE.equals(e.get("letto")) || "false".equalsIgnoreCase(String.valueOf(e.get("letto"))))
                    .collect(Collectors.toList());

            model.addAttribute("listaEmailArrivo", nonLette);
            model.addAttribute("filtroNonLetti", true);
            model.addAttribute("folderAttivo", "INBOX");
            model.addAttribute("currentAccountId", accountDaCaricare);
            model.addAttribute("erroreMailer", null);
        } catch (Exception e) {
            log.error("Errore inboxNonLetti account={}", accountDaCaricare, e);
            model.addAttribute("listaEmailArrivo", Collections.emptyList());
            model.addAttribute("erroreMailer", "errore_generico");
            model.addAttribute("folderAttivo", "INBOX");
        }

        model.addAttribute("config", config);
        return ViewUtils.resolveProtectedTemplate("email/elencoEmail");
    }

    @GetMapping("/api/nonletti")
    @ResponseBody
    public List<Map<String, Object>> nonLetti(HttpServletRequest request) {
        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return Collections.emptyList();

        List<Map<String, Object>> risultato = new ArrayList<>();

        String idEmailRaw = config.getAmministratore().getIdaccountemail();
        String idPecRaw   = config.getAmministratore().getIdaccountpec();
        String tuttiIds   = (idEmailRaw != null ? idEmailRaw : "") + "," + (idPecRaw != null ? idPecRaw : "");

        Set<String> setIds = new LinkedHashSet<>();
        for (String s : tuttiIds.split(",")) {
            String t = s.trim();
            if (!t.isEmpty() && !"0".equals(t)) setIds.add(t);
        }

        for (String idStr : setIds) {
            try {
                int idAcc = Integer.parseInt(idStr);
                Account acc = accountRepository.findById(idAcc).orElse(null);
                if (acc == null) continue;

                int conteggio = imapService.contaMessaggiNonLetti(idAcc);
                String emailVis = (acc.getEmailAccount() != null && !acc.getEmailAccount().isEmpty())
                        ? acc.getEmailAccount() : acc.getPecAccount();

                Map<String, Object> m = new HashMap<>();
                m.put("id", idAcc);
                m.put("descrizione", acc.getDescrizione());
                m.put("email", emailVis);
                m.put("nonLetti", conteggio);
                m.put("tipo", (acc.getPecAccount() != null && !acc.getPecAccount().isEmpty()) ? "PEC" : "EMAIL");
                risultato.add(m);
            } catch (Exception e) {
                log.warn("Errore recupero account ID {}: {}", idStr, e.getMessage());
            }
        }
        return risultato;
    }

    @GetMapping({"/open/{id}", "/open/{id}/{idAccount}"})
    public String openEmail(
            @PathVariable("id") int id,
            @PathVariable(value = "idAccount", required = false) String idAccount,
            @RequestParam(value = "fragment", defaultValue = "false") String fragment,
            HttpServletRequest request,
            Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        MessaggioUtente messaggio = new MessaggioUtente();
        try {
            String accId = pulisciAccountId(
                    (idAccount != null && !idAccount.isBlank() && !"0".equals(idAccount))
                            ? idAccount : config.getActualMailboxId()
            );

            if (accId != null && !"0".equals(accId)) {
                Map<String, Object> emailData = imapService.leggiMessaggio(
                        Integer.parseInt(accId), id, config.getActualMailboxFolderName());
                if (emailData != null) {
                    messaggio.setMittente((String) emailData.getOrDefault("mittente", ""));
                    messaggio.setIndirizzoMittente((String) emailData.getOrDefault("mittente", ""));
                    messaggio.setOggetto((String) emailData.getOrDefault("oggetto", ""));
                    messaggio.setMessaggio((String) emailData.getOrDefault("testo", ""));
                    messaggio.setDate(emailData.get("data") != null ? emailData.get("data").toString() : "");
                    messaggio.setId(id);
                }
            }
        } catch (Exception e) {
            log.error("Errore open email id={} account={}", id, idAccount, e);
        }

        model.addAttribute("messaggioEmail", messaggio);
        model.addAttribute("config", config);

        if ("true".equals(fragment)) {
            return ViewUtils.resolveProtectedTemplate("email/dettaglioEmailFragment");
        }
        return ViewUtils.resolveProtectedTemplate("email/dettaglioEmail");
    }

    @GetMapping({"/rispondi/{id}", "/rispondi/{id}/{idAccount}"})
    public String rispondiEmail(
            @PathVariable("id") int id,
            @PathVariable(value = "idAccount", required = false) String idAccount,
            HttpServletRequest request,
            Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        MessaggioUtente messaggio = new MessaggioUtente();
        try {
            String accId = pulisciAccountId(
                    (idAccount != null && !idAccount.isBlank() && !"0".equals(idAccount))
                            ? idAccount : config.getActualMailboxId()
            );

            if (accId != null && !"0".equals(accId)) {
                Map<String, Object> emailData = imapService.leggiMessaggio(
                        Integer.parseInt(accId), id, config.getActualMailboxFolderName());
                if (emailData != null) {
                    messaggio.setOggetto("Re: " + emailData.getOrDefault("oggetto", ""));
                    messaggio.setEmailDestinatario((String) emailData.getOrDefault("mittente", ""));
                    messaggio.setMessaggio("");
                }
            }
        } catch (Exception e) {
            log.error("Errore rispondi email id={}", id, e);
        }

        model.addAttribute("messaggioEmail", messaggio);
        model.addAttribute("config", config);
        return ViewUtils.resolveProtectedTemplate("email/componiEmail");
    }

    @GetMapping({"/inoltra/{id}", "/inoltra/{id}/{idAccount}"})
    public String inoltraEmail(
            @PathVariable("id") int id,
            @PathVariable(value = "idAccount", required = false) String idAccount,
            HttpServletRequest request,
            Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        MessaggioUtente messaggio = new MessaggioUtente();
        try {
            String accId = pulisciAccountId(
                    (idAccount != null && !idAccount.isBlank() && !"0".equals(idAccount))
                            ? idAccount : config.getActualMailboxId()
            );

            if (accId != null && !"0".equals(accId)) {
                Map<String, Object> emailData = imapService.leggiMessaggio(
                        Integer.parseInt(accId), id, config.getActualMailboxFolderName());
                if (emailData != null) {
                    messaggio.setOggetto("Fwd: " + emailData.getOrDefault("oggetto", ""));
                    messaggio.setMessaggio((String) emailData.getOrDefault("testo", ""));
                }
            }
        } catch (Exception e) {
            log.error("Errore inoltra email id={}", id, e);
        }

        model.addAttribute("messaggioEmail", messaggio);
        model.addAttribute("config", config);
        return ViewUtils.resolveProtectedTemplate("email/componiEmail");
    }

    @GetMapping({"/componi", "/componi/{idAccount}"})
    public String componiEmail(
            @PathVariable(value = "idAccount", required = false) String idAccount,
            HttpServletRequest request,
            Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        model.addAttribute("messaggioEmail", new MessaggioUtente());
        model.addAttribute("config", config);
        return ViewUtils.resolveProtectedTemplate("email/componiEmail");
    }

    @PostMapping("/invia")
    public String inviaEmail(
            @RequestParam("to") String to,
            @RequestParam(value = "cc", required = false) String cc,
            @RequestParam("oggetto") String oggetto,
            @RequestParam("testo") String testo,
            @RequestParam(value = "file_upload", required = false) List<MultipartFile> allegati,
            HttpServletRequest request,
            Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        MessaggioUtente messaggio = new MessaggioUtente();
        messaggio.setEmailMittente(to);
        messaggio.setMessaggio(testo);
        messaggio.setOggetto(oggetto);

        try {
            emailSenderService.inviaEmail(to, cc, oggetto, testo, allegati);
            model.addAttribute("messaggioEmail", new MessaggioUtente());
            model.addAttribute("esito", "ok");
        } catch (Exception e) {
            log.error("Errore invio email a={}", to, e);
            model.addAttribute("esito", "errore");
            model.addAttribute("errorMessage", "Errore: " + e.getMessage());
            model.addAttribute("messaggioEmail", messaggio);
        }

        model.addAttribute("config", config);
        return ViewUtils.resolveProtectedTemplate("email/componiEmail");
    }

    @PostMapping({"/eliminaMultiplo/{idAccount}", "/eliminaMultiplo"})
    @ResponseBody
    public ResponseEntity<String> eliminaMultiplo(
            @RequestParam("ids") List<String> ids,
            @PathVariable(value = "idAccount", required = false) String idAccount,
            HttpServletRequest request) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged() || ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().body("Parametri non validi");
        }

        try {
            String accId = pulisciAccountId(idAccount);
            Account account = accountRepository.findById(Integer.parseInt(accId))
                    .orElseThrow(() -> new RuntimeException("Account non trovato"));

            imapService.cancellaMessaggiDalServer(account, ids);

            for (String idStr : ids) {
                try {
                    messaggioUtenteRepository.deleteById(Integer.parseInt(idStr));
                } catch (Exception ex) {
                    log.warn("Errore rimozione dal DB id={}", idStr);
                }
            }
            return ResponseEntity.ok("Cancellazione completata");
        } catch (Exception e) {
            log.error("Errore eliminazione multipla", e);
            return ResponseEntity.internalServerError().body("Errore: " + e.getMessage());
        }
    }

    @GetMapping({"/allegato/{id}/{filename}", "/allegato/{id}/{filename}/{idAccount}"})
    public void getAllegato(
            @PathVariable("id") int id,
            @PathVariable("filename") int allegatoIndex,
            @PathVariable(value = "idAccount", required = false) String idAccount,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) { response.sendError(403); return; }

        try {
            log.info("Download allegato emailId={} index={} account={}", id, allegatoIndex, idAccount);
            response.sendError(501, "Non ancora implementato");
        } catch (Exception e) {
            log.error("Errore download allegato emailId={} index={}", id, allegatoIndex, e);
            response.sendError(500);
        }
    }
}