package com.jplatform_studiodomino.cms.admin.controller;

import com.jplatform_studiodomino.cms.entity.Allegato;
import com.jplatform_studiodomino.cms.entity.DatiBase;
import com.jplatform_studiodomino.cms.entity.Section;
import com.jplatform_studiodomino.cms.entity.SectionType;
import com.jplatform_studiodomino.cms.service.AllegatoService;
import com.jplatform_studiodomino.cms.service.ContentService;
import com.jplatform_studiodomino.shared.config.Configurazione;
import com.jplatform_studiodomino.shared.entity.Images;
import com.jplatform_studiodomino.shared.service.ConfigurazioneService;
import com.jplatform_studiodomino.shared.service.ImagesService;
import com.jplatform_studiodomino.shared.util.ViewUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin/contenuti")
@RequiredArgsConstructor
@Slf4j
public class ContenutoController {

    private final ConfigurazioneService configurazioneService;
    private final ContentService contentService;
    private final AllegatoService allegatoService;
    private final ImagesService imagesService;

    // =====================================================================
    // NEW FORM
    // =====================================================================
    @GetMapping("/new")
    public String newForm(
            @RequestParam(value = "id_root", defaultValue = "0") Integer idRoot,
            @RequestParam(value = "id_type", defaultValue = "0") String idType,
            HttpServletRequest request, Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        try {
            String idSite = String.valueOf(config.getIdSito());
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));

            DatiBase datiBase = new DatiBase();
            datiBase.setId(null);
            datiBase.setIdSite(idSite);
            datiBase.setIdRoot(String.valueOf(idRoot));
            datiBase.setIdType(idType);
            datiBase.setData(today);
            datiBase.setS1(today);
            datiBase.setS2(today);
            datiBase.setGalleryString("");

            Section sezione = contentService.findSectionById(idRoot, idSite).orElse(null);
            sezione = normalizzaSezione(sezione, idRoot);

            datiBase.setSection(sezione);
            datiBase.setIdParent(sezione.getIdParent());

            for (int i = 1; i <= 10; i++) {
                datiBase.setExtraTag(i, sezione.getExtraTag(i));
                datiBase.setExtraTagRef(i, sezione.getExtraTagRef(i));
            }

            model.addAttribute("content", datiBase);
            model.addAttribute("post", datiBase);
            model.addAttribute("sezione", sezione);
            model.addAttribute("elencoSezioni", contentService.findAllSections(idSite));
            model.addAttribute("config", config);

