package com.handsoffretail.fastmirror.logging;

/**
 * Log severities, rendered verbatim inside the square brackets of each line.
 *
 * <p>Deliberately only three. The operator reads this log beside the existing HOR AWS Sync Agent's
 * log and should not have to learn a second vocabulary.
 */
public enum LogLevel {

    /** Normal progress: startup, each successful copy, scan summaries, the periodic stats line. */
    INFO,

    /**
     * Something did not go to plan but the agent is working as designed: a locked source, a
     * vanished source, a partial capture, a skipped oversized file.
     */
    WARN,

    /** A genuine failure: an I/O error that captured nothing, or a watch failure. */
    ERROR
}
