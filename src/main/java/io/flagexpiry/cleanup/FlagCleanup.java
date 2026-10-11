package io.flagexpiry.cleanup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Command line tool that lists expired flags and removes them from source code.
 *
 * <pre>
 * list   --src DIR [--today yyyy-MM-dd]
 * remove --src DIR --flag NAME [--check-methods isOn,isEnabled]
 * </pre>
 *
 * <p>{@code list} prints one tab separated line per expired flag: name, owner, expires, note.
 * {@code remove} keeps the "on" branch of every check of the flag. Exit codes: 0 done,
 * 1 flag not found, 2 needs manual cleanup (nothing is written), 64 bad usage.
 */
public final class FlagCleanup {

    private static final Set<String> DEFAULT_CHECKS = Set.of("isOn");

    private FlagCleanup() {}

    public static void main(String[] argv) throws IOException {
        if (argv.length == 0) {
            usage();
            return;
        }
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 1; i + 1 < argv.length; i += 2) {
            opts.put(argv[i], argv[i + 1]);
        }
        Path src = Path.of(opts.getOrDefault("--src", "src"));
        switch (argv[0]) {
            case "list" -> list(src, opts.get("--today"));
            case "remove" -> {
                if (!opts.containsKey("--flag")) {
                    usage();
                    return;
                }
                Set<String> checks = opts.containsKey("--check-methods")
                        ? new TreeSet<>(List.of(opts.get("--check-methods").split("\\s*,\\s*"))) : DEFAULT_CHECKS;
                System.exit(remove(src, opts.get("--flag"), checks));
            }
            default -> usage();
        }
    }

    private static void list(Path src, String todayOpt) throws IOException {
        LocalDate today = todayOpt != null ? LocalDate.parse(todayOpt) : LocalDate.now();
        Map<String, FlagRewriter.Found> expired = new LinkedHashMap<>();
        for (Path file : javaFiles(src)) {
            String source = read(file);
            if (!source.contains("Flag")) {
                continue;
            }
            for (FlagRewriter.Found f : FlagRewriter.scan(source)) {
                try {
                    if (LocalDate.parse(f.expires()).isBefore(today)) {
                        expired.putIfAbsent(f.name(), f);
                    }
                } catch (DateTimeParseException ignored) {
                    // invalid dates are reported by the annotation processor at compile time
                }
            }
        }
        expired.values().forEach(f -> System.out.println(String.join("\t", f.name(), f.owner(), f.expires(),
                f.note().replaceAll("\\s+", " "))));
    }

    /** Returns the process exit code. */
    static int remove(Path src, String name, Set<String> checks) throws IOException {
        Map<Path, String> changed = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (Path file : javaFiles(src)) {
            String source = read(file);
            if (!source.contains("\"" + name + "\"")) {
                continue;
            }
            FlagRewriter.Result r = FlagRewriter.remove(source, name, checks);
            r.problems().forEach(p -> problems.add(file + ": " + p));
            if (!r.source().equals(source)) {
                changed.put(file, r.source());
            }
        }
        if (!problems.isEmpty()) {
            System.err.println("Flag '" + name + "' needs manual cleanup, nothing was changed:");
            problems.forEach(p -> System.err.println("  " + p));
            return 2;
        }
        if (changed.isEmpty()) {
            System.err.println("Flag '" + name + "' not found under " + src);
            return 1;
        }
        for (Map.Entry<Path, String> e : changed.entrySet()) {
            Files.writeString(e.getKey(), e.getValue(), StandardCharsets.UTF_8);
            System.out.println("updated " + e.getKey());
        }
        return 0;
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void usage() {
        System.err.println("usage: list --src DIR [--today yyyy-MM-dd]\n"
                + "       remove --src DIR --flag NAME [--check-methods isOn,isEnabled]");
        System.exit(64);
    }
}
