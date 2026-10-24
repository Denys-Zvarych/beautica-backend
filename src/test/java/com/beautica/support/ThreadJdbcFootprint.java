package com.beautica.support;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.io.Closeable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Thread-scoped JDBC footprint probe: counts connection checkouts, statements and transactions made
 * by ONE thread while it runs one action — and nothing any other thread does.
 *
 * <p><b>Why not Hibernate {@code Statistics}.</b> Those counters are SessionFactory-wide. Every
 * {@code @Scheduled} job ({@code NotificationOutboxDrainWorker}, the cleanup jobs), every
 * {@code @Async}/{@code AFTER_COMMIT} listener left running by a previous test on the same cached
 * context, and the HTTP worker threads of a concurrent request all add to them, so a
 * "zero connections" assertion against global counters is a timing-dependent flake, not a gate.
 * This probe only records work done on the thread that called {@link #footprintOf}/{@link #measure}, so a concurrent
 * job cannot move it. It sits at the {@link DataSource} level, so it sees Hibernate AND
 * {@code JdbcTemplate}/native work alike (Hibernate {@code Statistics} miss the latter).
 *
 * <p><b>Usage.</b> {@code @Import(ThreadJdbcFootprint.Config.class)} on the IT (this gives the class
 * its own cached context, since the primary {@code dataSource} bean is wrapped), then
 * {@code Footprint f = ThreadJdbcFootprint.footprintOf(() -> service.call(...));}. Call the service
 * directly — not over HTTP — because a request runs on a Tomcat worker thread, not the test thread.
 *
 * <p>Only the primary {@code dataSource} bean is wrapped; the dedicated {@code flywayDataSource}
 * pool is left alone.
 */
public final class ThreadJdbcFootprint {

    private static final ThreadLocal<Footprint> ACTIVE = new ThreadLocal<>();

    private ThreadJdbcFootprint() {
    }

    /** Counters recorded on the measuring thread. */
    public static final class Footprint {
        private final AtomicInteger connections = new AtomicInteger();
        private final AtomicInteger statements = new AtomicInteger();
        private final AtomicInteger transactions = new AtomicInteger();

        /** {@code DataSource#getConnection} calls — pool checkouts. */
        public int connections() {
            return connections.get();
        }

        /** {@code prepareStatement}/{@code prepareCall}/{@code createStatement} calls. */
        public int statements() {
            return statements.get();
        }

        /** {@code setAutoCommit(false)} calls — one per Spring/Hibernate transaction begun. */
        public int transactions() {
            return transactions.get();
        }

        @Override
        public String toString() {
            return "Footprint{connections=" + connections() + ", statements=" + statements()
                    + ", transactions=" + transactions() + "}";
        }
    }

    /** Runs {@code action} on the current thread and returns what it did to the database. */
    public static Footprint footprintOf(Runnable action) {
        return measure(() -> {
            action.run();
            return null;
        }).footprint();
    }

    /** As {@link #footprintOf(Runnable)}, also handing back the action's result. */
    public static <T> Measured<T> measure(Supplier<T> action) {
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("nested ThreadJdbcFootprint.measure on one thread");
        }
        Footprint footprint = new Footprint();
        ACTIVE.set(footprint);
        try {
            T result = action.get();
            return new Measured<>(result, footprint);
        } finally {
            ACTIVE.remove();
        }
    }

    public record Measured<T>(T result, Footprint footprint) {
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    static DataSource wrap(DataSource target) {
        InvocationHandler handler = (proxy, method, args) -> {
            Object result = invoke(target, method, args);
            Footprint footprint = ACTIVE.get();
            if (footprint != null && method.getName().equals("getConnection") && result instanceof Connection c) {
                footprint.connections.incrementAndGet();
                return wrap(c, footprint);
            }
            return result;
        };
        return (DataSource) Proxy.newProxyInstance(ThreadJdbcFootprint.class.getClassLoader(),
                new Class<?>[]{DataSource.class, Closeable.class}, handler);
    }

    private static Connection wrap(Connection target, Footprint footprint) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "prepareStatement", "prepareCall", "createStatement" -> footprint.statements.incrementAndGet();
                case "setAutoCommit" -> {
                    if (Boolean.FALSE.equals(args[0]) && target.getAutoCommit()) {
                        footprint.transactions.incrementAndGet();
                    }
                }
                default -> { }
            }
            return invoke(target, method, args);
        };
        return (Connection) Proxy.newProxyInstance(ThreadJdbcFootprint.class.getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }

    /** Wraps the primary {@code dataSource} bean; import on the IT that uses this probe. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        static BeanPostProcessor threadJdbcFootprintDataSourceWrapper() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if ("dataSource".equals(beanName) && bean instanceof DataSource dataSource) {
                        return wrap(dataSource);
                    }
                    return bean;
                }
            };
        }
    }
}
