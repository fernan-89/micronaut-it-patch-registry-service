package com.thinklab.domain.exception;

/** Domain Exception: no patch policy with that id in the tenant (another tenant's answers the same). RFC 7807 mapping: HTTP 404 Not Found. */
public class PatchPolicyNotFoundException extends BusinessException {

    private static final String ERROR_CODE = "ERR-PCH-00404";

    public PatchPolicyNotFoundException(String message) {
        super(ERROR_CODE, message);
    }
}
