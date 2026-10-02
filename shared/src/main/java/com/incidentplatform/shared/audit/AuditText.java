package com.incidentplatform.shared.audit;

/**
 * Error text as it may go into an audit event (backlog #0-84, found in
 * review).
 *
 * <p>An audit event is kept in plain text, in the outbox for its retention
 * and in the tenant's audit trail for good, and the trail is read by the
 * tenant. An exception's message is not written for that reader: an HTTP
 * client's can quote a request URL with a token in it, an internal host name
 * or a response body, and its length has no bound, while
 * {@link AuditEventPublisher} refuses an event over
 * {@code MAX_PAYLOAD_BYTES} and so fails the action it records. So an error
 * goes into an event only through {@link #error}: cut to
 * {@link #MAX_ERROR_LENGTH} characters, on one line. A message that is not
 * the platform's own (an unexpected exception) is not recorded at all, only
 * its type, through {@link #unexpected}; the full message belongs in the log.
 */
public final class AuditText {

    /** The longest error text an audit event carries. */
    public static final int MAX_ERROR_LENGTH = 500;

    private static final String CUT = "...";

    private AuditText() {
    }

    /**
     * A message the platform wrote itself (a channel's or Gemini's own
     * failure), fit for an audit event: control characters and the Unicode
     * line and paragraph separators (U+2028, U+2029) become spaces, and it is
     * cut to {@link #MAX_ERROR_LENGTH} characters, never between the two
     * halves of a surrogate pair (found in review); no message is "unknown".
     */
    public static String error(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        final StringBuilder oneLine = new StringBuilder(Math.min(message.length(), MAX_ERROR_LENGTH + 1));
        for (int i = 0; i < message.length() && oneLine.length() <= MAX_ERROR_LENGTH; i++) {
            final char c = message.charAt(i);
            oneLine.append(breaksLine(c) ? ' ' : c);
        }
        if (oneLine.length() <= MAX_ERROR_LENGTH) {
            return oneLine.toString();
        }
        int end = MAX_ERROR_LENGTH - CUT.length();
        if (Character.isHighSurrogate(oneLine.charAt(end - 1))) {
            end--;
        }
        return oneLine.substring(0, end) + CUT;
    }

    private static boolean breaksLine(char c) {
        final int type = Character.getType(c);
        return Character.isISOControl(c)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }

    /**
     * An exception the platform did not anticipate, recorded by its type only
     * ({@code "Unexpected error: SocketTimeoutException"}): its message may
     * quote anything the failing library saw.
     */
    public static String unexpected(Throwable error) {
        return "Unexpected error: " + (error == null ? "unknown" : error.getClass().getSimpleName());
    }
}
