package com.thinklab.domain.exception;

/** Domain Exception: an illegal PatchFinding transition, or the finding changed while the write was applied. RFC 7807 mapping: HTTP 409 Conflict. */
public class InvalidPatchFindingStatusException extends BusinessException {

    private static final String ERROR_CODE = "ERR-PCH-00409";

    public InvalidPatchFindingStatusException(String message) {
        super(ERROR_CODE, message);
    }
}
