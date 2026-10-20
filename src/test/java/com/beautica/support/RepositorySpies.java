package com.beautica.support;

import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

/** Helpers for {@code @MockitoSpyBean} spies on Spring Data repositories. */
public final class RepositorySpies {

    private RepositorySpies() {
    }

    /**
     * Calls through to the real repository from inside a stubbed answer. A repository spy wraps an
     * interface, so Mockito's {@code callRealMethod()} cannot reach the real query ("cannot call abstract
     * real method"); the spy's default answer — what every unstubbed call uses — does.
     */
    public static Object passThrough(Object repositorySpy, InvocationOnMock invocation) throws Throwable {
        return Mockito.mockingDetails(repositorySpy).getMockCreationSettings().getDefaultAnswer().answer(invocation);
    }
}
