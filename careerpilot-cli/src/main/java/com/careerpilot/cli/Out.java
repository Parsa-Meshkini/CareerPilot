package com.careerpilot.cli;

import com.careerpilot.common.Json;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/** Console output helpers: plain text tables for people, JSON for scripts (global --json flag). */
public final class Out {
    public static PrintStream out = System.out;
    public static boolean json;

    private Out() {}

    public static void line(String text) { out.println(text); }

    public static void line(String format, Object... args) { out.println(String.format(format, args)); }

    public static void json(Object value) { out.println(Json.pretty(value)); }

    /** Prints rows as an aligned table, or as JSON when --json is on. */
    public static void table(List<String> headers, List<List<String>> rows, Object jsonValue) {
        if (json) {
            json(jsonValue);
            return;
        }
        if (rows.isEmpty()) {
            line("(nothing to show)");
            return;
        }
        List<Integer> widths = new ArrayList<>();
        for (int c = 0; c < headers.size(); c++) {
            int w = headers.get(c).length();
            for (List<String> r : rows) w = Math.max(w, Math.min(60, r.get(c).length()));
            widths.add(w);
        }
        StringBuilder head = new StringBuilder();
        for (int c = 0; c < headers.size(); c++) head.append(String.format("%-" + widths.get(c) + "s  ", headers.get(c)));
        line(head.toString().stripTrailing());
        for (List<String> r : rows) {
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < headers.size(); c++) {
                String cell = r.get(c).length() > 60 ? r.get(c).substring(0, 57) + "..." : r.get(c);
                sb.append(String.format("%-" + widths.get(c) + "s  ", cell));
            }
            line(sb.toString().stripTrailing());
        }
    }

    public static String shortId(Object id) { return id == null ? "" : id.toString().substring(0, 8); }
}
