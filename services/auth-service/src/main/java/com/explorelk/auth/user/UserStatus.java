package com.explorelk.auth.user;

/**
 * Account lifecycle state.
 *
 * <pre>
 *   register -> PENDING_VERIFICATION -> (verify) -> ACTIVE
 *                                                     |
 *                                        SUSPENDED / DISABLED
 * </pre>
 *
 * Checked at login <em>and</em> at refresh — otherwise a suspended user keeps
 * a live session for the full refresh-token lifetime.
 */
public enum UserStatus {

    /** Registered, email not yet confirmed. Cannot authenticate. */
    PENDING_VERIFICATION,

    /** Normal, usable account. */
    ACTIVE,

    /** Temporarily blocked by an admin. Reversible. */
    SUSPENDED,

    /** Permanently blocked. */
    DISABLED;

    /** Only an ACTIVE account may hold a session. */
    public boolean canAuthenticate() {
        return this == ACTIVE;
    }
}
