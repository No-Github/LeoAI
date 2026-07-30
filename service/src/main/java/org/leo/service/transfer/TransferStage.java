package org.leo.service.transfer;

/** Observable execution stage shared by upload and download engines. */
public enum TransferStage {
    CREATED,
    PREPARING,
    TRANSFERRING,
    VERIFYING_REMOTE,
    VERIFYING_LOCAL,
    COMMITTING,
    FINISHED
}
