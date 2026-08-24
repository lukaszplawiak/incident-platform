-- Replay protection for TOTP codes (backlog #59).
--
-- Fixed: TotpService.verify() checked whether a code matched ANY of the
-- 3 valid time-step windows (~90s tolerance, RFC 6238 clock-skew
-- allowance) but never tracked which step it had already accepted for
-- a given user. A captured, still-valid code (shoulder-surfing,
-- malware, MITM) could be replayed by a second, independent login
-- attempt within that same window and would be accepted again —
-- nothing remembered the step had already been consumed.
ALTER TABLE users
    ADD COLUMN mfa_last_used_time_step BIGINT;

COMMENT ON COLUMN users.mfa_last_used_time_step
    IS 'Last TOTP time-step (epochSeconds/30) accepted for this user (backlog #59). A code whose matched step is <= this value is rejected as a replay, regardless of whether it is otherwise cryptographically valid. NULL until the first successful TOTP verification.';