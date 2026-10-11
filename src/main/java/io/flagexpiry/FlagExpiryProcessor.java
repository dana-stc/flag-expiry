package io.flagexpiry;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;

/**
 * Checks every {@link Flag} at compile time.
 *
 * <p>Options (pass with {@code -Akey=value}):
 * <ul>
 *   <li>{@code flagexpiry.warnDays} - warn this many days before expiry (default 14)</li>
 *   <li>{@code flagexpiry.failOnExpired} - report expired flags as errors (default true)</li>
 *   <li>{@code flagexpiry.today} - override today's date, {@code yyyy-MM-dd} (for tests)</li>
 * </ul>
 */
@SupportedAnnotationTypes("io.flagexpiry.Flag")
@SupportedOptions({FlagExpiryProcessor.OPT_WARN_DAYS, FlagExpiryProcessor.OPT_FAIL,
        FlagExpiryProcessor.OPT_TODAY})
public final class FlagExpiryProcessor extends AbstractProcessor {

    static final String OPT_WARN_DAYS = "flagexpiry.warnDays";
    static final String OPT_FAIL = "flagexpiry.failOnExpired";
    static final String OPT_TODAY = "flagexpiry.today";

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (annotations.isEmpty()) {
            return false;
        }
        LocalDate today = option(OPT_TODAY) != null ? LocalDate.parse(option(OPT_TODAY)) : LocalDate.now();
        int warnDays = option(OPT_WARN_DAYS) != null ? Integer.parseInt(option(OPT_WARN_DAYS)) : 14;
        boolean failOnExpired = !"false".equalsIgnoreCase(option(OPT_FAIL));

        for (Element element : round.getElementsAnnotatedWith(Flag.class)) {
            Flag flag = element.getAnnotation(Flag.class);
            check(element, flag, today, warnDays, failOnExpired);
        }
        return false;
    }

    private void check(Element element, Flag flag, LocalDate today, int warnDays, boolean failOnExpired) {
        if (flag.name().isBlank()) {
            error(element, "@Flag name must not be blank");
        }
        if (flag.owner().isBlank()) {
            error(element, "@Flag '" + flag.name() + "' has no owner");
        }
        LocalDate expires;
        try {
            expires = LocalDate.parse(flag.expires());
        } catch (DateTimeParseException e) {
            error(element, "@Flag '" + flag.name() + "' has invalid expires '" + flag.expires()
                    + "', expected yyyy-MM-dd");
            return;
        }
        long daysLeft = ChronoUnit.DAYS.between(today, expires);
        if (daysLeft < 0) {
            String msg = "Flag '" + flag.name() + "' expired on " + expires + " (" + (-daysLeft)
                    + " days ago). Owner: " + flag.owner() + ". Remove the flag and its dead branch.";
            processingEnv.getMessager().printMessage(failOnExpired ? Kind.ERROR : Kind.WARNING, msg, element);
        } else if (daysLeft <= warnDays) {
            processingEnv.getMessager().printMessage(Kind.WARNING, "Flag '" + flag.name() + "' expires in "
                    + daysLeft + " days (" + expires + "). Owner: " + flag.owner(), element);
        }
    }

    private void error(Element element, String msg) {
        processingEnv.getMessager().printMessage(Kind.ERROR, msg, element);
    }

    private String option(String key) {
        return processingEnv.getOptions().get(key);
    }
}
