package com.jplatform_studiodomino.cms.admin.controller;

import com.jplatform_studiodomino.cms.entity.Allegato;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin/sezioni")
@RequiredArgsConstructor
@Slf4j
public class SezioniController {

    private final ConfigurazioneService configurazioneService;
    private final ContentService contentService;
    private final ImagesService imagesService;
    private final AllegatoService allegatoService;

    @GetMapping
    public String elencoSezioni(HttpServletRequest request, Model model) {
        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        try {
            String idSite = String.valueOf(config.getIdSito());
            String userGroups = getSelettoreGruppo(config);

            List<Section> elencoSezioni = loadRootSectionsComplete(idSite);
            if (!userGroups.isEmpty()) {
                elencoSezioni = filterTreeByGroups(elencoSezioni, userGroups);
            }

            model.addAttribute("elencoSezioni", elencoSezioni);
            model.addAttribute("previewSezione", null);
            model.addAttribute("sectionTypes", contentService.findAllSectionTypes());
            model.addAttribute("config", config);

        } catch (Exception e) {
            log.error("Errore elencoSezioni", e);
        }

        return ViewUtils.resolveProtectedTemplate("cms/front/elencoSezioni");
    }

    @GetMapping({"/new", "/new/{idParent}"})
    public String newForm(
            @PathVariable(required = false) String idParent,
            @RequestParam(value = "id_parent", required = false) String idParentParam,
            @RequestParam(value = "groupid", defaultValue = "0") String groupId,
            HttpServletRequest request, Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        try {
            String idSite = String.valueOf(config.getIdSito());
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));

            String parentId = idParent != null ? idParent : (idParentParam != null ? idParentParam : "0");

            Section section = new Section();
            section.setId(null);
            section.setIdParent(parentId);
            section.setIdGruppo(groupId);
            section.setData(today);
            section.setSectionType(new SectionType());

