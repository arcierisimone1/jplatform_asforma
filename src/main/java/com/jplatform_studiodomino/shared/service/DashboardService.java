package com.jplatform_studiodomino.shared.service;

import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Numeri delle card della dashboard del manager.
 * Query semplici con JdbcTemplate: sono solo conteggi, non serve passare dalle entity.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardService {

    private static final int MESI = 12;
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");

    // Le date di utenteesterno e allegati sono stringhe "dd/MM/yyyy - HH:mm"
    private static final String DATA_CREAZIONE_UTENTE = "STR_TO_DATE(LEFT(datacreazione, 10), '%d/%m/%Y')";
    private static final String DATA_INSERIMENTO_ALLEGATO = "STR_TO_DATE(LEFT(datainserimento, 10), '%d/%m/%Y')";

    private final JdbcTemplate jdbc;

    /** Una card: totale, quanti negli ultimi 30 giorni, serie mensile (12 valori, dal più vecchio). */
    @Value
    public static class StatCard {
        long totale;
        long ultimi30gg;
        List<Long> perMese;

        public String getSerie() {               // "3,0,5,..." per il grafico
            StringJoiner sj = new StringJoiner(",");
            perMese.forEach(n -> sj.add(String.valueOf(n)));
            return sj.toString();
        }
    }

    public Map<String, StatCard> getStatistiche(Integer idSito) {
        String sito = idSito != null ? String.valueOf(idSito) : "1";
        Map<String, StatCard> stats = new LinkedHashMap<>();

        stats.put("utenti", card("utenteesterno", "1 = 1",
                DATA_CREAZIONE_UTENTE));

        stats.put("contenuti", card("contents", "id_site = ? AND id_root <> -1",
                "datasql", sito));

        stats.put("sezioni", card("contents", "id_site = ? AND id_root = -1",
                "datasql", sito));

        stats.put("allegati", card("allegati", "1 = 1",
                DATA_INSERIMENTO_ALLEGATO));

        return stats;
    }

    // ----------------------------------------------------------------------

    private StatCard card(String tabella, String where, String espressioneData, Object... params) {
        try {
            String base = " FROM " + tabella + " WHERE " + where;
            LocalDate oggi = LocalDate.now();

            long totale = count("SELECT COUNT(*)" + base, params);

            long recenti = count("SELECT COUNT(*)" + base + " AND " + espressioneData + " >= ?",
                    append(params, oggi.minusDays(30)));

            LocalDate inizio = oggi.withDayOfMonth(1).minusMonths(MESI - 1);
            Map<String, Long> conteggi = new HashMap<>();
            jdbc.query(
                    "SELECT DATE_FORMAT(" + espressioneData + ", '%Y-%m') AS ym, COUNT(*) AS n"
                            + base + " AND " + espressioneData + " >= ? GROUP BY ym",
                    (RowCallbackHandler) rs -> conteggi.put(rs.getString("ym"), rs.getLong("n")),
                    append(params, inizio));

            List<Long> serie = new ArrayList<>(MESI);
            for (int i = 0; i < MESI; i++) {
                serie.add(conteggi.getOrDefault(inizio.plusMonths(i).format(YM), 0L));
            }
            return new StatCard(totale, recenti, serie);

        } catch (Exception e) {
            log.warn("Dashboard: errore statistiche su {}: {}", tabella, e.getMessage());
            return new StatCard(0, 0, new ArrayList<>(Collections.nCopies(MESI, 0L)));
        }
    }

    private long count(String sql, Object... params) {
        Long n = jdbc.queryForObject(sql, Long.class, params);
        return n != null ? n : 0;
    }

    private static Object[] append(Object[] params, Object valore) {
        Object[] out = Arrays.copyOf(params, params.length + 1);
        out[params.length] = valore;
        return out;
    }
}