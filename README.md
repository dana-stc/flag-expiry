# flag-expiry

Feature flags that expire. Annotate flagged code with an owner and an expiry date.
The compiler warns when the date approaches and **fails the build** once it has passed,
so dead flags and dead branches get removed instead of piling up.

## Why

Feature flags are meant to be temporary, but nothing forces anyone to delete them.
`flag-expiry` turns "we should clean this up someday" into a build-time check with a
named owner and a date.

## Quick start

### 1. Add the dependency

```xml
<dependency>
  <groupId>io.flagexpiry</groupId>
  <artifactId>flag-expiry</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scope>provided</scope>
</dependency>
```

Until it is published, install it locally with `mvn install` in this directory.

The annotation processor is registered through `META-INF/services`, so javac picks it up
automatically from the classpath. No extra compiler plugin configuration is needed.

### 2. Annotate flagged code

```java
import io.flagexpiry.Flag;

public class CheckoutService {

    @Flag(name = "new-checkout",
          owner = "team-payments",
          expires = "2026-12-01",
          note = "PAY-1234: remove old flow after 100% rollout")
    public Receipt pay(Cart cart) {
        if (flags.isOn("new-checkout")) {
            return newFlow(cart);
        }
        return legacyFlow(cart);
    }
}
```

### 3. Build

| Situation                              | Result                                                        |
|----------------------------------------|---------------------------------------------------------------|
| Expiry is more than 14 days away       | Silent                                                        |
| Expiry is within 14 days               | Warning: `Flag 'new-checkout' expires in 9 days (...)`        |
| Expiry day itself                      | Still valid (the flag is valid *through* the date)            |
| Expiry date has passed                 | **Compile error**, naming the flag, owner and days overdue    |
| Blank owner or blank name              | Compile error                                                 |
| Date not in `yyyy-MM-dd` format        | Compile error                                                 |

Example error:

```
CheckoutService.java:8: error: Flag 'new-checkout' expired on 2026-12-01 (3 days ago).
Owner: team-payments. Remove the flag and its dead branch.
```

## The `@Flag` annotation

| Attribute | Required | Description                                                     |
|-----------|----------|-----------------------------------------------------------------|
| `name`    | yes      | Flag key, as used in your flag provider                         |
| `owner`   | yes      | Team or person responsible for removing it                      |
| `expires` | yes      | ISO date `yyyy-MM-dd`, valid through that day                   |
| `note`    | no       | Free text: reason, ticket, removal instructions                 |
| `resolvedTo` | no    | `true` (default) or `false`: which behaviour the cleanup tool keeps when the flag expires |

It can be placed on classes, methods, fields, constructors and local variables.
Retention is `RUNTIME`, so you can also read flags via reflection (for example to build
a report of all flags and owners).

## Configuration

Pass options to javac with `-A`. In Maven:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <compilerArgs>
      <arg>-Aflagexpiry.warnDays=30</arg>
      <arg>-Aflagexpiry.failOnExpired=true</arg>
    </compilerArgs>
  </configuration>
