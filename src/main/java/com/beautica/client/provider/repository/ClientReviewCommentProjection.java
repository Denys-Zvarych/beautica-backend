package com.beautica.client.provider.repository;

import java.util.UUID;

/**
 * B565 (REV-44) — one visible non-blank comment about a client, from any provider. No author, salon, master or service
 * column is selected; {@code own} is computed for the viewer inside the statement.
 */
public interface ClientReviewCommentProjection {

    UUID getReviewId();

    /** {@code YYYY-MM} of the rated visit in Europe/Kyiv. */
    String getVisitMonth();

    Integer getStars();

    String getComment();

    Boolean getOwn();
}
