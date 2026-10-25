package net.ximatai.muyun.spring.iam.federation;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.CompositeIndex;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

import java.time.Instant;

/**
 * Server-side state of one external authorization round trip. The opaque {@code state} sent to the
 * provider and the browser proof kept by the initiating browser are stored only as hashes; the PKCE verifier
 * never leaves the server and is cleared once the transaction completes. The transaction
 * is completed exactly once, by the browser that started it, presenting both values together with the
 * provider callback parameters.
 */
@Getter
@Setter
@Table(name = "iam_external_login_transaction", comment = "External login transaction")
@CompositeIndex(columns = {"state_hash"}, unique = true)
public class ExternalLoginTransaction extends StandardEntity {
    @Column(name = "state_hash", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "SHA-256 of the opaque state")
    private String stateHash;

    @Column(name = "browser_proof_hash", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "SHA-256 of the initiating browser proof")
    private String browserProofHash;

    @Column(name = "pkce_verifier", type = ColumnType.VARCHAR, length = 128,
            comment = "PKCE code verifier, server-only and cleared on completion")
    private String pkceVerifier;

    @Column(name = "purpose", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Login or bind")
    private ExternalLoginPurpose purpose;

    @Column(name = "provider_id", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Identity provider id")
    private String providerId;

    @Column(name = "initiator_user_id", type = ColumnType.VARCHAR, length = 64,
            comment = "Account that started a bind transaction")
    private String initiatorUserId;

    @Column(name = "initiator_session_id", type = ColumnType.VARCHAR, length = 64,
            comment = "Session that started a bind transaction; it must still be active to complete")
    private String initiatorSessionId;

    @Column(name = "status", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Transaction status")
    private ExternalLoginStatus status;

    @Column(name = "failure", type = ColumnType.VARCHAR, length = 64, comment = "Stable denial reason")
    private ExternalLoginFailure failure;

    @Column(name = "result_user_id", type = ColumnType.VARCHAR, length = 64, comment = "Resolved local account")
    private String resultUserId;

    @Column(name = "result_identity_id", type = ColumnType.VARCHAR, length = 64,
            comment = "Binding used or created by the transaction")
    private String resultIdentityId;

    @Column(name = "expires_at", type = ColumnType.TIMESTAMP, nullable = false, comment = "Expires at")
    private Instant expiresAt;

    @Column(name = "completed_at", type = ColumnType.TIMESTAMP, comment = "Completed by the initiating browser at")
    private Instant completedAt;
}