</plugin>
```

In Gradle:

```groovy
tasks.withType(JavaCompile) {
    options.compilerArgs += ['-Aflagexpiry.warnDays=30']
}
```

| Option                        | Default | Description                                            |
|-------------------------------|---------|--------------------------------------------------------|
| `flagexpiry.warnDays`         | `14`    | Warn this many days before expiry                      |
| `flagexpiry.failOnExpired`    | `true`  | `false` downgrades expired flags from error to warning |
| `flagexpiry.today`            | system date | Override "today" (`yyyy-MM-dd`), useful in tests   |

Each option can also come from an environment variable (`FLAGEXPIRY_WARN_DAYS`,
`FLAGEXPIRY_FAIL_ON_EXPIRED`, `FLAGEXPIRY_TODAY`). A `-A` option wins over the variable.

## Recommended workflow

1. **Add the flag with a realistic date.** Pick the rollout end plus a buffer, not "never".
2. **Roll out and finish.** When the flag is 100% on, remove the flag, the annotation
   and the dead branch in one PR. Once the flag has expired, the
   [cleanup workflow](#automatic-cleanup-prs) opens this PR for you.
3. **Need more time?** Move `expires` forward in a PR. The change is reviewed and visible
   in git history, which makes extensions a conscious decision.
4. **Adopting in an existing codebase?** Start with `-Aflagexpiry.failOnExpired=false`
   so expired flags only warn, clean them up, then switch to the default.

## Tips

- Run with `-Aflagexpiry.today=...` to preview what the build will say in the future,
  e.g. `-Aflagexpiry.today=2026-12-02` to find everything that will break by then.
- A build that suddenly fails because time passed can surprise people. Keep `warnDays`
  large enough (30 days works well) that the warning is seen long before the error.
- Use CI warnings as a signal: grep the build log for `expires in` to post a weekly
  summary of upcoming expirations per owner.

## Automatic cleanup PRs

Expired flags can be removed by a deterministic tool that opens one pull
request per flag.

```bash
mvn compile
java -cp target/classes io.flagexpiry.cleanup.FlagCleanup list   --src src [--today 2026-12-02]
java -cp target/classes io.flagexpiry.cleanup.FlagCleanup remove --src src --flag new-checkout
```

`list` prints one tab-separated line (name, owner, expires, resolvedTo, note) per flag whose date has
passed. `--today` previews a future date, like `flagexpiry.today` for the compiler. Run it
with a JDK (it uses the compiler API), not just a JRE.

`remove` deletes the `@Flag` annotation, rewrites every `if (flags.isOn("name"))` /
ternary check to the behaviour of `resolvedTo` (the **flag-on** branch by default), drops the dead branch (and code made
unreachable by an early `return`), and removes the unused import. The check method name is
`isOn` by default; use `--check-methods isOn,isEnabled` for others.

A flag that was rolled back is removed the same way: set `resolvedTo = false` on its
`@Flag` and the flag-off branch is kept instead.

```java
@Flag(name = "dark-mode", owner = "team-ui", expires = "2026-03-01", resolvedTo = false)
```

Checks in `if` and ternary conditions may be combined with `!`, `&&`, `||` and parentheses;
the flag is replaced by its value and the condition is simplified:

| Condition (flag resolved to `true`) | Result                              |
|-------------------------------------|-------------------------------------|
| `isOn("x") && other`                | `other`                             |
| `other \|\| !isOn("x")`             | `other`                             |
| `isOn("x") \|\| other`              | always true: the `else` is deleted  |

If a check is used any other way (for example `isOn("x") == other`, passed as an argument,
stored in a variable, or used in a `switch`), or simplifying would drop a call that may
have side effects (`check() && isOn("x")` resolved to `false`), the tool exits with code 2
and changes nothing, so that flag needs manual cleanup.

`scripts/flag-cleanup.sh` runs this for every expired flag: it creates a
`flag-cleanup/<name>` branch, runs your build and tests on the result, commits, pushes and
runs `gh pr create`. Flags that already have a branch, need manual cleanup or break the
build are skipped and reported. `.github/workflows/flag-cleanup.yml` runs it every Monday (or on
demand. The PR is never merged automatically: a human confirms that the kept behaviour
(on, or off with `resolvedTo = false`) is what shipped.

To use it in another project, copy the script and workflow and point the script at your
code with `SRC_DIR` (default `src`) and at the flag-expiry classes or jar with `CLASSES`
(default `target/classes`). `BASE` sets the target branch (default `main`),
`VERIFY_CMD` the check run before each PR (default `mvn -B -q test`, empty to skip) and
`DRY_RUN=1` only prints what would happen. While `VERIFY_CMD` runs,
`FLAGEXPIRY_FAIL_ON_EXPIRED=false` is set, so the other expired flags do not fail the
build (this does not apply if your build passes `-Aflagexpiry.failOnExpired` itself).
Requirements:

- `gh` authenticated (the workflow uses `GITHUB_TOKEN`) and JDK 17+.
- In the repository settings, enable *Allow GitHub Actions to create and approve pull
  requests*.
- PRs opened with `GITHUB_TOKEN` do **not** trigger other workflows. To run your CI on the
  cleanup PRs, add a personal access token or GitHub App token as the repository secret
  `FLAG_CLEANUP_TOKEN`; the workflow uses it when present.

## Limitations

- Checks run at compile time on source code only. Flags that exist solely in your flag
  provider's dashboard (not annotated in code) are not covered.
- The processor itself never edits code; removal is a separate tool (see
  [Automatic cleanup PRs](#automatic-cleanup-prs)).
- Dates are compared against the build machine's local date.
- Automatic cleanup cannot see your flag provider, so it keeps the flag-on branch unless
  the annotation says `resolvedTo = false`. Set it when you roll a flag back, otherwise the
  PR keeps the wrong behaviour.
- Automatic cleanup only understands `if` statements and ternaries whose condition is
  built from the check call (`isOn` by default), `!`, `&&`, `||` and parentheses. Flags
  stored in variables, passed as arguments or used in a `switch` are reported and left for
  manual cleanup.
- Cleanup does not touch non-Java files (config, flag provider definitions). It runs
  `VERIFY_CMD` before opening a PR, but only your CI on the PR (see the token note above)
  is the real safety net.

## Building from source

```bash
mvn test      # runs the processor and the cleanup rewriter against in-memory sources
mvn install   # installs the artifact into your local repository
```

Requires JDK 17+.
