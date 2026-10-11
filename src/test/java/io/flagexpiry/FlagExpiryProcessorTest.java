package io.flagexpiry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

class FlagExpiryProcessorTest {

    private record Result(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        List<String> messages(Diagnostic.Kind kind) {
            return diagnostics.stream().filter(d -> d.getKind() == kind)
                    .map(d -> d.getMessage(null)).collect(Collectors.toList());
        }
    }

    private static Result compile(String flagArgs, String... options) {
        String source = "import io.flagexpiry.Flag;\n"
                + "public class Checkout {\n"
                + "  @Flag(" + flagArgs + ")\n"
                + "  void pay() {}\n"
                + "}\n";
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///Checkout.java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignore) {
                return source;
            }
        };
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        var args = new java.util.ArrayList<String>(List.of("-proc:only", "-classpath",
                System.getProperty("java.class.path")));
        args.addAll(List.of(options));
        var task = compiler.getTask(null, null, diagnostics, args, null, List.of(file));
        task.setProcessors(List.of(new FlagExpiryProcessor()));
        boolean ok = task.call();
        return new Result(ok, diagnostics.getDiagnostics());
    }

    private static final String TODAY = "-Aflagexpiry.today=2026-10-11";

    @Test
    void validFlagFarFromExpiryProducesNoDiagnostics() {
        Result r = compile("name=\"a\", owner=\"team-x\", expires=\"2027-01-01\"", TODAY);
        assertTrue(r.success());
        assertTrue(r.messages(Diagnostic.Kind.WARNING).isEmpty());
    }

    @Test
    void flagNearExpiryWarns() {
        Result r = compile("name=\"a\", owner=\"team-x\", expires=\"2026-10-20\"", TODAY);
        assertTrue(r.success());
        assertEquals(1, r.messages(Diagnostic.Kind.WARNING).size());
        assertTrue(r.messages(Diagnostic.Kind.WARNING).get(0).contains("expires in 9 days"));
    }

    @Test
    void expiryDayItselfIsStillValid() {
        Result r = compile("name=\"a\", owner=\"team-x\", expires=\"2026-10-11\"", TODAY);
        assertTrue(r.success());
    }

    @Test
    void expiredFlagFailsTheBuild() {
        Result r = compile("name=\"old\", owner=\"team-x\", expires=\"2026-10-01\"", TODAY);
        assertFalse(r.success());
        assertTrue(r.messages(Diagnostic.Kind.ERROR).get(0).contains("expired on 2026-10-01"));
    }

    @Test
    void failOnExpiredFalseDowngradesToWarning() {
        Result r = compile("name=\"old\", owner=\"team-x\", expires=\"2026-10-01\"", TODAY,
                "-Aflagexpiry.failOnExpired=false");
        assertTrue(r.success());
        assertEquals(1, r.messages(Diagnostic.Kind.WARNING).size());
    }

    @Test
    void blankOwnerFails() {
        Result r = compile("name=\"a\", owner=\" \", expires=\"2027-01-01\"", TODAY);
        assertFalse(r.success());
        assertTrue(r.messages(Diagnostic.Kind.ERROR).get(0).contains("no owner"));
    }

    @Test
    void invalidDateFails() {
        Result r = compile("name=\"a\", owner=\"t\", expires=\"01/01/2027\"", TODAY);
        assertFalse(r.success());
        assertTrue(r.messages(Diagnostic.Kind.ERROR).get(0).contains("invalid expires"));
    }

    @Test
    void warnDaysIsConfigurable() {
        Result r = compile("name=\"a\", owner=\"t\", expires=\"2026-11-20\"", TODAY,
                "-Aflagexpiry.warnDays=60");
        assertEquals(1, r.messages(Diagnostic.Kind.WARNING).size());
    }
}
