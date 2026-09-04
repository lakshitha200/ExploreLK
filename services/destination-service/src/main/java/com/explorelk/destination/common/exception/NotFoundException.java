package com.explorelk.destination.common.exception;

import com.explorelk.destination.common.ErrorCode;

/**
 * Nothing published matches the identifier that was asked for.
 *
 * <p>A DRAFT or ARCHIVED row that exists but is not publicly visible raises this
 * too. That is on purpose: telling an anonymous caller "it exists but you cannot
 * see it" leaks the existence of unpublished content, and there is no reason for
 * a public endpoint to distinguish the two cases.
 */
public class NotFoundException extends AppException {

    public NotFoundException(String what, Object identifier) {
        super(ErrorCode.NOT_FOUND, what + " not found: " + identifier);
    }
}
