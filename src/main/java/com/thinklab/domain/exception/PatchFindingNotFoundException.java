package com.thinklab.domain.exception;

/** Domain Exception: no patch finding with that id in the tenant (another tenant's answers the same). RFC 7807 mapping: HTTP 404 Not Found. */
public class PatchFindingNotFoundException extends BusinessException {

    private static final String ERROR_CODE = "ERR-PCH-00404";

    public PatchFindingNotFoundException(String message) {
        super(ERROR_CODE, message);
    }
}
