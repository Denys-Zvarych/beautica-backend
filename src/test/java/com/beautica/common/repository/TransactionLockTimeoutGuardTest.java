package com.beautica.common.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.Repository;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.withSettings;

/**
 * Structural guard: every JPQL {@code @Lock} finder in {@code com.beautica} bounds its row-lock wait through
 * {@link TransactionLockTimeout}.
 *
 * <p>The application pool sets no session {@code lock_timeout}, so a {@code @Lock} finder that is called directly
 * waits unbounded until the 5s {@code statement_timeout} (503) instead of failing fast with {@code 55P03} (409).
 * The convention this test enforces: the annotated method is named {@code <name>NoLockTimeout}, its repository
 * extends {@link TransactionLockTimeout}, and a {@code default <name>(same params)} wrapper with the same return
 * type exists that calls {@link TransactionLockTimeout#applyTransactionLockTimeout()} BEFORE the locked finder.
 * The call order is checked behaviourally on a Mockito mock that runs only the default methods for real.
 */
@DisplayName("TransactionLockTimeout guard — every @Lock finder is wrapped by a lock_timeout default method")
class TransactionLockTimeoutGuardTest {

    private static final String BASE_PACKAGE = "com.beautica";
    private static final String SUFFIX = "NoLockTimeout";

    @Test
    @DisplayName("the scan finds the repositories (non-vacuous): at least the 12 known @Lock finders")
    void should_findKnownLockFinders_when_scanningRepositories() throws Exception {
        List<Method> lockMethods = lockMethods(repositoryInterfaces());

        assertThat(lockMethods).hasSizeGreaterThanOrEqualTo(12);
    }

    @Test
    @DisplayName("every @Lock method is named *NoLockTimeout on a TransactionLockTimeout repository with a matching default wrapper")
    void should_haveMatchingDefaultWrapper_when_methodIsAnnotatedWithLock() throws Exception {
        List<String> violations = new ArrayList<>();

        for (Method locked : lockMethods(repositoryInterfaces())) {
            String where = locked.getDeclaringClass().getSimpleName() + "#" + locked.getName();
            if (!locked.getName().endsWith(SUFFIX)) {
                violations.add(where + ": @Lock method must be named <name>" + SUFFIX);
                continue;
            }
            if (!TransactionLockTimeout.class.isAssignableFrom(locked.getDeclaringClass())) {
                violations.add(where + ": repository must extend TransactionLockTimeout");
                continue;
            }
            Method wrapper = wrapperFor(locked);
            if (wrapper == null || !wrapper.isDefault()) {
                violations.add(where + ": missing default wrapper " + wrapperName(locked) + "(same params)");
            } else if (!wrapper.getGenericReturnType().equals(locked.getGenericReturnType())) {
                violations.add(where + ": wrapper return type differs from the locked finder");
            }
        }

        assertThat(violations).as("unbounded @Lock finders").isEmpty();
    }

    @Test
    @DisplayName("every wrapper applies the transaction lock_timeout BEFORE delegating to the @Lock finder")
    void should_applyLockTimeoutBeforeLockedFinder_when_wrapperIsCalled() throws Exception {
        List<String> violations = new ArrayList<>();

        for (Method locked : lockMethods(repositoryInterfaces())) {
            Method wrapper = wrapperFor(locked);
            if (wrapper == null) {
                continue; // reported by the structural test
            }
            Class<?> repo = locked.getDeclaringClass();
            Object mock = Mockito.mock(repo, withSettings().defaultAnswer(realDefaultMethodsOnly()));
            Object[] args = Arrays.stream(wrapper.getParameterTypes()).map(TransactionLockTimeoutGuardTest::dummy).toArray();

            wrapper.invoke(mock, args);

            InOrder order = inOrder(mock);
            try {
                order.verify((TransactionLockTimeout) mock).applyTransactionLockTimeout();
                // Same dummy arguments the wrapper received, so plain equality matching suffices.
                locked.invoke(order.verify(mock), args);
            } catch (AssertionError | ReflectiveOperationException e) {
                Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : e;
                violations.add(repo.getSimpleName() + "#" + wrapper.getName()
                        + ": must call applyTransactionLockTimeout() then " + locked.getName()
                        + " (" + cause.getClass().getSimpleName() + ")");
            }
        }

        assertThat(violations).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static List<Class<?>> repositoryInterfaces() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isInterface() && definition.getMetadata().isIndependent();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));
        List<Class<?>> result = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            result.add(Class.forName(candidate.getBeanClassName()));
        }
        return result;
    }

    private static List<Method> lockMethods(List<Class<?>> repositories) {
        return repositories.stream()
                .flatMap(r -> Arrays.stream(r.getDeclaredMethods()))
                .filter(m -> m.isAnnotationPresent(Lock.class))
                .toList();
    }

    private static String wrapperName(Method locked) {
        return locked.getName().substring(0, locked.getName().length() - SUFFIX.length());
    }

    private static Method wrapperFor(Method locked) {
        if (!locked.getName().endsWith(SUFFIX)) {
            return null;
        }
        try {
            return locked.getDeclaringClass().getDeclaredMethod(wrapperName(locked), locked.getParameterTypes());
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** Runs {@code default} methods for real; every abstract repository method answers with Mockito defaults. */
    private static Answer<Object> realDefaultMethodsOnly() {
        return invocation -> invocation.getMethod().isDefault() && !Modifier.isAbstract(invocation.getMethod().getModifiers())
                ? invocation.callRealMethod()
                : Mockito.RETURNS_DEFAULTS.answer(invocation);
    }

    private static Object dummy(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == char.class) {
            return '\0';
        }
        return type == byte.class ? (byte) 0 : type == short.class ? (short) 0 : 0;
    }
}
