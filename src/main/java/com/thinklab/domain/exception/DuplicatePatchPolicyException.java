package com.thinklab.domain.exception;

/** Domain Exception: a patch policy with that name already exists in the organisation. RFC 7807 mapping: HTTP 409 Conflict. */
public class DuplicatePatchPolicyException extends BusinessException {

    private static final String ERROR_CODE = "ERR-PCH-00409";

    public DuplicatePatchPolicyException(String message) {
        super(ERROR_CODE, message);
    }
}
