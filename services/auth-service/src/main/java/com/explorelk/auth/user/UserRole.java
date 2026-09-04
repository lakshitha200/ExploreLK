package com.explorelk.auth.user;

/**
 * One role per user — stored as a VARCHAR + CHECK constraint, not a join table.
 * The JWT carries exactly one of these.
 */
public enum UserRole {

    /** Default consumer role. Created by public registration. */
    TRAVELER,

    /** Sells experiences. Created by public registration, but must be approved before publishing. */
    PROVIDER,

    /** Platform staff. Created only by a SUPER_ADMIN. */
    ADMIN,

    /** Created only by the startup bootstrap. Never by any API. */
    SUPER_ADMIN;

    /** Roles a member of the public is allowed to choose at registration. */
    public boolean isPubliclyRegisterable() {
        return this == TRAVELER || this == PROVIDER;
    }
}
