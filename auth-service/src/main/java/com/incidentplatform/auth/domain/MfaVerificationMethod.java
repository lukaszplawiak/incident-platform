package com.incidentplatform.auth.domain;

/**
 * How a platform operator verified the person asking to recover a customer
 * tenant admin's MFA (backlog #0-90), outside the account's own channels
 * (its password and mailbox are what may have been taken). Recorded with
 * the request and shown in both tenants' audit trails; the operator's note
 * says the details.
 */
public enum MfaVerificationMethod {
    /** A video call against an identity document or a known face. */
    VIDEO_CALL,
    /** A call back to a phone number known from before the request (a contract, an earlier ticket). */
    KNOWN_PHONE_CALLBACK,
    /** A DNS TXT record the operator asked for, on the customer's own domain. */
    DNS_TXT_RECORD,
    /** A signed letter or document from the customer organisation. */
    SIGNED_DOCUMENT,
    /** Anything else; the note must then say what was checked. */
    OTHER
}
