package com.thinklab.domain.exception;

/** Domain Exception: an illegal PatchPolicy lifecycle transition, or the policy changed while the write was applied. RFC 7807 mapping: HTTP 409 Conflict. */
public class InvalidPatchPolicyStatusException extends BusinessException {

    private static final String ERROR_CODE = "ERR-PCH-00409";

    public InvalidPatchPolicyStatusException(String message) {
        super(ERROR_CODE, message);
    }
}
