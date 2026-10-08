package com.incidentplatform.notification.channel;

import com.incidentplatform.notification.config.NotificationChannelProperties;
import com.incidentplatform.notification.dto.NotificationRequest;
import com.incidentplatform.shared.domain.Severity;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EmailNotificationChannel implements NotificationChannel {

    private static final Logger log =
            LoggerFactory.getLogger(EmailNotificationChannel.class);

    /** An RFC 3463 enhanced status code at the start of a reply's text, e.g. {@code 5.1.1}. */
    private static final Pattern ENHANCED_STATUS = Pattern.compile("^\\s*(?:\\d{3}[ -])?\\s*([245])\\.(\\d{1,3})\\.\\d{1,3}\\b");

    private final boolean enabled;
    private final String fromAddress;
    private final JavaMailSender mailSender;

    public EmailNotificationChannel(
            JavaMailSender mailSender,
            NotificationChannelProperties properties) {
        this.mailSender  = mailSender;
        this.enabled     = properties.channels().email().enabled();
        this.fromAddress = properties.channels().email().from();
    }

    @Override
    public String channelName() {
        return "EMAIL";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void send(NotificationRequest request) {
        try {
            final MimeMessage message = mailSender.createMimeMessage();
            final MimeMessageHelper helper =
                    new MimeMessageHelper(message, false, "UTF-8");

            helper.setFrom(fromAddress);
            helper.setTo(request.recipient());
            helper.setSubject(request.subject());
            helper.setText(buildHtmlBody(request), true);

            mailSender.send(message);

            log.info("Email sent: to={}, subject={}, incidentId={}",
                    request.recipient(), request.subject(),
                    request.incidentId());

        } catch (Exception e) {
            // Backlog #0-93: the mail library's message (an SMTP server's reply,
            // the relay's host and port) stays on the cause, for the log line
            // the caller writes; what is recorded is the reason alone.
            throw new NotificationException("EMAIL", request.recipient(), classify(e, request.recipient()), e);
        }
    }

    /**
     * Maps a failed send to the platform's reason (backlog #0-93), from the
     * exception and everything it wraps: Spring's {@code MailSendException}
     * keeps each message's failure in {@code getMessageExceptions()}, not as
     * its cause.
     * <ul>
     *   <li>authentication to the server failed: the operator's to fix;</li>
     *   <li>an address the server refused as an address, or could not parse:
     *       the tenant's;</li>
     *   <li>no connection, a timeout, or addresses the server deferred (4xx):
     *       may pass on a later attempt;</li>
     *   <li>anything else, a refusal on the server's policy included.</li>
     * </ul>
     * The first match in that order wins, whatever its depth.
     *
     * <p>"Refused as an address" (review): a 5xx answer to RCPT is not always
     * about the address. "550 5.7.1 Relaying denied" is the platform's relay
     * refusing to deliver, a misconfiguration the operator must hear about;
     * reported as the tenant's rejected address it would be logged at WARN and
     * hidden. So only an {@code SMTPAddressFailedException} whose reply names
     * an addressing problem counts: an RFC 3463 enhanced status {@code 5.1.x},
     * or, from a server that sends none, the basic codes RFC 5321 gives for a
     * mailbox (550, 551, 553). Any other refusal of an address is
     * {@link NotificationFailureReason#EMAIL_FAILED}, logged at ERROR. The
     * reply is only read here, never recorded.
     *
     * <p>Accepted (third review): a server that sends no enhanced code and
     * answers a relay refusal with a bare "550 Relaying denied" is read as a
     * rejected address, logged at WARN. RFC 5321 gives 550 to "mailbox
     * unavailable", current servers send an enhanced code ("550 5.7.1", which
     * is caught), and matching the reply's free text would be a guess about
     * one server's wording. Such a relay still shows: every send to that
     * server fails, counted under {@code EMAIL_RECIPIENT_REJECTED} for every
     * recipient alike.
     *
     * <p>Second review: the same holds for the platform's own address. An
     * {@code AddressException} is the tenant's only when what failed to parse
     * is the recipient (a malformed {@code from} is the operator's
     * configuration), and a refusal only when it answered {@code RCPT}.
     *
     * @param recipient the address the message was for
     */
    static NotificationFailureReason classify(Throwable failure, String recipient) {
        final List<Throwable> chain = flatten(failure);
        if (chain.stream().anyMatch(t -> t instanceof MailAuthenticationException
                || t instanceof AuthenticationFailedException)) {
            return NotificationFailureReason.EMAIL_AUTHENTICATION_FAILED;
        }
        if (chain.stream().anyMatch(t -> (t instanceof AddressException malformed
                        && recipient != null && recipient.equals(malformed.getRef()))
                || (t instanceof SMTPAddressFailedException refused && isAddressRefusal(refused)))) {
            return NotificationFailureReason.EMAIL_RECIPIENT_REJECTED;
        }
        if (chain.stream().anyMatch(t -> t instanceof ConnectException
                || t instanceof UnknownHostException
                || t instanceof SocketTimeoutException
                || t instanceof NoRouteToHostException
                || (t instanceof SMTPAddressFailedException deferred && deferred.getReturnCode() / 100 == 4)
                || (t instanceof SendFailedException sfe && hasAny(sfe.getValidUnsentAddresses())
                        && !hasAny(sfe.getInvalidAddresses())))) {
            return NotificationFailureReason.EMAIL_TRANSPORT_UNAVAILABLE;
        }
        return NotificationFailureReason.EMAIL_FAILED;
    }

    private static boolean isAddressRefusal(SMTPAddressFailedException refused) {
        final String command = refused.getCommand();
        if (refused.getReturnCode() / 100 != 5
                || (command != null && !command.regionMatches(true, 0, "RCPT", 0, 4))) {
            return false;
        }
        final Matcher enhanced = ENHANCED_STATUS.matcher(
                refused.getMessage() == null ? "" : refused.getMessage());
        if (enhanced.find()) {
            return "1".equals(enhanced.group(2));
        }
        final int code = refused.getReturnCode();
        return code == 550 || code == 551 || code == 553;
    }

    /** The failure and everything it wraps, each once, at most 32. */
    private static List<Throwable> flatten(Throwable failure) {
        final List<Throwable> found = new ArrayList<>();
        final Deque<Throwable> pending = new ArrayDeque<>();
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (failure != null) {
            pending.add(failure);
        }
        while (!pending.isEmpty() && found.size() < 32) {
            final Throwable t = pending.poll();
            if (!seen.add(t)) {
                continue;
            }
            found.add(t);
            if (t.getCause() != null) {
                pending.add(t.getCause());
            }
            if (t instanceof MessagingException me && me.getNextException() != null) {
                pending.add(me.getNextException());
            }
            if (t instanceof MailSendException mse) {
                pending.addAll(Arrays.asList(mse.getMessageExceptions()));
            }
        }
        return found;
    }

    private static boolean hasAny(Address[] addresses) {
        return addresses != null && addresses.length > 0;
    }

    /**
     * Builds the HTML email body.
     *
     * <h2>Fixed: unescaped HTML injection via subject/message</h2>
     * {@code request.subject()}/{@code request.message()} ultimately
     * originate from external alert sources (Prometheus, Wazuh — see
     * ingestion-service's normalizers) — free-text fields this platform
     * does not control. Previously interpolated directly into raw HTML via
     * {@code String.format}, so a malicious or malformed alert title/
     * description containing HTML markup would be embedded verbatim in
     * the email body. Most modern mail clients strip {@code <script>} and
     * disable JS execution in rendered HTML email by policy, so classic
     * XSS wasn't the primary concern here — but unescaped markup could
     * still break the email's layout or inject deceptive content dressed
     * up as a legitimate platform notification. Fixed using Spring's own
     * {@link HtmlUtils#htmlEscape}, applied to both fields — no new
     * dependency, this codebase's first use of HTML escaping (nothing
     * else builds raw HTML from external strings today).
     */
    private String buildHtmlBody(NotificationRequest request) {
        final String severityColor = switch (request.severity()) {
            case CRITICAL -> "#FF0000";
            case HIGH     -> "#FF6600";
            case MEDIUM   -> "#FFAA00";
            case LOW      -> "#00AA00";
        };

        final String safeSubject = escapeHtml(request.subject());
        final String safeMessage = escapeHtml(request.message());

        return String.format("""
                <html>
                <body style="font-family: Arial, sans-serif;">
                    <h2 style="color: %s;">%s</h2>
                    <p>%s</p>
                    <hr/>
                    <p><strong>Incident ID:</strong> %s</p>
                    <p><strong>Severity:</strong>
                        <span style="color: %s;">%s</span>
                    </p>
                    <p><strong>Tenant:</strong> %s</p>
                </body>
                </html>
                """,
                severityColor,
                safeSubject,
                safeMessage,
                request.incidentId(),
                severityColor,
                request.severity().name(),
                // The tenant id comes from a Kafka header or payload without a format
                // check, and this email now also goes to the platform operator
                // (backlog #0-18), so it is escaped like the subject and message.
                escapeHtml(request.tenantId())
        );
    }

    /**
     * Null-safe wrapper around {@link HtmlUtils#htmlEscape(String)} —
     * {@code request.message()} is a plain, nullable field on
     * {@link NotificationRequest} (see e.g.
     * {@code SmsNotificationChannelTest}'s "should not throw when message
     * is null" case), and {@code HtmlUtils.htmlEscape} does not itself
     * accept null.
     */
    private static String escapeHtml(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }
}