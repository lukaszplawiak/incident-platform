package com.incidentplatform.auth.api;

import com.incidentplatform.auth.dto.CancelMfaRecoveryRequest;
import com.incidentplatform.auth.service.MfaRecoveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The account's side of an operator MFA recovery (backlog #0-90): the cancel
 * link of the notice lands here. Public, like reset-password: the owner of a
 * locked-out account holds only the token from the email, whose tenant and
 * user come from the token, never from a header. The token can only stop a
 * reset, never cause one; that whoever holds the mailbox can keep stopping
 * them is an accepted limit ({@code MfaRecoveryService}).
 */
@RestController
@RequestMapping("/api/v1/auth/mfa-recovery")
@Tag(name = "Authentication")
public class MfaRecoveryCancelController {

    private final MfaRecoveryService recoveryService;

    public MfaRecoveryCancelController(MfaRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @PostMapping(value = "/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Cancel an account recovery the platform operator was asked for",
            description = """
                    Uses the token from the "Account recovery requested" email. Single-use.
                    Answers 204 whether or not the request was still open (it may have been
                    cancelled already), so the link reveals nothing beyond what the email said.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "No open recovery request remains"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "401", description = "Token invalid, expired, or already used")
    })
    public ResponseEntity<Void> cancel(@Valid @RequestBody CancelMfaRecoveryRequest request) {
        recoveryService.cancelByAccount(request.token());
        return ResponseEntity.noContent().build();
    }
}
