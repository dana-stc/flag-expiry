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
   and the dead branch in one PR.
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

## Limitations

- Checks run at compile time on source code only. Flags that exist solely in your flag
  provider's dashboard (not annotated in code) are not covered.
- The processor does not remove code for you. Automatic cleanup (for example an
  OpenRewrite recipe that deletes the flag and the dead branch) is a planned extension.
- Dates are compared against the build machine's local date.

## Building from source

```bash
mvn test      # runs the processor against in-memory sources
mvn install   # installs the artifact into your local repository
```

Requires JDK 17+.
