package io.flagexpiry.cleanup;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Removes a flag from Java source text: the {@code @Flag} annotation, the flag checks
 * ({@code if}/ternary on {@code x.isOn("name")}) and the dead branch.
 *
 * <p>The flag is assumed to be rolled out: the "on" branch is kept. Anything it cannot
 * rewrite safely (for example {@code isOn("name") && other}) is reported as a problem and
 * the caller must leave the code untouched.
 */
final class FlagRewriter {

    record Found(String name, String owner, String expires, String note, int line) {}

    record Result(String source, List<String> problems) {}

    private record Edit(int start, int end, String text) {
        String applyTo(String src) {
            return src.substring(0, start) + text + src.substring(end);
        }
    }

    private record Annotated(AnnotationTree annotation, Tree element, Found info) {}

    private record Parsed(CompilationUnitTree cu, SourcePositions pos, Trees trees) {
        int start(Tree t) {
            return (int) pos.getStartPosition(cu, t);
        }

        int end(Tree t) {
            return (int) pos.getEndPosition(cu, t);
        }

        int line(Tree t) {
            return (int) cu.getLineMap().getLineNumber(start(t));
        }
    }

    private static final Pattern FLAG_USAGE = Pattern.compile("@(io\\.flagexpiry\\.)?Flag\\b");
    private static final Pattern FLAG_IMPORT = Pattern.compile("(?m)^import\\s+io\\.flagexpiry\\.Flag;[ \\t]*\\R?");

    private FlagRewriter() {}

    /** Every {@code @Flag} found in the source. */
    static List<Found> scan(String source) {
        return annotated(parse(source)).stream().map(Annotated::info).toList();
    }

    /** Removes every flag named {@code name} from the source. */
    static Result remove(String source, String name, Set<String> checks) {
        String src = source;
        for (int i = 0; i < 10_000; i++) {
            Parsed p = parse(src);
            Annotated target = annotated(p).stream().filter(a -> a.info().name().equals(name)).findFirst().orElse(null);
            if (target == null) {
                break;
            }
            Finder finder = new Finder(src, p, name, checks);
            finder.scan(p.trees().getPath(p.cu(), target.element()), null);
            Edit edit = finder.edit;
            if (edit == null) {
                List<String> left = checkCalls(p, target.element(), name, checks);
                if (!left.isEmpty()) {
                    return new Result(source, left);
                }
                edit = annotationEdit(src, p, target.annotation());
            }
            src = edit.applyTo(src);
        }
        List<String> leftovers = checkCalls(parse(src), null, name, checks);
        if (!leftovers.isEmpty()) {
            return new Result(source, leftovers);
        }
        if (!src.equals(source) && !FLAG_USAGE.matcher(FLAG_IMPORT.matcher(src).replaceAll("")).find()) {
            src = FLAG_IMPORT.matcher(src).replaceAll("");
        }
        return new Result(src, List.of());
    }

    // ---------------------------------------------------------------- parsing

