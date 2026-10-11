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
    void unsupportedConditionIsReportedAndNothingChanges() {
        var r = remove("    @Flag(name = \"f\", owner = \"o\", expires = \"2020-01-01\")\n"
                + "    void run() { if (flags.isOn(\"f\") && other) { a(); } }\n");
        assertEquals(1, r.problems().size());
    }
}
