package io.flagexpiry;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks code guarded by a temporary feature flag.
 *
 * <p>The annotation processor warns when {@link #expires()} is close and fails the
 * compilation once it has passed, so dead flags cannot be forgotten.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR,
        ElementType.LOCAL_VARIABLE})
public @interface Flag {

    /** Flag key, as used in the flag provider (e.g. {@code "new-checkout"}). */
    String name();

    /** Team or person responsible for removing the flag. Must not be blank. */
    String owner();

    /** Expiry date in ISO format {@code yyyy-MM-dd}. The flag is valid through this day. */
    String expires();

    /** Optional free text: why the flag exists, ticket link, removal instructions. */
    String note() default "";

    /**
     * The value the flag is permanently resolved to. Only used by the cleanup tool: when the
     * flag expires, the branch for this value is kept and the other one is deleted. Use
     * {@code false} for a flag that was rolled back.
     */
    boolean resolvedTo() default true;
}
