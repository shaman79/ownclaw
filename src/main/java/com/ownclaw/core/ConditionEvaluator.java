package com.ownclaw.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates plan step condition expressions in pure Java.
 * The grammar is deliberately minimal to prevent the Mentor from generating unparseable expressions.
 * <p>
 * Supported variables: {@code $N.success}, {@code $N.output}, {@code $N.exit_code}
 * <br>
 * Supported operators: {@code &&}, {@code ||}, {@code !}, {@code ==}, {@code !=},
 * {@code .contains("literal")}, {@code .isEmpty()}
 * <p>
 * On parse failure: returns {@code true} (fail-open) and logs a warning.
 */
@Component
public class ConditionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ConditionEvaluator.class);

    // Pre-compiled patterns for step result references
    private static final Pattern CONTAINS_PATTERN = Pattern.compile("^(.+)\\.contains\\([\"'](.*)[\"\'\u2019]\\)$");
    private static final Pattern EMPTY_PATTERN    = Pattern.compile("^(.+)\\.isEmpty\\(\\)$");
    private static final Pattern SUCCESS_PATTERN  = Pattern.compile("^\\$(\\d+)\\.success$");
    private static final Pattern OUTPUT_PATTERN   = Pattern.compile("^\\$(\\d+)\\.output$");
    private static final Pattern EXIT_CODE_PATTERN = Pattern.compile("^\\$(\\d+)\\.exit_code$");

    /**
     * Evaluate a condition expression against completed step results.
     *
     * @param condition   the condition string (e.g. "$1.success && $2.success"), or null (= true)
     * @param stepResults map of step ID → StepResult for completed steps
     * @return evaluation result, or {@code true} if condition is null/empty or unparseable
     */
    public boolean evaluate(String condition, Map<Integer, StepResult> stepResults) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        try {
            return evalExpr(condition.trim(), stepResults);
        } catch (Exception e) {
            log.warn("Condition parse error (fail-open): '{}' — {}", condition, e.getMessage());
            return true;
        }
    }

    // --- Recursive descent parser ---

    private boolean evalExpr(String expr, Map<Integer, StepResult> results) {
        expr = expr.trim();

        // Handle parentheses
        if (expr.startsWith("(")) {
            int close = findMatchingParen(expr, 0);
            if (close == expr.length() - 1) {
                return evalExpr(expr.substring(1, close), results);
            }
        }

        // Split on || (lowest precedence)
        int idx = findOperator(expr, "||");
        if (idx >= 0) {
            return evalExpr(expr.substring(0, idx), results)
                    || evalExpr(expr.substring(idx + 2), results);
        }

        // Split on && (higher precedence)
        idx = findOperator(expr, "&&");
        if (idx >= 0) {
            return evalExpr(expr.substring(0, idx), results)
                    && evalExpr(expr.substring(idx + 2), results);
        }

        // Handle ! prefix
        if (expr.startsWith("!")) {
            return !evalExpr(expr.substring(1), results);
        }

        // Handle == and !=
        idx = findOperator(expr, "==");
        if (idx >= 0) {
            String left = resolveToString(expr.substring(0, idx).trim(), results);
            String right = resolveToString(expr.substring(idx + 2).trim(), results);
            return left.equals(right);
        }
        idx = findOperator(expr, "!=");
        if (idx >= 0) {
            String left = resolveToString(expr.substring(0, idx).trim(), results);
            String right = resolveToString(expr.substring(idx + 2).trim(), results);
            return !left.equals(right);
        }

        // Handle .contains("...") or .contains('...')
        Matcher cm = CONTAINS_PATTERN.matcher(expr);
        if (cm.matches()) {
            String value = resolveToString(cm.group(1).trim(), results);
            return value.contains(cm.group(2));
        }

        // Handle .isEmpty()
        Matcher em = EMPTY_PATTERN.matcher(expr);
        if (em.matches()) {
            String value = resolveToString(em.group(1).trim(), results);
            return value.isEmpty();
        }

        // Atomic values: $N.success, literal "true"/"false"
        return resolveToBoolean(expr, results);
    }

    private boolean resolveToBoolean(String expr, Map<Integer, StepResult> results) {
        expr = expr.trim();
        if ("true".equalsIgnoreCase(expr)) return true;
        if ("false".equalsIgnoreCase(expr)) return false;

        Pattern p = SUCCESS_PATTERN;
        Matcher m = p.matcher(expr);
        if (m.matches()) {
            int stepId = Integer.parseInt(m.group(1));
            StepResult r = results.get(stepId);
            return r != null && r.success();
        }

        throw new IllegalArgumentException("Cannot resolve to boolean: " + expr);
    }

    private String resolveToString(String expr, Map<Integer, StepResult> results) {
        expr = expr.trim();

        // String literal: "..."
        if (expr.startsWith("\"") && expr.endsWith("\"")) {
            return expr.substring(1, expr.length() - 1);
        }

        // Boolean/number literals
        if ("true".equalsIgnoreCase(expr) || "false".equalsIgnoreCase(expr)) {
            return expr.toLowerCase();
        }

        // $N.output
        Matcher om = OUTPUT_PATTERN.matcher(expr);
        if (om.matches()) {
            int stepId = Integer.parseInt(om.group(1));
            StepResult r = results.get(stepId);
            return r != null ? (r.output() != null ? r.output() : "") : "";
        }

        // $N.exit_code
        Matcher em = EXIT_CODE_PATTERN.matcher(expr);
        if (em.matches()) {
            int stepId = Integer.parseInt(em.group(1));
            StepResult r = results.get(stepId);
            return r != null ? String.valueOf(r.exitCode()) : "-1";
        }

        // $N.success (as string)
        Matcher sm = SUCCESS_PATTERN.matcher(expr);
        if (sm.matches()) {
            int stepId = Integer.parseInt(sm.group(1));
            StepResult r = results.get(stepId);
            return (r != null && r.success()) ? "true" : "false";
        }

        return expr;
    }

    /** Find operator at the top level (not inside parentheses or quotes). */
    private int findOperator(String expr, String operator) {
        int depth = 0;
        boolean inQuote = false;
        for (int i = 0; i <= expr.length() - operator.length(); i++) {
            char c = expr.charAt(i);
            if (c == '"' || c == '\'' || c == '\u2019') inQuote = !inQuote;
            if (inQuote) continue;
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (depth == 0 && expr.startsWith(operator, i)) {
                return i;
            }
        }
        return -1;
    }

    /** Find closing parenthesis matching the one at position {@code start}. */
    private int findMatchingParen(String expr, int start) {
        int depth = 0;
        for (int i = start; i < expr.length(); i++) {
            if (expr.charAt(i) == '(') depth++;
            if (expr.charAt(i) == ')') depth--;
            if (depth == 0) return i;
        }
        throw new IllegalArgumentException("Unmatched parenthesis in: " + expr);
    }
}