            log.info("NEW CONTENUTO -> idRoot={}, idType={}, page2={}",
                    idRoot,
                    idType,
                    sezione.getSectionType() != null ? sezione.getSectionType().getPage2() : null);

        } catch (Exception e) {
            log.error("Errore newForm contenuto", e);
        }

        return ViewUtils.resolveProtectedTemplate("cms/dettaglioContenutoTemplate");
    }

    private Section normalizzaSezione(Section sezione, Integer idRoot) {
        if (sezione == null) {
            sezione = new Section();
            sezione.setId(idRoot != null ? idRoot : 0);
            sezione.setSectionType(new SectionType());
            return sezione;
        }

        if (sezione.getSectionType() == null && sezione.getIdType() != null) {
            SectionType st = contentService.getSectionTypeById(sezione.getIdType());
            sezione.setSectionType(st != null ? st : new SectionType());
        } else if (sezione.getSectionType() == null) {
            sezione.setSectionType(new SectionType());
        }

        return sezione;
    }

    @GetMapping("/new/{idRoot}")
    public String newFormPulito(@PathVariable Integer idRoot,
                                HttpServletRequest request,
                                Model model) {
        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        String idSite = String.valueOf(config.getIdSito());
        Section sezione = contentService.findSectionById(idRoot, idSite).orElse(null);

        String idType = (sezione != null && sezione.getIdType() != null)
                ? sezione.getIdType().toString()
                : "0";

        return newForm(idRoot, idType, request, model);
    }

    // =====================================================================
    // OPEN
    // =====================================================================
    @GetMapping("/{id}")
    public String open(@PathVariable Integer id,
                       HttpServletRequest request, Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        try {
            String idSite = String.valueOf(config.getIdSito());

            DatiBase documento = contentService.findContentById(id, idSite)
                    .orElseThrow(() -> new RuntimeException("Contenuto non trovato: " + id));

            Integer idRoot = parseIntSafe(documento.getIdRoot());
            Section sezione = (idRoot != null)
                    ? contentService.findSectionById(idRoot, idSite).orElse(null)
                    : null;

            sezione = normalizzaSezione(sezione, idRoot);

            documento.setSection(sezione);
            log.info("PRIMA caricaDocumentiCorrelati: sezione.id={}, l15={}, l11={}",
                    sezione.getId(), sezione.getL15(), sezione.getL11());
            caricaDocumentiCorrelati(documento, sezione, idSite);

            List<Section> elencoSezioni = contentService.findAllSections(idSite);

            model.addAttribute("content", documento);
            model.addAttribute("post", documento);
            model.addAttribute("sezione", sezione);
            model.addAttribute("elencoSezioni", elencoSezioni);
            model.addAttribute("config", config);

            // Popola gallery dalla galleryString
            String galleryString = documento.getGalleryString();
            if (galleryString != null && !galleryString.isEmpty()) {
                List<Images> gallery = new ArrayList<>();
                String[] parts = galleryString.split(";");
                for (String part : parts) {
                    part = part.trim().replace("(", "").replace(")", "");
                    if (!part.isEmpty()) {
                        try {
                            Integer imgId = Integer.parseInt(part);
                            imagesService.findById(imgId).ifPresent(gallery::add);
                        } catch (NumberFormatException ignored) {}
                    }
                }
                documento.setGallery(gallery);
            }

// Popola allegati
            if (documento.getId() != null && !documento.getId().isBlank()) {
                List<Allegato> allegati = allegatoService.findAllegatiByDocumento(
                        Integer.parseInt(documento.getId()));
                documento.setAllegati(allegati);
            }

            log.info("OPEN CONTENUTO -> id={}, idRoot={}, page2={}",
                    id,
                    idRoot,
                    sezione.getSectionType() != null ? sezione.getSectionType().getPage2() : null);

        } catch (Exception e) {
            log.error("Errore open contenuto id={}", id, e);
            return "redirect:/admin/contenuti?error=open";
        }

        return ViewUtils.resolveProtectedTemplate("cms/dettaglioContenutoTemplate");
    }

    // =====================================================================
    // SAVE
    // =====================================================================
    @CacheEvict(value = {"menuPubblico", "homePage", "tagCloud", "contenutiFront"}, allEntries = true)
    @PostMapping("/save")
    public String save(@ModelAttribute DatiBase documento,
                       HttpServletRequest request, Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        try {
            String idSite = String.valueOf(config.getIdSito());
            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy - HH:mm"));
            String operatore = config.getAmministratore().getNomeCompleto();

            if (documento.getDataSql() == null) {
                documento.setDataSql(LocalDate.now());
            }

            boolean isNew = (documento.getId() == null || documento.getId().isBlank()
                    || documento.getId().equals("0") || documento.getId().equals("-1"));

            if (isNew) {
                documento.setId(null);  // forza INSERT
                documento.setIdSite(idSite);
                documento.setCreato(now);
                documento.setCreatoDa(operatore);
                if (documento.getPrivato() == null || documento.getPrivato().isBlank()) {
                    documento.setPrivato("0");  // default pubblico
                }
                if (documento.getPosition() == null || documento.getPosition().isBlank()) {
                    documento.setPosition("0");
                }
                parseDataVisualizzata(documento);

                DatiBase saved = contentService.saveContent(documento);
                log.info("Contenuto creato: id={} titolo={}", saved.getId(), saved.getTitolo());
                return "redirect:/admin/contenuti/" + saved.getId();

            } else {
                // UPDATE: carica dal DB e aggiorna solo i campi modificabili
                DatiBase db = contentService.findContentById(
                                Integer.parseInt(documento.getId()), idSite)
                        .orElseThrow(() -> new RuntimeException("Contenuto non trovato"));

                db.setTitolo(documento.getTitolo());
                db.setRiassunto(documento.getRiassunto());
                db.setTesto(documento.getTesto());

                db.setStato(documento.getStato());
                db.setData(documento.getData());
                db.setDataVisualizzata(documento.getDataVisualizzata());
                db.setTag(documento.getTag());

                db.setS1(documento.getS1());
                db.setS2(documento.getS2());
                db.setS3(documento.getS3());
                // s4-s10 mancavano qui: prezzo (s5) e ore (s6) venivano scartati
                // silenziosamente al salvataggio, l'entita' restava con i valori
                // vecchi anche se il form admin li mostrava aggiornati.
                db.setS4(documento.getS4());
                db.setS5(documento.getS5());
                db.setS6(documento.getS6());
                db.setS7(documento.getS7());
                db.setS8(documento.getS8());
                db.setS9(documento.getS9());
                db.setS10(documento.getS10());

                db.setClick(documento.getClick());

                db.setInfo1(documento.getInfo1());
                db.setInfo2(documento.getInfo2());
                db.setInfo3(documento.getInfo3());
                db.setInfo4(documento.getInfo4());
                db.setInfo5(documento.getInfo5());

                db.setPrivato(documento.getPrivato());

                db.setL2(documento.getL2());
                db.setL3(documento.getL3());
                db.setL4(documento.getL4());
                db.setL11(documento.getL11());
                db.setL15(documento.getL15());

                db.setPosition(documento.getPosition());

                db.setVarchar1(documento.getVarchar1());
                db.setVarchar2(documento.getVarchar2());
                db.setVarchar3(documento.getVarchar3());
                db.setVarchar4(documento.getVarchar4());
                db.setVarchar5(documento.getVarchar5());
                db.setVarchar6(documento.getVarchar6());
                db.setVarchar7(documento.getVarchar7());
                db.setVarchar8(documento.getVarchar8());
                db.setVarchar9(documento.getVarchar9());
                db.setVarchar10(documento.getVarchar10());

                db.setText1(documento.getText1());
                db.setText2(documento.getText2());
                db.setText3(documento.getText3());
                db.setText4(documento.getText4());
                db.setText5(documento.getText5());
                db.setText6(documento.getText6());
                db.setText7(documento.getText7());
                db.setText8(documento.getText8());
                db.setText9(documento.getText9());
                db.setText10(documento.getText10());

                db.setRegolaExtraTag1(documento.getRegolaExtraTag1());
                db.setMaxExtraTag(documento.getMaxExtraTag());
                db.setOrdineExtraTag(documento.getOrdineExtraTag());

                for (int i = 1; i <= 10; i++) {
                    db.setExtraTag(i, documento.getExtraTag(i));
                    db.setExtraTagRef(i, documento.getExtraTagRef(i));
                }

                db.setIdRoot(documento.getIdRoot());
                db.setIdType(documento.getIdType());
                db.setIdParent(documento.getIdParent());

                db.setGalleryString(documento.getGalleryString());

                db.setAnno(documento.getAnno());
                db.setMese(documento.getMese());

                db.setLog1(documento.getLog1());
                db.setLog2(documento.getLog2());
                db.setLog3(documento.getLog3());

                db.setTemp1(documento.getTemp1());

                db.setModificato(now);
                db.setModificatoDa(operatore);
                parseDataVisualizzata(db);

                contentService.saveContent(db);
                log.info("Contenuto salvato: id={}", db.getId());

                String allegatoString = request.getParameter("allegatoString");
                if (allegatoString != null && !allegatoString.isBlank()) {
                    for (String part : allegatoString.split(";")) {
                        part = part.trim().replaceAll("[()]", "");
                        if (!part.isEmpty()) {
                            try {
                                Integer idAllegato = Integer.parseInt(part);
                                allegatoService.collegaAllegato(
                                        Integer.parseInt(db.getId()),
                                        idAllegato,
                                        operatore,
                                        config.getAmministratore().getId().toString()
                                );
                            } catch (NumberFormatException e) {
                                log.warn("allegatoString: id non valido '{}'", part);
                            }
                        }
                    }
                }

                String scollegaString = request.getParameter("scollegaString");
                if (scollegaString != null && !scollegaString.isBlank()) {
                    for (String part : scollegaString.split(";")) {
                        part = part.trim().replaceAll("[()]", "");
                        if (!part.isEmpty()) {
                            try {
                                allegatoService.scollegaAllegato(Integer.parseInt(part));
                            } catch (NumberFormatException e) {
                                log.warn("scollegaString: id non valido '{}'", part);
                            }
                        }
                    }
                }

                // --- AGGIUNTA DA INSERIRE QUI ---
                if ("1".equals(documento.getTemp1())) {
                    Integer idRoot = parseIntSafe(db.getIdRoot());
                    if (idRoot != null) {
                        // Recupera tutti i contenuti della stessa sezione (es. la 805)
                        List<DatiBase> fratelli = contentService.findContentsBySection(idSite, idRoot);

                        for (DatiBase f : fratelli) {
                            // Salta il documento corrente per non sovrascriverlo inutilmente
                            if (f.getId().equals(db.getId())) continue;

                            // 1. Copia il TAG principale (es: "zumba,")
                            f.setTag(db.getTag());

                            // 2. Copia i 10 ExtraTag e i loro riferimenti (Sezioni Associate)
                            for (int i = 1; i <= 10; i++) {
                                f.setExtraTag(i, db.getExtraTag(i));
                                f.setExtraTagRef(i, db.getExtraTagRef(i));
                            }

                            // Salva il "fratello" aggiornato
                            contentService.saveContent(f);
                        }
                        log.info("Applicazione massiva completata con successo per la sezione {}", idRoot);
                    }
                }
                // =================================================================

                return "redirect:/admin/contenuti/" + db.getId();
            }

        } catch (Exception e) {
            log.error("Errore save contenuto", e);

            String idSite = String.valueOf(config.getIdSito());
            Section sezione = buildFallbackSezione(parseIntSafe(documento.getIdRoot()), idSite);
            documento.setSection(sezione);

            model.addAttribute("error", "Errore nel salvataggio: " + e.getMessage());
            model.addAttribute("content", documento);
            model.addAttribute("post", documento);
            model.addAttribute("sezione", sezione);
            model.addAttribute("elencoSezioni", contentService.findAllSections(idSite));
            model.addAttribute("config", config);

            return ViewUtils.resolveProtectedTemplate("cms/dettaglioContenutoTemplate");
        }
    }

    // =====================================================================
    // DELETE SINGOLO
    // =====================================================================
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Integer id,
                         HttpServletRequest request) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        String idSite = String.valueOf(config.getIdSito());
        String redirectTo = "/admin/sezioni";

        try {
            // Recupero la sezione padre PRIMA di cancellare
            DatiBase content = contentService.findContentById(id, idSite).orElse(null);
            if (content != null && content.getIdRoot() != null && !content.getIdRoot().isBlank()) {
                redirectTo = "/admin/sezioni/" + content.getIdRoot() + "/preview";
            }

            contentService.deleteContent(id);
            log.info("Contenuto eliminato: id={}", id);

        } catch (Exception e) {
            log.error("Errore delete contenuto id={}", id, e);
        }

        return "redirect:" + redirectTo;
    }

    // =====================================================================
    // DELETE MULTIPLO (AJAX)
    // =====================================================================

    @PostMapping("/deleteMultiplo")
    @ResponseBody
    public ResponseEntity<String> deleteMultiplo(
            @RequestParam(value = "delContID", required = false) List<Integer> ids,
            @RequestParam(value = "delContID[]", required = false) List<Integer> ids2,
            HttpServletRequest request) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return ResponseEntity.status(401).body("KO");

        List<Integer> lista = (ids != null && !ids.isEmpty()) ? ids : ids2;
        if (lista == null || lista.isEmpty()) return ResponseEntity.badRequest().body("KO");

        try {
            for (Integer id : lista) {
                contentService.deleteContent(id);
            }
            log.info("Eliminati {} contenuti", lista.size());
            return ResponseEntity.ok("OK");

        } catch (Exception e) {
            log.error("Errore deleteMultiplo contenuti", e);
            return ResponseEntity.ok("KO");
        }
    }

    // =====================================================================
    // DUPLICATE
    // =====================================================================
    @GetMapping("/{id}/duplicate")
    public String duplicate(
            @PathVariable Integer id,
            HttpServletRequest request, Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        String idSite = String.valueOf(config.getIdSito());

        try {
            DatiBase original = contentService.findContentById(id, idSite)
                    .orElseThrow(() -> new RuntimeException("Contenuto non trovato"));

            DatiBase copia = new DatiBase();
            copia.setId(null);
            copia.setIdSite(idSite);
            copia.setIdRoot(original.getIdRoot());
            copia.setIdParent(original.getIdParent());
            copia.setIdType(original.getIdType());
            copia.setTitolo("Copia di " + original.getTitolo());
            copia.setRiassunto(original.getRiassunto());
            copia.setTesto(original.getTesto());
            copia.setStato("0");
            copia.setData(original.getData());
            copia.setDataVisualizzata(original.getDataVisualizzata());
            copia.setAnno(original.getAnno());
            copia.setMese(original.getMese());
            copia.setTag(original.getTag());
            copia.setDataSql(LocalDate.now());
            copia.setGalleryString("");

            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy - HH:mm"));
            copia.setCreato(now);
            copia.setCreatoDa(config.getAmministratore().getNomeCompleto());

            Integer idRoot = parseIntSafe(original.getIdRoot());
            Section sezione = (idRoot != null)
                    ? contentService.findSectionById(idRoot, idSite).orElse(null)
                    : null;
            sezione = normalizzaSezione(sezione, idRoot);
            copia.setSection(sezione);

            model.addAttribute("content", copia);
            model.addAttribute("post", copia);
            model.addAttribute("sezione", sezione);
            model.addAttribute("elencoSezioni", contentService.findAllSections(idSite));
            model.addAttribute("config", config);

        } catch (Exception e) {
            log.error("Errore duplicate contenuto id={}", id, e);
            return "redirect:/admin/sezioni";
        }

        return ViewUtils.resolveProtectedTemplate("cms/dettaglioContenutoTemplate");
    }

    // =====================================================================
    // RICLASSIFICA (AJAX)
    // =====================================================================
    @PostMapping("/riclassifica")
    @ResponseBody
    public ResponseEntity<String> riclassificaDocumenti(
            @RequestParam("ricContID") List<Integer> ids,
            @RequestParam("id") Integer nuovaSezioneId,
            HttpServletRequest request) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return ResponseEntity.status(401).body("KO");

        String idSite = String.valueOf(config.getIdSito());

        try {
            Section nuovaSezione = contentService.findSectionById(nuovaSezioneId, idSite)
                    .orElseThrow(() -> new RuntimeException("Sezione non trovata"));

            for (Integer contId : ids) {
                contentService.findDatiBaseById(contId).ifPresent(doc -> {
                    doc.setIdRoot(nuovaSezione.getId().toString());
                    doc.setIdType(nuovaSezione.getIdType() != null
                            ? nuovaSezione.getIdType().toString() : "0");
                    contentService.saveContent(doc);
                });
            }

            log.info("Riclassificati {} contenuti → sezione {}", ids.size(), nuovaSezioneId);
            return ResponseEntity.ok("OK");

        } catch (Exception e) {
            log.error("Errore riclassificaDocumenti", e);
            return ResponseEntity.ok("KO");
        }
    }

    // =====================================================================
    // ORDINA (AJAX)
    // =====================================================================
    @PostMapping("/ordina")
    @ResponseBody
    public ResponseEntity<String> ordinaDocumenti(
            @RequestParam("ordine") String ordine,
            @RequestParam(value = "ordineBase", defaultValue = "") String ordineBase,
            HttpServletRequest request) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return ResponseEntity.status(401).body("KO");

        try {
            if (!ordine.equals(ordineBase) && !ordine.isEmpty()) {
                String[] ids = ordine.split(";");
                int position = 1;

                for (String idStr : ids) {
                    if (idStr == null || idStr.isBlank()) continue;
                    int contId = Integer.parseInt(idStr.trim());
                    int pos = position;

                    contentService.findDatiBaseById(contId).ifPresent(doc -> {
                        doc.setPosition(String.valueOf(pos));
                        contentService.saveContent(doc);
                    });

                    position++;
                }
            }
            return ResponseEntity.ok("OK");

        } catch (Exception e) {
            log.error("Errore ordinaDocumenti", e);
            return ResponseEntity.ok("KO");
        }
    }

    @PostMapping("/{id}/riclassifica")
    @ResponseBody
    public ResponseEntity<Map<String, String>> riclassifica(
            @PathVariable Integer id,
            @RequestParam String nuovoRootId,
            HttpServletRequest request) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);

        Map<String, String> result = new java.util.HashMap<>();

        if (!config.isLogged()) {
            result.put("esito", "KO");
            result.put("messaggio", "Sessione scaduta.");
            return ResponseEntity.ok(result);
        }

        try {
            // Aggiorna id_root del contenuto
            contentService.updateContentRoot(id, String.valueOf(nuovoRootId));

            log.info("Contenuto {} spostato sotto root {}", id, nuovoRootId);
            result.put("esito", "OK");
            result.put("messaggio", "Contenuto spostato con successo.");

        } catch (Exception e) {
            log.error("Errore riclassifica contenuto id={} nuovoRoot={}", id, nuovoRootId, e);
            result.put("esito", "KO");
            result.put("messaggio", "Errore durante lo spostamento: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    // =====================================================================
    // UTILITY PRIVATI
    // =====================================================================

    private Section buildFallbackSezione(Integer idRoot, String idSite) {
        if (idRoot != null) {
            try {
                Section s = contentService.findSectionById(idRoot, idSite).orElse(null);
                if (s != null) {
                    if (s.getSectionType() == null) s.setSectionType(new SectionType());
                    return s;
                }
            } catch (Exception ignored) {}
        }
        Section fallback = new Section();
        fallback.setId(idRoot != null ? idRoot : 0);
        fallback.setSectionType(new SectionType());
        return fallback;
    }

    private void caricaDocumentiCorrelati(DatiBase documento, Section sezione, String idSite) {
        try {
            String l15 = sezione.getL15();
            if (l15 != null && !l15.isBlank()) {
                // Prende solo il primo ID (prima della virgola)
                String firstId = l15.split(",")[0].trim();
                Integer idRootCorrelati = parseIntSafe(firstId);
                if (idRootCorrelati != null && idRootCorrelati > 0) {
                    documento.setDocCorrelati1(
                            contentService.findContentsBySection(idSite, idRootCorrelati));
                }
            }
            String l11 = sezione.getL11();
            if (l11 != null && !l11.isBlank()) {
                String firstId = l11.split(",")[0].trim();
                Integer idRootCorrelati2 = parseIntSafe(firstId);
                if (idRootCorrelati2 != null && idRootCorrelati2 > 0) {
                    documento.setDocCorrelati2(
                            contentService.findContentsBySection(idSite, idRootCorrelati2));
                }
            }
        } catch (Exception e) {
            log.warn("Errore caricamento documenti correlati: {}", e.getMessage());
        }
    }

    private void parseDataVisualizzata(DatiBase documento) {
        try {
            String dataVis = documento.getDataVisualizzata();
            if (dataVis == null || dataVis.isEmpty()) {
                documento.setAnno(LocalDate.now().getYear());
                documento.setMese("gennaio");
            } else {
                int comma = dataVis.indexOf(',');
                if (comma > 0) {
                    String annoStr = dataVis.substring(comma + 2).trim();
                    documento.setAnno(Integer.parseInt(annoStr));
                    String meseStr = dataVis.substring(3, comma).trim();
                    documento.setMese(meseStr.toLowerCase());
                }
            }
        } catch (Exception e) {
            log.warn("Errore parsing dataVisualizzata: {}", documento.getDataVisualizzata());
            documento.setAnno(LocalDate.now().getYear());
            documento.setMese("gennaio");
        }
    }

    private Integer parseIntSafe(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}