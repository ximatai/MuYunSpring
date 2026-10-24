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
 * provider and the browser proof kept by the initiating browser are stored only as hashes. The transaction
 * is completed once by the provider callback and redeemed once by the browser that holds the proof.
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

    @Column(name = "purpose", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Login or bind")
    private ExternalLoginPurpose purpose;

    @Column(name = "provider_id", type = ColumnType.VARCHAR, length = 64, nullable = false,
            comment = "Identity provider id")
    private String providerId;

    @Column(name = "initiator_user_id", type = ColumnType.VARCHAR, length = 64,
            comment = "Account that started a bind transaction")
    private String initiatorUserId;

    @Column(name = "status", type = ColumnType.VARCHAR, length = 32, nullable = false, comment = "Transaction status")
    private ExternalLoginStatus status;

    @Column(name = "failure_code", type = ColumnType.VARCHAR, length = 64, comment = "Stable denial code")
    private String failureCode;

    @Column(name = "result_user_id", type = ColumnType.VARCHAR, length = 64, comment = "Resolved local account")
    private String resultUserId;

    @Column(name = "result_identity_id", type = ColumnType.VARCHAR, length = 64,
            comment = "Binding used or created by the transaction")
    private String resultIdentityId;

    @Column(name = "expires_at", type = ColumnType.TIMESTAMP, nullable = false, comment = "Expires at")
    private Instant expiresAt;

    @Column(name = "completed_at", type = ColumnType.TIMESTAMP, comment = "Provider callback completed at")
    private Instant completedAt;

    @Column(name = "redeemed_at", type = ColumnType.TIMESTAMP, comment = "Browser redeemed at")
    private Instant redeemedAt;
}
