package com.incidentplatform.shared.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AuditText (backlog #0-84)")
class AuditTextTest {

    @Test
    @DisplayName("a short message is kept as it is")
    void shortMessageKept() {
        assertThat(AuditText.error("SMTP connection refused")).isEqualTo("SMTP connection refused");
    }

    @Test
    @DisplayName("no message, or a blank one, is 'unknown'")
    void noMessage() {
        assertThat(AuditText.error(null)).isEqualTo("unknown");
        assertThat(AuditText.error("  ")).isEqualTo("unknown");
    }

    @Test
    @DisplayName("a long message is cut to 500 characters, marked as cut")
    void longMessageCut() {
        final String cut = AuditText.error("x".repeat(300_000));

        assertThat(cut).hasSize(AuditText.MAX_ERROR_LENGTH).endsWith("...");
        assertThat(AuditText.error("y".repeat(AuditText.MAX_ERROR_LENGTH)))
                .as("exactly the limit is not cut").hasSize(AuditText.MAX_ERROR_LENGTH).doesNotContain("...");
    }

    @Test
    @DisplayName("line breaks and other control characters become spaces")
    void oneLine() {
        assertThat(AuditText.error("first\r\nsecond\tthird\u0000")).isEqualTo("first  second third ");
    }

    @Test
    @DisplayName("Unicode line and paragraph separators become spaces too (found in review)")
    void unicodeSeparators() {
        assertThat(AuditText.error("a\u2028b\u2029c\u0085d")).isEqualTo("a b c d");
    }

    @Test
    @DisplayName("the cut never splits a surrogate pair (found in review)")
    void cutKeepsSurrogatePairs() {
        // An emoji is two chars; place one so that its high half sits at the cut.
        final String message = "x".repeat(AuditText.MAX_ERROR_LENGTH - 4) + "\uD83D\uDE00" + "y".repeat(10);

        final String cut = AuditText.error(message);

        assertThat(cut).endsWith("...").hasSizeLessThanOrEqualTo(AuditText.MAX_ERROR_LENGTH);
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 4))).isFalse();
    }

    @Test
    @DisplayName("an unexpected exception is recorded by its type only, never its message")
    void unexpectedByTypeOnly() {
        assertThat(AuditText.unexpected(new SocketTimeoutException("GET https://hooks.example/T0/secret-path")))
                .isEqualTo("Unexpected error: SocketTimeoutException");
        assertThat(AuditText.unexpected(null)).isEqualTo("Unexpected error: unknown");
    }
}
