package com.beautica.search.dto;

/**
 * The one thing {@link MasterSearchRequest} and {@link SalonSearchRequest} have in
 * common that the caching layer needs: the raw free-text {@code q} parameter.
 *
 * <p>Exists so {@code SearchCacheResolver} can route a discovery call to the browse
 * or the free-text cache without a type switch over every request record — a switch
 * that would have to be edited (and could be silently forgotten) the day a third
 * discovery surface is added. Deliberately narrow: it exposes {@code q} and nothing
 * else, so it stays a routing contract rather than a second, parallel description of
 * the search request.</p>
 */
public interface FreeTextSearchRequest {

    /** The raw, un-normalised {@code q} query parameter; {@code null} when absent. */
    String q();
}
