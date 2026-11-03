package com.ownclaw.agent;

import com.ownclaw.agent.tools.Tool;
import com.ownclaw.agent.tools.ToolParam;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The skills that fit what a model asked for, best first: BM25 over each tool's words -- its name
 * split at underscores and case changes, its description, its parameters' names and descriptions.
 * <p>
 * For the local model running a task itself ({@code find_tools}, {@link ThinkingEngine#toolsFor}):
 * every tool's description, sent on its first step, was most of a 42,000-token prompt it reads at
 * about 90 tokens a second. Words, not embeddings: an embedding model is a second model on the
 * local GPU, which evicts the one running the task -- a reload of minutes, both ways.
 */
final class ToolFinder {

    /** Matches listed in one answer; the rest are pages the answer names. */
    static final int PAGE = 8;

    private ToolFinder() {}

    /**
     * One page of the matches, best first, and how many there are. Only tools sharing a word with
     * the query match.
     *
     * @param page from 1
     */
    record Page(List<Tool> tools, int total, int page) {
        int pages() {
            return (total + PAGE - 1) / PAGE;
        }
    }

    static Page find(Collection<Tool> tools, String query, int page) {
        List<String> asked = words(query);
        List<Tool> all = new ArrayList<>(tools);
        Map<Tool, List<String>> docs = new HashMap<>();
        double length = 0;
        for (Tool tool : all) {
            List<String> doc = words(text(tool));
            docs.put(tool, doc);
            length += doc.size();
        }
        double average = all.isEmpty() ? 1 : length / all.size();
        Map<String, Integer> holding = new HashMap<>();
        for (String word : Set.copyOf(asked)) {
            holding.put(word, (int) docs.values().stream().filter(d -> d.contains(word)).count());
        }

        Map<Tool, Double> scores = new HashMap<>();
        for (Tool tool : all) {
            double score = score(docs.get(tool), asked, holding, all.size(), average);
            if (score > 0) scores.put(tool, score);
        }
        List<Tool> ranked = scores.keySet().stream()
                .sorted(Comparator.comparingDouble((Tool t) -> -scores.get(t)).thenComparing(Tool::name))
                .toList();
        // In long: a page asked for as 1e9 overflows an int.
        int from = (int) Math.min((Math.max(page, 1) - 1L) * PAGE, ranked.size());
        return new Page(ranked.subList(from, Math.min(from + PAGE, ranked.size())), ranked.size(), Math.max(page, 1));
    }

    /** BM25 with k1 1.2 and b 0.75. */
    private static double score(List<String> doc, List<String> asked, Map<String, Integer> holding, int n,
                                double average) {
        double score = 0;
        for (String word : Set.copyOf(asked)) {
            int tf = (int) doc.stream().filter(word::equals).count();
            if (tf == 0) continue;
            int df = holding.get(word);
            double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
            score += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * doc.size() / average));
        }
        return score;
    }

    /** What a tool is searched by: its name, description and parameters. */
    private static String text(Tool tool) {
        var sb = new StringBuilder(tool.name()).append(' ').append(tool.description());
        Map<String, ToolParam> params = tool.inputSchema();
        if (params != null) {
            params.forEach((name, param) -> sb.append(' ').append(name).append(' ')
                    .append(param == null || param.description() == null ? "" : param.description()));
        }
        return sb.toString();
    }

    private static final Set<String> STOP = Set.of("a", "an", "and", "are", "as", "at", "be", "by", "for",
            "from", "in", "into", "is", "it", "its", "of", "on", "or", "that", "the", "this", "to", "with");

    /**
     * The words of a text, lowercased, the commonest English words and single letters left out,
     * a plural made singular. A word written in parts is also its parts: "WiFi" is wifi, wi and
     * fi, "e-mail" is email, e and mail, so "wifi" finds "WiFi" and "OpenWrt" finds openwrt_run.
     * An underscore separates words: a skill's name is the words it is made of.
     */
    static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String written : text.split("[^\\p{L}\\p{N}-]+")) {
            String whole = written.replace("-", "");
            add(out, whole);
            String[] parts = written.replaceAll("(\\p{Ll})(\\p{Lu})", "$1 $2").split("[ -]+");
            if (parts.length > 1) for (String part : parts) add(out, part);
        }
        return out;
    }

    private static void add(List<String> out, String raw) {
        String word = raw.toLowerCase(Locale.ROOT);
        if (word.length() < 2 || STOP.contains(word)) return;
        out.add(singular(word));
    }

    /** Words that end in s and are no plural. */
    private static final Set<String> NOT_PLURAL = Set.of("news", "status", "bus", "virus", "campus", "focus",
            "bonus", "census", "corpus", "alias", "bias", "canvas", "atlas", "gas", "chaos", "series", "species",
            "always", "perhaps", "was", "has", "does", "yes");

    /** "menus" is menu, "addresses" address, "summaries" summary; "news", "status", "class", "analysis" stay. */
    static String singular(String word) {
        if (NOT_PLURAL.contains(word) || word.endsWith("ss") || word.endsWith("is")) return word;
        if (word.length() > 4 && word.endsWith("ies")) return word.substring(0, word.length() - 3) + "y";
        if (word.length() > 4 && (word.endsWith("sses") || word.endsWith("xes") || word.endsWith("ches")
                || word.endsWith("shes"))) {
            return word.substring(0, word.length() - 2);
        }
        if (word.length() > 3 && word.endsWith("s")) return word.substring(0, word.length() - 1);
        return word;
    }

    /** The first sentence of a description: what a match is listed with. Its whole one is in the tools array. */
    static String gist(String description) {
        if (description == null) return "";
        String text = description.strip();
        int end = text.indexOf(". ");
        // "e.g. " and "i.e. " end no sentence.
        while (end > 0 && (text.startsWith("e.g", end - 3) || text.startsWith("i.e", end - 3))) {
            end = text.indexOf(". ", end + 1);
        }
        int line = text.indexOf('\n');
        if (line >= 0 && (end < 0 || line < end)) return text.substring(0, line).strip();
        return end < 0 ? text : text.substring(0, end + 1);
    }
}
