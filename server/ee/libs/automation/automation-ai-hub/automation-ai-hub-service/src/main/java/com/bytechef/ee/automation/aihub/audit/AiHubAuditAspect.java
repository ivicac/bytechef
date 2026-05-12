/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.aihub.audit;

import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import com.bytechef.platform.connection.audit.AuditCaptureFailedException;
import com.bytechef.platform.connection.audit.AuditCorrelation;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Aspect that intercepts {@link AuditAiHub}-annotated methods, evaluates the data SpEL, and publishes an
 * {@link AiHubAuditEvent} via {@link AiHubAuditPublisher}. Mirrors {@code ConnectionAuditAspect}: boot-time SpEL
 * validation, {@code afterCommit} publish (so rolled-back transactions don't emit), {@code strictAudit} rethrow on
 * capture failure, {@code establishCorrelation} ThreadLocal scope.
 *
 * @version ee
 *
 * @author Ivica Cardic
 */
@Aspect
@Component
@ConditionalOnEEVersion
@SuppressFBWarnings({
    "CT_CONSTRUCTOR_THROW", "SPEL_INJECTION"
})
public class AiHubAuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AiHubAuditAspect.class);

    private final ApplicationContext applicationContext;
    private final AiHubAuditPublisher publisher;
    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final @Nullable MeterRegistry meterRegistry;
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    @SuppressFBWarnings("EI")
    public AiHubAuditAspect(
        ApplicationContext applicationContext, AiHubAuditPublisher publisher,
        ObjectProvider<MeterRegistry> meterRegistryProvider) {

        this.applicationContext = applicationContext;
        this.publisher = publisher;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    /**
     * Boot-time validation of every {@code @AuditAiHub} SpEL expression in the context. Parse failures are logged at
     * ERROR with the offending method so a typo surfaces at startup rather than as a runtime miss the first time the
     * method is invoked. Implementation note: annotations are read off the bean <em>class</em> (not the instantiated
     * bean) to avoid forcing-instantiation of every lazy bean in the context.
     */
    @EventListener(ContextRefreshedEvent.class)
    public void validateAuditAnnotations() {
        int checked = 0;
        int failed = 0;
        int skipped = 0;

        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> beanType;

            try {
                beanType = applicationContext.getType(beanName);
            } catch (RuntimeException typeLookup) {
                skipped++;

                recordValidationSkipped();

                if (log.isDebugEnabled()) {
                    log.debug(
                        "Skipping @AuditAiHub validation for bean '{}': {}",
                        beanName, typeLookup.getClass()
                            .getSimpleName(),
                        typeLookup);
                }

                continue;
            }

            if (beanType == null) {
                continue;
            }

            Class<?> targetClass = beanType.getName()
                .contains("$$SpringCGLIB$$") ? beanType.getSuperclass() : beanType;

            if (targetClass == null) {
                continue;
            }

            for (Method method : targetClass.getDeclaredMethods()) {
                AuditAiHub annotation = method.getAnnotation(AuditAiHub.class);

                if (annotation == null) {
                    continue;
                }

                checked++;

                for (AuditAiHub.AuditData auditData : annotation.data()) {
                    failed += validateExpression(targetClass, method, auditData.value());
                }
            }
        }

        if (log.isInfoEnabled()) {
            log.info(
                "AiHubAuditAspect validated {} @AuditAiHub-annotated method(s); {} SpEL parse failure(s); "
                    + "{} bean(s) skipped due to resolution failure",
                checked, failed, skipped);
        }
    }

    private int validateExpression(Class<?> targetClass, Method method, String expression) {
        try {
            expressionParser.parseExpression(expression);

            return 0;
        } catch (ParseException parseException) {
            log.error(
                "@AuditAiHub SpEL parse failure on {}#{} expression='{}'",
                targetClass.getName(), method.getName(), expression, parseException);

            return 1;
        }
    }

    @Around("@annotation(auditAiHub)")
    public Object establishCorrelation(ProceedingJoinPoint joinPoint, AuditAiHub auditAiHub) throws Throwable {
        if (!auditAiHub.establishCorrelation()) {
            return joinPoint.proceed();
        }

        AuditCorrelation.CorrelationId previous = AuditCorrelation.push(AuditCorrelation.newId());

        try {
            return joinPoint.proceed();
        } finally {
            AuditCorrelation.pop(previous);
        }
    }

    @AfterReturning(pointcut = "@annotation(auditAiHub)", returning = "result")
    public void audit(JoinPoint joinPoint, AuditAiHub auditAiHub, Object result) {
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        Method method = methodSignature.getMethod();
        EvaluationContext evaluationContext = buildEvaluationContext(method, joinPoint.getArgs(), result);

        Map<String, Object> data;

        try {
            data = evaluateAuditData(auditAiHub.data(), evaluationContext);
        } catch (Exception exception) {
            recordAuditFailure();

            log.error(
                "Failed to evaluate audit event {} for method {}",
                auditAiHub.event(),
                joinPoint.getSignature()
                    .toShortString(),
                exception);

            if (auditAiHub.event()
                .isStrictAudit()) {
                throw new AuditCaptureFailedException(
                    "Strict audit capture failed for event " + auditAiHub.event()
                        + "; rolling back the mutation rather than committing without a trail",
                    exception);
            }

            return;
        }

        AiHubAuditEvent eventType = auditAiHub.event();
        Map<String, Object> resolvedData = data;

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.publish(eventType, resolvedData);
                }
            });
        } else {
            publisher.publish(eventType, resolvedData);
        }
    }

    private void recordAuditFailure() {
        if (meterRegistry != null) {
            Counter.builder("bytechef_ai_hub_audit_failed")
                .register(meterRegistry)
                .increment();
        }
    }

    private void recordValidationSkipped() {
        if (meterRegistry != null) {
            Counter.builder("bytechef_ai_hub_audit_validation_skipped")
                .description(
                    "Beans whose @AuditAiHub SpEL could not be validated at boot because the bean failed to "
                        + "resolve during context refresh")
                .register(meterRegistry)
                .increment();
        }
    }

    private EvaluationContext buildEvaluationContext(Method method, Object[] args, Object result) {
        StandardEvaluationContext context = new StandardEvaluationContext();

        context.setBeanResolver(new BeanFactoryResolver(applicationContext));

        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);

        if (parameterNames != null) {
            for (int i = 0; i < parameterNames.length; i++) {
                context.setVariable(parameterNames[i], args[i]);
            }
        }

        context.setVariable("result", result);

        return context;
    }

    private Map<String, Object> evaluateAuditData(
        AuditAiHub.AuditData[] auditDataEntries, EvaluationContext evaluationContext) {

        Map<String, Object> data = new HashMap<>();

        for (AuditAiHub.AuditData entry : auditDataEntries) {
            Object value = expressionParser.parseExpression(entry.value())
                .getValue(evaluationContext);

            data.put(entry.key(), value != null ? value.toString() : "null");
        }

        String correlationId = AuditCorrelation.current();

        if (correlationId != null) {
            data.putIfAbsent("correlationId", correlationId);
        }

        return data;
    }
}