    private static Parsed parse(String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///Source.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try {
            JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostics, List.of("-proc:none"), null, List.of(file));
            CompilationUnitTree cu = task.parse().iterator().next();
            for (Diagnostic<?> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) {
                    throw new IllegalArgumentException("Cannot parse source: " + d.getMessage(null));
                }
            }
            Trees trees = Trees.instance(task);
            return new Parsed(cu, trees.getSourcePositions(), trees);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static List<Annotated> annotated(Parsed p) {
        List<Annotated> found = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree t, Void v) {
                collect(t, t.getModifiers().getAnnotations());
                return super.visitClass(t, v);
            }

            @Override
            public Void visitMethod(MethodTree t, Void v) {
                collect(t, t.getModifiers().getAnnotations());
                return super.visitMethod(t, v);
            }

            @Override
            public Void visitVariable(VariableTree t, Void v) {
                collect(t, t.getModifiers().getAnnotations());
                return super.visitVariable(t, v);
            }

            private void collect(Tree element, List<? extends AnnotationTree> annotations) {
                for (AnnotationTree a : annotations) {
                    String type = a.getAnnotationType().toString();
                    if (!type.equals("Flag") && !type.equals("io.flagexpiry.Flag")) {
                        continue;
                    }
                    Map<String, String> args = new HashMap<>();
                    for (ExpressionTree arg : a.getArguments()) {
                        if (arg instanceof AssignmentTree as && as.getVariable() instanceof IdentifierTree id
                                && as.getExpression() instanceof LiteralTree lt && lt.getValue() instanceof String s) {
                            args.put(id.getName().toString(), s);
                        }
                    }
                    if (args.containsKey("name")) {
                        found.add(new Annotated(a, element, new Found(args.get("name"), args.getOrDefault("owner", ""),
                                args.getOrDefault("expires", ""), args.getOrDefault("note", ""), p.line(a))));
                    }
                }
            }
        }.scan(p.cu(), null);
        return found;
    }

    // ---------------------------------------------------------------- flag checks

    /** Returns TRUE for {@code isOn("name")}, FALSE for {@code !isOn("name")}, null otherwise. */
    private static Boolean polarity(ExpressionTree cond, String name, Set<String> checks) {
        if (cond instanceof ParenthesizedTree pt) {
            return polarity(pt.getExpression(), name, checks);
        }
        if (cond instanceof UnaryTree ut && ut.getKind() == Tree.Kind.LOGICAL_COMPLEMENT) {
            Boolean inner = polarity(ut.getExpression(), name, checks);
            return inner == null ? null : !inner;
        }
        if (cond instanceof MethodInvocationTree m && isCheck(m, name, checks)) {
            return Boolean.TRUE;
        }
        return null;
    }

    private static boolean isCheck(MethodInvocationTree m, String name, Set<String> checks) {
        String method;
        if (m.getMethodSelect() instanceof MemberSelectTree ms) {
            method = ms.getIdentifier().toString();
        } else if (m.getMethodSelect() instanceof IdentifierTree id) {
            method = id.getName().toString();
        } else {
            return false;
        }
        return checks.contains(method) && m.getArguments().size() == 1
                && m.getArguments().get(0) instanceof LiteralTree lt && name.equals(lt.getValue());
    }

    private static List<String> checkCalls(Parsed p, Tree scope, String name, Set<String> checks) {
        List<String> out = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree m, Void v) {
                if (isCheck(m, name, checks)) {
                    out.add("line " + p.line(m) + ": cannot remove automatically: " + m
                            + " (only plain if / ternary checks are supported)");
                }
                return super.visitMethodInvocation(m, v);
            }
        }.scan(scope == null ? p.cu() : scope, null);
        return out;
    }

    // ---------------------------------------------------------------- edits

    private static final class Finder extends TreePathScanner<Void, Void> {
        private final String src;
        private final Parsed p;
        private final String name;
        private final Set<String> checks;
        Edit edit;

        Finder(String src, Parsed p, String name, Set<String> checks) {
            this.src = src;
            this.p = p;
            this.name = name;
            this.checks = checks;
        }

        @Override
        public Void visitIf(IfTree t, Void v) {
            if (edit == null) {
                Boolean on = polarity(t.getCondition(), name, checks);
                if (on != null) {
                    edit = ifEdit(t, on, getCurrentPath().getParentPath().getLeaf());
                    return null;
                }
            }
            return super.visitIf(t, v);
        }

        @Override
        public Void visitConditionalExpression(ConditionalExpressionTree t, Void v) {
            if (edit == null) {
                Boolean on = polarity(t.getCondition(), name, checks);
                if (on != null) {
                    ExpressionTree keep = on ? t.getTrueExpression() : t.getFalseExpression();
                    edit = new Edit(p.start(t), p.end(t), slice(keep));
                    return null;
                }
            }
            return super.visitConditionalExpression(t, v);
        }

        private String slice(Tree t) {
            return src.substring(p.start(t), p.end(t));
        }

        private Edit ifEdit(IfTree t, boolean on, Tree parent) {
            StatementTree keep = on ? t.getThenStatement() : t.getElseStatement();
            int start = p.start(t);
            int end = p.end(t);
            if (!(parent instanceof BlockTree block)) {
                return new Edit(start, end, keep == null ? "{ }" : slice(keep));
            }
            String text;
            if (keep == null) {
                text = "";
            } else if (keep instanceof BlockTree kb && !declaresLocals(kb)) {
                text = unwrap(kb, start);
            } else {
                text = slice(keep);
            }
            if (keep != null && !completesNormally(keep)) {
                // everything after the kept branch in this block is now unreachable
                List<? extends StatementTree> siblings = block.getStatements();
                end = p.end(siblings.get(siblings.size() - 1));
            }
            if (text.isEmpty()) {
                int lineStart = src.lastIndexOf('\n', start - 1) + 1;
                if (src.substring(lineStart, start).isBlank()) {
                    start = lineStart;
                    while (end < src.length() && (src.charAt(end) == ' ' || src.charAt(end) == '\t')) {
                        end++;
                    }
                    if (end < src.length() && src.charAt(end) == '\r') {
                        end++;
                    }
                    if (end < src.length() && src.charAt(end) == '\n') {
                        end++;
                    }
                }
            }
            return new Edit(start, end, text);
        }

        private static boolean declaresLocals(BlockTree b) {
            return b.getStatements().stream().anyMatch(s -> s instanceof VariableTree || s instanceof ClassTree);
        }

        private static boolean completesNormally(StatementTree s) {
            if (s instanceof BlockTree b) {
                return b.getStatements().isEmpty() || completesNormally(b.getStatements().get(b.getStatements().size() - 1));
            }
            return switch (s.getKind()) {
                case RETURN, THROW, BREAK, CONTINUE -> false;
                default -> true;
            };
        }

        /** Source of the block body, dedented one level so it sits where the {@code if} was. */
        private String unwrap(BlockTree block, int ifStart) {
            String inner = src.substring(p.start(block) + 1, p.end(block) - 1);
            List<String> lines = new ArrayList<>(Arrays.asList(inner.split("\r?\n", -1)));
            if (!lines.isEmpty() && lines.get(0).isBlank()) {
                lines.remove(0);
            }
            if (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
                lines.remove(lines.size() - 1);
            }
            if (lines.isEmpty()) {
                return "";
            }
            int lineStart = src.lastIndexOf('\n', ifStart - 1) + 1;
            int ifIndent = indent(src.substring(lineStart, src.indexOf('\n', ifStart) < 0 ? src.length() : src.indexOf('\n', ifStart)));
            int min = lines.stream().filter(l -> !l.isBlank()).mapToInt(Finder::indent).min().orElse(0);
            int strip = Math.max(0, min - ifIndent);
            List<String> out = new ArrayList<>();
            for (String l : lines) {
                out.add(l.isBlank() ? "" : l.substring(Math.min(strip, indent(l))));
            }
            out.set(0, out.get(0).stripLeading());
            return String.join("\n", out);
        }

        private static int indent(String line) {
            int i = 0;
            while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
                i++;
            }
            return i;
        }
    }

    /** Deletes the annotation and the line it sat on, if it was alone there. */
    private static Edit annotationEdit(String src, Parsed p, AnnotationTree a) {
        int start = p.start(a);
        int end = p.end(a);
        int lineStart = src.lastIndexOf('\n', start - 1) + 1;
        boolean alone = src.substring(lineStart, start).isBlank();
        while (end < src.length() && (src.charAt(end) == ' ' || src.charAt(end) == '\t')) {
            end++;
        }
        if (alone) {
            start = lineStart;
            if (end < src.length() && src.charAt(end) == '\r') {
                end++;
            }
            if (end < src.length() && src.charAt(end) == '\n') {
                end++;
            }
        }
        return new Edit(start, end, "");
    }
}
