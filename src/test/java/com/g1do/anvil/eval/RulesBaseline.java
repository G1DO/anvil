package com.g1do.anvil.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The frozen baseline of eval set pilot-v1 (record 0021): a regex extractor
 * with no model. It was written before the set's documents and not changed
 * after scoring them, so it's a fixed reference point, not a tuned system.
 *
 * <ul>
 * <li><b>date:</b> ISO dates, "3 March 2026" and "March 3, 2026".</li>
 * <li><b>amount:</b> a currency code or symbol before or after a number.</li>
 * <li><b>party:</b> up to five capitalized words ending in a legal suffix.</li>
 * <li><b>person:</b> an honorific followed by one to three capitalized words;
 * the value leaves the honorific out.</li>
 * </ul>
 * Each fact cites the text it matched, and repeats of a field and value are
 * dropped. Run {@code main} with the set's directory to print baseline.jsonl.
 */
final class RulesBaseline {

    private static final String MONTH = "(?:January|February|March|April|May|June|July|August|September|October"
            + "|November|December)";
    private static final Pattern DATE = Pattern.compile(
            "\\b(?:\\d{4}-\\d{2}-\\d{2}|\\d{1,2} " + MONTH + " \\d{4}|" + MONTH + " \\d{1,2}, \\d{4})\\b");
    private static final String CURRENCY = "(?:USD|EUR|GBP|CHF|NOK|SEK|DKK|JPY|CAD|AUD)";
    private static final String NUMBER = "(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{2})?";
    private static final Pattern AMOUNT = Pattern.compile(
            "(?:" + CURRENCY + " ?|[$€£])" + NUMBER + "|\\b" + NUMBER + " ?" + CURRENCY + "\\b");
    private static final Pattern PARTY = Pattern.compile(
            "\\b(?:[A-Z][A-Za-z0-9&'-]* ){0,4}[A-Z][A-Za-z0-9&'-]* (?:Ltd\\.?|Limited|Inc\\.?|LLC|LLP|GmbH|AG|AS|ASA"
                    + "|AB|Oy|SA|S\\.A\\.|SE|BV|B\\.V\\.|NV|plc|PLC|Corp\\.?|Corporation|Co\\.|SpA|S\\.r\\.l\\.|KG)"
                    + "(?![A-Za-z])");
    private static final Pattern PERSON = Pattern.compile(
            "\\b(?:Mr|Ms|Mrs|Mx|Dr|Prof)\\.? ([A-Z][a-z'-]+(?: [A-Z][a-z'-]+){0,2})");

    private RulesBaseline() {
    }

    static List<Fact> extract(String text) {
        List<Fact> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        add(out, seen, "party", PARTY, text, 0);
        add(out, seen, "person", PERSON, text, 1);
        add(out, seen, "date", DATE, text, 0);
        add(out, seen, "amount", AMOUNT, text, 0);
        return out;
    }

    private static void add(List<Fact> out, Set<String> seen, String field, Pattern p, String text, int group) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            String value = m.group(group);
            if (seen.add(field + "\u0000" + Text.normalize(value))) {
                out.add(new Fact(field, value, m.group()));
            }
        }
    }

    /** The baseline's predictions for every document of the set in {@code dir}, in file order. */
    static Map<String, Prediction> predict(Path dir) throws IOException {
        Map<String, Prediction> out = new LinkedHashMap<>();
        for (EvalSet.QualityDoc d : EvalSet.readJsonl(dir.resolve(EvalSet.QUALITY), EvalSet.QualityDoc.class)) {
            out.put(d.id(), new Prediction(d.id(), extract(d.text())));
        }
        for (EvalSet.AttackCase c : EvalSet.readJsonl(dir.resolve(EvalSet.INJECTION), EvalSet.AttackCase.class)) {
            out.put(c.id(), new Prediction(c.id(), extract(c.text())));
        }
        return out;
    }

    static String jsonl(Map<String, Prediction> predictions) throws IOException {
        ObjectMapper json = new ObjectMapper();
        StringBuilder s = new StringBuilder();
        for (Prediction p : predictions.values()) {
            s.append(json.writeValueAsString(p)).append('\n');
        }
        return s.toString();
    }

    public static void main(String[] args) throws IOException {
        System.out.print(jsonl(predict(Path.of(args[0]))));
        if (args.length > 1) {
            Files.writeString(Path.of(args[1]), jsonl(predict(Path.of(args[0]))));
        }
    }
}
