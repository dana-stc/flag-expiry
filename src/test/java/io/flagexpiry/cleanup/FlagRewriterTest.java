package io.flagexpiry.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class FlagRewriterTest {

    private static final Set<String> CHECKS = Set.of("isOn");

    private static FlagRewriter.Result remove(String body) {
        String source = "import io.flagexpiry.Flag;\n"
                + "class A {\n"
                + body
                + "}\n";
        return FlagRewriter.remove(source, "f", CHECKS);
    }

    @Test
    void earlyReturnKeepsNewFlowAndDropsLegacy() {
        var r = remove("    @Flag(name = \"f\", owner = \"o\", expires = \"2020-01-01\")\n"
                + "    int pay() {\n"
                + "        if (flags.isOn(\"f\")) {\n"
                + "            return 1;\n"
                + "        }\n"
                + "        return 2;\n"
                + "    }\n");
        assertTrue(r.problems().isEmpty());
        assertEquals("class A {\n"
                + "    int pay() {\n"
                + "        return 1;\n"
                + "    }\n"
                + "}\n", r.source());
    }

    @Test
    void ifElseKeepsOnBranchUnindented() {
        var r = remove("    @Flag(name = \"f\", owner = \"o\", expires = \"2020-01-01\")\n"
                + "    void run() {\n"
                + "        if (flags.isOn(\"f\")) {\n"
                + "            a();\n"
                + "            b();\n"
                + "        } else {\n"
                + "            c();\n"
                + "        }\n"
                + "    }\n");
        assertTrue(r.source().contains("    void run() {\n        a();\n        b();\n    }\n"), r.source());
        assertFalse(r.source().contains("c()"));
    }

    @Test
    void negatedCheckKeepsElseBranch() {
        var r = remove("    @Flag(name = \"f\", owner = \"o\", expires = \"2020-01-01\")\n"
                + "    void run() {\n"
                + "        if (!flags.isOn(\"f\")) {\n"
                + "            legacy();\n"
                + "        }\n"
                + "        after();\n"
                + "    }\n");
        assertTrue(r.source().contains("    void run() {\n        after();\n    }\n"), r.source());
    }

    @Test
    void ternaryKeepsOnValue() {
        var r = remove("    @Flag(name = \"f\", owner = \"o\", expires = \"2020-01-01\")\n"
                + "    String label() {\n"
                + "        return flags.isOn(\"f\") ? \"new\" : \"old\";\n"
                + "    }\n");
        assertTrue(r.source().contains("return \"new\";"), r.source());
    }

    @Test
    void importIsDroppedWhenNoFlagsLeft() {
        var r = remove("    @Flag(name = \"f\", owner = \"o\", expires = \"2020-01-01\")\n"
                + "    void run() { if (flags.isOn(\"f\")) { a(); } }\n");
        assertFalse(r.source().contains("import io.flagexpiry.Flag"), r.source());
    }

    @Test
    void otherFlagsAreUntouched() {
        var r = remove("    @Flag(name = \"g\", owner = \"o\", expires = \"2099-01-01\")\n"
                + "    void run() { if (flags.isOn(\"g\")) { a(); } }\n");
        assertTrue(r.source().contains("@Flag(name = \"g\""));
        assertTrue(r.source().contains("import io.flagexpiry.Flag"));
    }

    @Test
    void resolvedToFalseKeepsOffBranch() {
        var r = remove("    @Flag(name = \"f\", owner = \"o\", expires = \"2020-01-01\", resolvedTo = false)\n"
                + "    int pay() {\n"
                + "        if (flags.isOn(\"f\")) {\n"
                + "            return 1;\n"
                + "        }\n"
                + "        return 2;\n"
                + "    }\n");
        assertTrue(r.problems().isEmpty());
        assertTrue(r.source().contains("    int pay() {\n        return 2;\n    }\n"), r.source());
        assertFalse(r.source().contains("resolvedTo"));
    }

    @Test
    void resolvedToFalseTernaryKeepsOffValue() {
        var r = remove("    @Flag(name = \"f\", owner = \"o\", expires = \"2020-01-01\", resolvedTo = false)\n"
                + "    String label() { return !flags.isOn(\"f\") ? \"a\" : \"b\"; }\n");
        assertTrue(r.source().contains("return \"a\";"), r.source());
    }

    private static String flagged(String condition, boolean resolvedTo) {
        return "    @Flag(name = \"f\", owner = \"o\", expires = \"2020-01-01\", resolvedTo = " + resolvedTo + ")\n"
                + "    void run() { if (" + condition + ") { a(); } else { b(); } }\n";
    }

    @Test
    void andWithOtherConditionKeepsTheOtherCondition() {
        var r = remove(flagged("flags.isOn(\"f\") && other", true));
        assertTrue(r.problems().isEmpty());
        assertTrue(r.source().contains("if (other) { a(); } else { b(); }"), r.source());
    }

    @Test
    void orAndNegationAreSimplified() {
        var r = remove(flagged("other || !flags.isOn(\"f\")", true));
        assertTrue(r.source().contains("if (other) { a(); } else { b(); }"), r.source());

        r = remove(flagged("a && (b || flags.isOn(\"f\"))", false));
        assertTrue(r.source().contains("if (a && (b)) { a(); } else { b(); }"), r.source());
    }

    @Test
    void conditionDecidedByTheFlagDropsTheBranch() {
        var r = remove(flagged("flags.isOn(\"f\") || other", true));
        assertTrue(r.source().contains("void run() { a(); }"), r.source());
        r = remove(flagged("flags.isOn(\"f\") && other", false));
        assertTrue(r.source().contains("void run() { b(); }"), r.source());
    }

    @Test
    void droppingACallWithSideEffectsIsNotAllowed() {
        var r = remove(flagged("check() && flags.isOn(\"f\")", false));
        assertEquals(1, r.problems().size());
    }

    @Test
    void ifElseThatBothReturnDropsTheCodeAfterIt() {
        var r = remove("    @Flag(name = \"f\", owner = \"o\", expires = \"2020-01-01\")\n"
                + "    int run() {\n"
                + "        if (flags.isOn(\"f\")) {\n"
                + "            if (x) { return 1; } else { return 2; }\n"
                + "        }\n"
                + "        return 3;\n"
                + "    }\n");
        assertTrue(r.problems().isEmpty());
        assertFalse(r.source().contains("return 3"), r.source());
    }

    @Test
    void unsupportedConditionIsReportedAndNothingChanges() {
        var r = remove("    @Flag(name = \"f\", owner = \"o\", expires = \"2020-01-01\")\n"
                + "    void run() { if (flags.isOn(\"f\") == other) { a(); } }\n");
        assertEquals(1, r.problems().size());
    }
}