            model.addAttribute("section", section);
            populateDetailModel(model, config, idSite);

        } catch (Exception e) {
            log.error("Errore newForm sezione", e);
        }

        return ViewUtils.resolveProtectedTemplate("cms/dettaglioSezioneTemplate");
    }

    @GetMapping("/{id}")
    public String open(@PathVariable Integer id,
                       HttpServletRequest request, Model model,
                       RedirectAttributes redirectAttributes) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        try {
            String idSite = String.valueOf(config.getIdSito());

            Section section = contentService.findSectionById(id, idSite)
                    .orElseThrow(() -> new RuntimeException("Sezione non trovata: " + id));

            if (section.getSectionType() == null && section.getIdType() != null) {
                SectionType st = contentService.getSectionTypeById(section.getIdType());
                section.setSectionType(st != null ? st : new SectionType());
            } else if (section.getSectionType() == null) {
                section.setSectionType(new SectionType());
            }

            model.addAttribute("section", section);
            populateDetailModel(model, config, idSite);

            String galleryString = section.getGalleryString();
            log.info("galleryString sezione {}: '{}'", section.getId(), galleryString);

            if (galleryString != null && !galleryString.isEmpty()) {
                // Normalizza formato: converti "80;86" in "(80);(86);"
                StringBuilder normalized = new StringBuilder();
                List<Images> gallery = new ArrayList<>();

                String[] parts = galleryString.split(";");
                for (String part : parts) {
                    part = part.trim().replace("(", "").replace(")", "");
                    if (!part.isEmpty()) {
                        try {
                            Integer imgId = Integer.parseInt(part);
                            normalized.append("(").append(imgId).append(");");
                            imagesService.findById(imgId).ifPresent(img -> {
                                log.info("Immagine trovata: id={}, path={}", img.getId(), img.getFullpath());
                                gallery.add(img);
                            });
                        } catch (NumberFormatException ignored) {}
                    }
                }

                section.setGalleryString(normalized.toString());
                section.setGallery(gallery);
            }

            if (section.getId() != null) {
                List<Allegato> allegati = allegatoService.findAllegatiByDocumento(section.getId());
                section.setAllegati(allegati);
            }

        } catch (Exception e) {
            log.error("Errore open sezione id={}", id, e);
            redirectAttributes.addFlashAttribute("errorMessage", "Sezione non trovata");
            return "redirect:/admin/sezioni";
        }

        return ViewUtils.resolveProtectedTemplate("cms/dettaglioSezioneTemplate");
    }

    @GetMapping("/{id}/duplicate")
    public String duplicate(
            @PathVariable Integer id,
            HttpServletRequest request, Model model) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        String idSite = String.valueOf(config.getIdSito());

        try {
            Section original = contentService.findSectionById(id, idSite)
                    .orElseThrow(() -> new RuntimeException("Sezione non trovata"));

            Section copia = new Section();
            copia.setId(null);
            copia.setIdSite(idSite);
            copia.setIdRoot(original.getIdRoot());
            copia.setIdParent(original.getIdParent());
            copia.setIdType(original.getIdType());
            copia.setSectionType(original.getSectionType() != null ? original.getSectionType() : new SectionType());

            copia.setTitolo("Copia di " + original.getTitolo());
            copia.setRiassunto(original.getRiassunto());
            copia.setTesto(original.getTesto());
            copia.setTag(original.getTag());
            copia.setData(original.getData());
            copia.setDataVisualizzata(original.getDataVisualizzata());
            copia.setDataSql(LocalDate.now());
            copia.setStato("0");
            copia.setPrivato(original.getPrivato());
            copia.setMenu1(original.getMenu1()); copia.setMenu2(original.getMenu2());
            copia.setMenu3(original.getMenu3()); copia.setMenu4(original.getMenu4());
            copia.setMenu5(original.getMenu5());
            copia.setS1(original.getS1()); copia.setS2(original.getS2()); copia.setS3(original.getS3());
            copia.setInfo1(original.getInfo1()); copia.setInfo2(original.getInfo2());
            copia.setInfo3(original.getInfo3()); copia.setInfo4(original.getInfo4());
            copia.setInfo5(original.getInfo5());
            copia.setVarchar1(original.getVarchar1()); copia.setVarchar2(original.getVarchar2());
            copia.setVarchar3(original.getVarchar3()); copia.setVarchar4(original.getVarchar4());
            copia.setVarchar5(original.getVarchar5()); copia.setVarchar6(original.getVarchar6());
            copia.setVarchar7(original.getVarchar7()); copia.setVarchar8(original.getVarchar8());
            copia.setVarchar9(original.getVarchar9()); copia.setVarchar10(original.getVarchar10());
            copia.setText1(original.getText1()); copia.setText2(original.getText2());
            copia.setText3(original.getText3()); copia.setText4(original.getText4());
            copia.setText5(original.getText5()); copia.setText6(original.getText6());
            copia.setText7(original.getText7()); copia.setText8(original.getText8());
            copia.setText9(original.getText9()); copia.setText10(original.getText10());
            copia.setL11(original.getL11()); copia.setL15(original.getL15());
            copia.setExtraTagRef1(original.getExtraTagRef1()); copia.setExtraTagRef2(original.getExtraTagRef2());
            copia.setExtraTagRef3(original.getExtraTagRef3()); copia.setExtraTagRef4(original.getExtraTagRef4());
            copia.setExtraTagRef5(original.getExtraTagRef5()); copia.setExtraTagRef6(original.getExtraTagRef6());
            copia.setExtraTagRef7(original.getExtraTagRef7()); copia.setExtraTagRef8(original.getExtraTagRef8());
            copia.setExtraTagRef9(original.getExtraTagRef9()); copia.setExtraTagRef10(original.getExtraTagRef10());
            copia.setExtraTag(1, original.getExtraTag(1)); copia.setExtraTag(2, original.getExtraTag(2));
            copia.setExtraTag(3, original.getExtraTag(3)); copia.setExtraTag(4, original.getExtraTag(4));
            copia.setExtraTag(5, original.getExtraTag(5)); copia.setExtraTag(6, original.getExtraTag(6));
            copia.setExtraTag(7, original.getExtraTag(7)); copia.setExtraTag(8, original.getExtraTag(8));
            copia.setExtraTag(9, original.getExtraTag(9)); copia.setExtraTag(10, original.getExtraTag(10));
            copia.setOrdineExtraTag(original.getOrdineExtraTag());
            copia.setMaxExtraTag(original.getMaxExtraTag());
            copia.setRegolaExtraTag1(original.getRegolaExtraTag1());
            copia.setRegolaExtraTag2(original.getRegolaExtraTag2());
            copia.setLabel(original.getLabel());
            copia.setAnno(original.getAnno());
            copia.setMese(original.getMese());
            copia.setClick(0);

            model.addAttribute("section", copia);
            populateDetailModel(model, config, idSite);

        } catch (Exception e) {
            log.error("Errore duplicate sezione id={}", id, e);
            return "redirect:/admin/sezioni";
        }

        return ViewUtils.resolveProtectedTemplate("cms/dettaglioSezioneTemplate");
    }

    @GetMapping("/{id}/preview")
    public String preview(@PathVariable Integer id,
                          HttpServletRequest request,
                          Model model,
                          RedirectAttributes redirectAttributes) {

        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        try {
            String idSite = String.valueOf(config.getIdSito());

            Section previewSezione = contentService.findSectionCompleteAdmin(id, idSite)
                    .orElseThrow(() -> new RuntimeException("Sezione non trovata: " + id));

            if (previewSezione.getSectionType() == null) {
                previewSezione.setSectionType(new SectionType());
            }

            String userGroups = getSelettoreGruppo(config);
            List<Section> elencoSezioni = loadRootSectionsComplete(idSite);
            if (!userGroups.isEmpty()) {
                elencoSezioni = filterTreeByGroups(elencoSezioni, userGroups);
            }

            model.addAttribute("elencoSezioni", elencoSezioni);
            model.addAttribute("previewSezione", previewSezione);
            model.addAttribute("sectionTypes", contentService.findAllSectionTypes());
            model.addAttribute("config", config);

        } catch (Exception e) {
            log.error("Errore preview sezione id={}", id, e);
            redirectAttributes.addFlashAttribute("errorMessage", "Sezione non trovata o errore nel caricamento");
            return "redirect:/admin/sezioni";
        }

        return ViewUtils.resolveProtectedTemplate("cms/front/previewSezioni");
    }

    @CacheEvict(value = {"menuPubblico", "homePage", "tagCloud", "contenutiFront"}, allEntries = true)
    @PostMapping("/save")
    public String save(@ModelAttribute Section form, HttpServletRequest request, Model model) {
        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        try {
            String idSite = String.valueOf(config.getIdSito());
            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy - HH:mm"));
            String operatore = config.getAmministratore().getNomeCompleto();

            Section db;
            boolean isNew = form.getId() == null || form.getId() <= 0;

            if (isNew) {
                db = form;
                db.setIdSite(idSite);
                db.setIdRoot(null); // Di solito le sezioni hanno idRoot -1
                db.setCreato(now);
                db.setCreatoDa(operatore);
                if (db.getClick() == null) db.setClick(0);
                if (db.getPosition() == null) db.setPosition(0);
            } else {
                db = contentService.findSectionById(form.getId(), idSite)
                        .orElseThrow(() -> new RuntimeException("Sezione non trovata"));

                // MERGE CAMPI BASE
                db.setTitolo(form.getTitolo());
                db.setStato(form.getStato());
                db.setIdType(form.getIdType());
                db.setDataVisualizzata(form.getDataVisualizzata());
                db.setData(form.getData());
                db.setTag(form.getTag());
                db.setRiassunto(form.getRiassunto());
                db.setTesto(form.getTesto());

                // MERGE CAMPI MULTIPLI (Varchar 1-10)
                db.setVarchar1(form.getVarchar1()); db.setVarchar2(form.getVarchar2());
                db.setVarchar3(form.getVarchar3()); db.setVarchar4(form.getVarchar4());
                db.setVarchar5(form.getVarchar5()); db.setVarchar6(form.getVarchar6());
                db.setVarchar7(form.getVarchar7()); db.setVarchar8(form.getVarchar8());
                db.setVarchar9(form.getVarchar9()); db.setVarchar10(form.getVarchar10());

                // MERGE CAMPI TESTO (Text 1-10)
                db.setText1(form.getText1()); db.setText2(form.getText2());
                db.setText3(form.getText3()); db.setText4(form.getText4());
                db.setText5(form.getText5()); db.setText6(form.getText6());
                db.setText7(form.getText7()); db.setText8(form.getText8());
                db.setText9(form.getText9()); db.setText10(form.getText10());

                // MERGE EXTRA TAG REF
                db.setExtraTagRef1(form.getExtraTagRef1()); db.setExtraTagRef2(form.getExtraTagRef2());
                db.setExtraTagRef3(form.getExtraTagRef3()); db.setExtraTagRef4(form.getExtraTagRef4());
                db.setExtraTagRef5(form.getExtraTagRef5());

                // MERGE INFO & MENU
                db.setInfo1(form.getInfo1()); db.setInfo2(form.getInfo2());
                db.setInfo3(form.getInfo3()); db.setInfo4(form.getInfo4());
                db.setInfo5(form.getInfo5());
                db.setMenu1(form.getMenu1()); db.setMenu2(form.getMenu2());
                db.setMenu3(form.getMenu3()); db.setMenu4(form.getMenu4());
                db.setMenu5(form.getMenu5());
                db.setS1(form.getS1()); db.setS2(form.getS2()); db.setS3(form.getS3());
                db.setL11(form.getL11());
                db.setL15(form.getL15());

                db.setModificato(now);
                db.setModificatoDa(operatore);
                db.setGalleryString(form.getGalleryString());
                if (form.getPosition() != null) db.setPosition(form.getPosition());
            }

            // Validazioni e Parsing finali
            if (db.getLabel() == null || db.getLabel().isBlank()) db.setLabel(db.getTitolo());
            if (db.getDataSql() == null) db.setDataSql(LocalDate.now());
            parseDataVisualizzata(db);

            Section saved = contentService.saveSection(db);
            log.info("Sezione salvata con id: {}", saved.getId());
            String allegatoString = request.getParameter("allegatoString");
            log.info("allegatoString ricevuto: '{}'", allegatoString);
            if (allegatoString != null && !allegatoString.isBlank()) {
                for (String part : allegatoString.split(";")) {
                    part = part.trim().replaceAll("[()]", "");
                    if (!part.isEmpty()) {
                        try {
                            Integer idAllegato = Integer.parseInt(part);
                            allegatoService.collegaAllegato(
                                    saved.getId(),
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

            return "redirect:/admin/sezioni/" + saved.getId();

        } catch (Exception e) {
            log.error("Errore save sezione", e);
            model.addAttribute("error", "Errore: " + e.getMessage());

            if (form.getSectionType() == null && form.getIdType() != null) {
                SectionType st = contentService.getSectionTypeById(form.getIdType());
                form.setSectionType(st != null ? st : new SectionType());
            } else if (form.getSectionType() == null) {
                form.setSectionType(new SectionType());
            }
            model.addAttribute("section", form);
            populateDetailModel(model, config, String.valueOf(config.getIdSito()));
            return ViewUtils.resolveProtectedTemplate("cms/dettaglioSezioneTemplate");
        }
    }

    /**
     * Autosave dalla preview: aggiorna SOLO i campi presenti nel form della preview
     * (titolo, data, stato, tipo). Non passa da save() per non azzerare testo, menu, varchar ecc.
     */
    @PostMapping("/{id}/autosave")
    @ResponseBody
    public ResponseEntity<Map<String, String>> autosave(
            @PathVariable Integer id,
            @RequestParam(required = false) String titolo,
            @RequestParam(required = false) String data,
            @RequestParam(required = false) String stato,
            @RequestParam(required = false) Integer idType,
            HttpServletRequest request) {

        Map<String, String> result = new java.util.HashMap<>();
        Configurazione config = configurazioneService.getConfig(request.getSession());

        if (!config.isLogged()) {
            result.put("esito", "KO");
            result.put("messaggio", "Sessione scaduta.");
            return ResponseEntity.status(401).body(result);
        }
        if (titolo == null || titolo.isBlank()) {
            result.put("esito", "KO");
            result.put("messaggio", "Il titolo è obbligatorio.");
            return ResponseEntity.badRequest().body(result);
        }
        if (stato == null || stato.isBlank() || idType == null) {
            result.put("esito", "KO");
            result.put("messaggio", "Seleziona stato e tipo sezione.");
            return ResponseEntity.badRequest().body(result);
        }

        try {
            String idSite = String.valueOf(config.getIdSito());
            Section db = contentService.findSectionById(id, idSite).orElse(null);
            if (db == null) {
                result.put("esito", "KO");
                result.put("messaggio", "Sezione non trovata.");
                return ResponseEntity.status(404).body(result);
            }

            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy - HH:mm"));
            String operatore = config.getAmministratore().getNomeCompleto();

            db.setTitolo(titolo.trim());
            db.setData(data);
            db.setStato(stato);
            db.setIdType(idType);
            db.setModificato(now);
            db.setModificatoDa(operatore);

            contentService.saveSection(db);

            result.put("esito", "OK");
            result.put("modificato", now);
            result.put("modificatoDa", operatore);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Errore autosave sezione id={}", id, e);
            result.put("esito", "KO");
            result.put("messaggio", "Errore durante il salvataggio.");
            return ResponseEntity.internalServerError().body(result);
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Integer id, HttpServletRequest request,
                         RedirectAttributes redirectAttributes) {
        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged()) return "redirect:/login";

        try {
            contentService.deleteSection(id);
            redirectAttributes.addFlashAttribute("successMessage", "Sezione eliminata con successo");
            return "redirect:/admin/sezioni";
        } catch (Exception e) {
            log.error("Errore durante la cancellazione della sezione id={}", id, e);
            redirectAttributes.addFlashAttribute("errorMessage", "Errore durante la cancellazione");
            return "redirect:/admin/sezioni";
        }
    }

    @PostMapping("/deleteMultiplo")
    @ResponseBody
    public ResponseEntity<String> deleteMultiplo(@RequestParam("delSezioniID") List<Integer> ids,
                                                 HttpServletRequest request) {
        HttpSession session = request.getSession();
        Configurazione config = configurazioneService.getConfig(session);
        if (!config.isLogged() || ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().body("Parametri non validi");
        }

        try {
            for (Integer id : ids) {
                contentService.deleteSection(id);
            }
            return ResponseEntity.ok("Cancellazione completata");
        } catch (Exception e) {
            log.error("Errore cancellazione multipla sezioni", e);
            return ResponseEntity.internalServerError().body("Errore durante la cancellazione");
        }
    }

    @PostMapping("/{id}/riclassifica")
    @ResponseBody
    public ResponseEntity<Map<String, String>> riclassifica(
            @PathVariable Integer id,
            @RequestParam String nuovoParentId,
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
            String idSite = String.valueOf(config.getIdSito());

            Section sezione = contentService.findSectionById(id, idSite)
                    .orElseThrow(() -> new RuntimeException("Sezione non trovata: " + id));

            // Aggiorna il parent
            sezione.setIdParent(nuovoParentId);

            contentService.saveSection(sezione);

            log.info("Sezione {} spostata sotto parent {}", id, nuovoParentId);
            result.put("esito", "OK");
            result.put("messaggio", "Sezione spostata con successo.");

        } catch (Exception e) {
            log.error("Errore riclassifica sezione id={} nuovoParent={}", id, nuovoParentId, e);
            result.put("esito", "KO");
            result.put("messaggio", "Errore durante lo spostamento: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    private void populateDetailModel(Model model, Configurazione config, String idSite) {
        try {
            List<Section> elencoSezioni = contentService.findRootSections(idSite);
            List<SectionType> sectionTypes = contentService.findAllSectionTypes();

            // L'UNICA RIGA AGGIUNTA: Carichiamo i gruppi dal service
            config.setGruppi(configurazioneService.getAllGruppi(idSite));

            model.addAttribute("elencoSezioni", elencoSezioni);
            model.addAttribute("elencoSezioniCompleto", elencoSezioni);
            model.addAttribute("sectionTypes", sectionTypes);
            model.addAttribute("config", config);
        } catch (Exception e) {
            log.error("Errore populateDetailModel", e);
        }
    }

    private String getSelettoreGruppo(Configurazione config) {
        String role1 = config.getAmministratore().getRole1();
        if ("s".equals(role1) || "a".equals(role1)) return "";
        String g = config.getAmministratore().getIdgruppi();
        return g != null ? g.trim() : "";
    }

    private List<Section> loadRootSectionsComplete(String idSite) {
        List<Section> root = contentService.findRootSections(idSite);
        List<Section> complete = new ArrayList<>();
        for (Section r : root) {
            try {
                Section full = contentService.findSectionCompleteAdmin(r.getId(), idSite).orElse(r);
                // findSectionCompleteAdmin popola solo il livello diretto delle sottosezioni (subaoo).
                // L'albero (previewSezioni.html) supporta 4 livelli (aoo/subaoo/subsubaoo/subsubsubaoo):
                // qui carichiamo ricorsivamente anche i due livelli successivi, altrimenti le
                // sottosezioni di 2° livello risultano con subsection=null e non mostrano il "+"
                // anche quando hanno davvero dei figli nel DB.
                if (full.getSubsection() != null) {
                    for (Section child : full.getSubsection()) {
                        populateSubsectionsRecursive(child, idSite, 2);
                    }
                }
                complete.add(full);
            } catch (Exception ex) {
                complete.add(r);
            }
        }
        return complete;
    }

    /**
     * Popola ricorsivamente il campo subsection di una sezione e dei suoi discendenti,
     * fino a "remainingDepth" livelli aggiuntivi (usato per completare l'albero del
     * "Titolario di classificazione" oltre al primo livello già caricato da
     * findSectionCompleteAdmin).
     */
    private void populateSubsectionsRecursive(Section section, String idSite, int remainingDepth) {
        if (section == null || section.getId() == null || remainingDepth <= 0) return;
        List<Section> children = contentService.findAllSubsections(idSite, section.getId().toString());
        section.setSubsection(children);
        for (Section child : children) {
            populateSubsectionsRecursive(child, idSite, remainingDepth - 1);
        }
    }

    private List<Section> filterTreeByGroups(List<Section> sections, String userGroups) {
        List<Section> out = new ArrayList<>();
        for (Section s : sections) {
            Section filtered = filterSingleSectionTree(s, userGroups);
            if (filtered != null) out.add(filtered);
        }
        return out;
    }

    private Section filterSingleSectionTree(Section s, String userGroups) {
        if (!canUserSeeSection(s, userGroups)) return null;
        if (s.getSubsection() != null && !s.getSubsection().isEmpty()) {
            List<Section> children = new ArrayList<>();
            for (Section c : s.getSubsection()) {
                Section childFiltered = filterSingleSectionTree(c, userGroups);
                if (childFiltered != null) children.add(childFiltered);
            }
            s.setSubsection(children);
        }
        return s;
    }

    private boolean canUserSeeSection(Section s, String userGroups) {
        String sectGroup = s.getIdGruppo();
        if (sectGroup == null || sectGroup.isBlank()) return true;
        if (userGroups == null || userGroups.isBlank()) return true;
        String normalized = userGroups.replace(",", ";");
        String[] ug = normalized.split(";");
        for (String g : ug) {
            String gg = g.trim();
            if (!gg.isEmpty() && sectGroup.contains(gg)) return true;
        }
        return false;
    }

    private void parseDataVisualizzata(Section section) {
        try {
            String dataVis = section.getDataVisualizzata();
            if (dataVis == null || dataVis.isEmpty()) {
                section.setAnno(LocalDate.now().getYear());
                section.setMese("gennaio");
            } else {
                int comma = dataVis.indexOf(',');
                if (comma > 0) {
                    String annoStr = dataVis.substring(comma + 2).trim();
                    section.setAnno(Integer.parseInt(annoStr));
                    String meseStr = dataVis.substring(3, comma).trim();
                    section.setMese(meseStr.toLowerCase());
                }
            }
        } catch (Exception e) {
            section.setAnno(LocalDate.now().getYear());
            section.setMese("gennaio");
        }
    }
}