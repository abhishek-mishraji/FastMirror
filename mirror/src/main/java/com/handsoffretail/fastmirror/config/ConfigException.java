package com.handsoffretail.fastmirror.config;

/**
 * A configuration problem that prevents startup.
 *
 * <p>Every message must be actionable on its own: it names the key, the offending value and what
 * was expected. The operator reading it is standing at a store PC with no debugger.
 */
public final class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
