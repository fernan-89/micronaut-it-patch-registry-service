package com.thinklab.domain.exception;

/** Domain Exception: the patch registry is for IT staff and their tools; a REQUESTER is refused. RFC 7807 mapping: HTTP 403 Forbidden. */
public class PatchAccessDeniedException extends BusinessException {

    private static final String ERROR_CODE = "ERR-PCH-00403";

    public PatchAccessDeniedException(String message) {
        super(ERROR_CODE, message);
    }
}
