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

`list` prints one tab-separated line (name, owner, expires, note) per flag whose date has
passed. `--today` previews a future date, like `flagexpiry.today` for the compiler. Run it
with a JDK (it uses the compiler API), not just a JRE.

`remove` deletes the `@Flag` annotation, rewrites every `if (flags.isOn("name"))` /
ternary check to the **flag-on** behaviour, drops the dead branch (and code made
unreachable by an early `return`), and removes the unused import. The check method name is
`isOn` by default; use `--check-methods isOn,isEnabled` for others.

If a check is not a plain `if` or ternary (for example `isOn("x") && other`), the tool
exits with code 2 and changes nothing, so that flag needs manual cleanup.

`scripts/flag-cleanup.sh` runs this for every expired flag: it creates a
`flag-cleanup/<name>` branch, commits, pushes and runs `gh pr create`, skipping flags that
already have a branch. `.github/workflows/flag-cleanup.yml` runs it every Monday (or on
demand). The PR is never merged automatically: a human confirms that flag-on is what
shipped.

To use it in another project, copy the script and workflow and point the script at your
code with `SRC_DIR` (default `src`) and at the flag-expiry classes or jar with `CLASSES`
(default `target/classes`). `BASE` sets the target branch (default `main`) and
`DRY_RUN=1` only prints what would happen. Requirements:

- `gh` authenticated (the workflow uses `GITHUB_TOKEN`) and JDK 17+.
- In the repository settings, enable *Allow GitHub Actions to create and approve pull
  requests*.
- PRs opened with `GITHUB_TOKEN` do **not** trigger other workflows. If you want your CI
  to run on the cleanup PRs, pass a personal access token or GitHub App token as
  `GH_TOKEN` instead.

## Limitations

- Checks run at compile time on source code only. Flags that exist solely in your flag
  provider's dashboard (not annotated in code) are not covered.
- The processor itself never edits code; removal is a separate tool (see
  [Automatic cleanup PRs](#automatic-cleanup-prs)).
- Dates are compared against the build machine's local date.
- Automatic cleanup always keeps the flag-on behaviour, so a flag that was rolled back
  must be removed by hand.
- Automatic cleanup only understands plain `if` statements and ternaries on a single
  check call (`isOn` by default). Anything else (`&&`/`||` combinations, flags stored in
  variables, `switch`) is reported and left for manual cleanup.
- Cleanup does not touch non-Java files (config, flag provider definitions) and does not
  run your tests; your CI must run on the PR (see the token note above) to catch a broken
  result, for example code left unreachable by a nested `return`.

## Building from source

```bash
mvn test      # runs the processor and the cleanup rewriter against in-memory sources
mvn install   # installs the artifact into your local repository
```

Requires JDK 17+.
