package com.explorelk.destination.destination;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

/**
 * <p><strong>Public reads are status-qualified here, not in the controller.</strong>
 * Every finder a public endpoint may use takes a {@link ContentStatus}, so the
 * filter cannot be forgotten when someone adds an endpoint later. The unqualified
 * {@code findById} inherited from {@link JpaRepository} exists for the admin side
 * (Step 5), which is supposed to see drafts.
 *
 * <p>{@link JpaSpecificationExecutor} backs the composable search in Step 4 — see
 * {@link com.explorelk.destination.search.DestinationSearchSpecs}.
 */
public interface DestinationRepository
        extends JpaRepository<Destination, UUID>, JpaSpecificationExecutor<Destination> {

    Optional<Destination> findByIdAndStatus(UUID id, ContentStatus status);

    Optional<Destination> findBySlugAndStatus(String slug, ContentStatus status);

    boolean existsBySlug(String slug);
}
