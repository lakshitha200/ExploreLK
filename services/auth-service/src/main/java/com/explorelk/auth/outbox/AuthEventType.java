package com.explorelk.auth.outbox;

/**
 * The seven events this service publishes, and nothing else.
 *
 * <p>An enum rather than string literals at the call sites: these names are a
 * contract other services subscribe to, and a typo in one would compile, deploy
 * and then silently deliver an event nobody is listening for.
 *
 * <table>
 *   <caption>Who cares about what</caption>
 *   <tr><th>Event</th><th>Emitted when</th><th>Consumed by</th></tr>
 *   <tr><td>{@code USER_REGISTERED}</td><td>registration commits</td>
 *       <td>Notification — verification email</td></tr>
 *   <tr><td>{@code USER_EMAIL_VERIFIED}</td><td>verification succeeds</td>
 *       <td>Notification — welcome</td></tr>
 *   <tr><td>{@code USER_SUSPENDED}</td><td>an admin suspends</td>
 *       <td>Booking, Trip — block activity</td></tr>
 *   <tr><td>{@code USER_DISABLED}</td><td>an admin disables</td>
 *       <td>Booking, Trip</td></tr>
 *   <tr><td>{@code PROVIDER_REGISTERED}</td><td>a PROVIDER registers</td>
 *       <td>Notification — alert an admin there is something to approve</td></tr>
 *   <tr><td>{@code PROVIDER_APPROVED}</td><td>an admin approves one</td>
 *       <td>Experience — allow publishing; Notification</td></tr>
 *   <tr><td>{@code ADMIN_CREATED}</td><td>a SUPER_ADMIN creates an admin</td>
 *       <td>Audit</td></tr>
 * </table>
 *
 * <p><strong>No event ever carries a password hash.</strong> Kafka retains
 * records for days and every consumer sees all of them, so an event is the
 * worst possible place to put a credential. The verification token is the one
 * sensitive value that does travel, because the Notification Service cannot
 * build the link without it.
 */
public enum AuthEventType {

    USER_REGISTERED,
    USER_EMAIL_VERIFIED,
    USER_SUSPENDED,
    USER_DISABLED,

    PROVIDER_REGISTERED,
    PROVIDER_APPROVED,

    ADMIN_CREATED;

    /**
     * Every aggregate in this service is a user, which is why the column has a
     * {@code CHECK (aggregate_type IN ('USER'))} constraint rather than being
     * free text. Named once here so the two cannot drift apart.
     */
    public static final String AGGREGATE_TYPE = "USER";
}
