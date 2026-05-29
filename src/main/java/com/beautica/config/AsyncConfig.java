package com.beautica.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    // SMTP pool for invite and admin notification emails — FCM/APNs push gets a dedicated pool in Phase 5.8+.
    // Wrapped in DelegatingSecurityContextTaskExecutor so the calling thread's SecurityContext
    // (Authentication / actor identity) propagates into async tasks. Without this wrapper any
    // @Async method reading SecurityContextHolder.getContext().getAuthentication() observes null.
    @Bean(name = "emailExecutor")
    public TaskExecutor emailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(150);
        executor.setThreadNamePrefix("email-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        // Propagate the webapp classloader into email-* threads so that
        // ServiceLoader (jakarta.mail.util.StreamProvider) resolves META-INF/services/
        // entries from the application classpath rather than the system classloader.
        executor.setTaskDecorator(runnable -> {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            return () -> {
                ClassLoader prev = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(cl);
                try {
                    runnable.run();
                } finally {
                    Thread.currentThread().setContextClassLoader(prev);
                }
            };
        });
        executor.initialize();
        return new DelegatingSecurityContextTaskExecutor(executor);
    }

    // FCM/APNs push — dedicated pool to prevent SMTP starvation under push burst.
    // Same SecurityContext propagation rationale as emailExecutor.
    @Bean(name = "pushExecutor")
    public TaskExecutor pushExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("push-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        // Same classloader propagation as emailExecutor — future ServiceLoader-based libs
        // (FCM SDK, APNs provider) need the webapp classloader on push-* threads.
        executor.setTaskDecorator(runnable -> {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            return () -> {
                ClassLoader prev = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(cl);
                try {
                    runnable.run();
                } finally {
                    Thread.currentThread().setContextClassLoader(prev);
                }
            };
        });
        executor.initialize();
        return new DelegatingSecurityContextTaskExecutor(executor);
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("Uncaught async exception in {}.{}(): {}",
                        method.getDeclaringClass().getSimpleName(), method.getName(), ex.getClass().getName(), ex);
    }
}
