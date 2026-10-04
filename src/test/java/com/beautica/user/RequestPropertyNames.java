package com.beautica.user;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Every name under which Jackson could bind a JSON property onto a request type — the extraction
 * {@link RequestDtoAvatarFieldArchitectureTest} applies to production, kept package-private and
 * separate so the test-source fixtures in {@link AvatarPropertyFixtures} prove the SAME logic flags
 * a planted avatar property without scanning production.
 *
 * <p>Collected for the type and every superclass up to (excluding) {@link Object}/{@link Record}:
 * <ul>
 *   <li>record component names;</li>
 *   <li>non-static, non-synthetic declared field names;</li>
 *   <li>parameter names of {@link JsonCreator} constructors and static factory methods;</li>
 *   <li>setter-derived names ({@code setFooBar(x)} → {@code fooBar});</li>
 *   <li>the {@link JsonProperty}/{@link JsonSetter} value and {@link JsonAlias} values on any of the
 *       above elements and on any declared method or creator parameter — which catches a renamed
 *       binding such as {@code @JsonProperty("avatarUrl") String pic}.</li>
 * </ul>
 */
final class RequestPropertyNames {

    private static final String AVATAR = "avatar";
    private static final String SETTER_PREFIX = "set";

    private RequestPropertyNames() {
    }

    /** {@code Type#property} entries whose bindable name contains {@code avatar} (case-insensitive). */
    static List<String> avatarProperties(Class<?> type) {
        return of(type).stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).contains(AVATAR))
                .map(name -> type.getName() + "#" + name)
                .toList();
    }

    /** All bindable property names of {@code type} and its superclasses, de-duplicated, in scan order. */
    static Set<String> of(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        for (Class<?> c = type; c != null && c != Object.class && c != Record.class; c = c.getSuperclass()) {
            collectDeclared(c, names);
        }
        return names;
    }

    private static void collectDeclared(Class<?> c, Set<String> names) {
        List<AnnotatedElement> annotated = new ArrayList<>();
        RecordComponent[] components = c.getRecordComponents();
        if (components != null) {
            for (RecordComponent rc : components) {
                names.add(rc.getName());
                annotated.add(rc);
            }
        }
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                continue;
            }
            names.add(f.getName());
            annotated.add(f);
        }
        for (Constructor<?> ctor : c.getDeclaredConstructors()) {
            annotated.addAll(creatorParameters(ctor, names));
        }
        for (Method m : c.getDeclaredMethods()) {
            if (m.isSynthetic() || m.isBridge()) {
                continue;
            }
            annotated.add(m);
            if (Modifier.isStatic(m.getModifiers())) {
                annotated.addAll(creatorParameters(m, names));
            } else if (isSetter(m)) {
                names.add(decapitalize(m.getName().substring(SETTER_PREFIX.length())));
            }
        }
        annotated.forEach(e -> addJsonNames(e, names));
    }

    /** Parameters of a {@link JsonCreator}; their source names are added when compiled with {@code -parameters}. */
    private static List<Parameter> creatorParameters(Executable executable, Set<String> names) {
        if (!executable.isAnnotationPresent(JsonCreator.class)) {
            return List.of();
        }
        List<Parameter> params = List.of(executable.getParameters());
        params.stream().filter(Parameter::isNamePresent).map(Parameter::getName).forEach(names::add);
        return params;
    }

    private static boolean isSetter(Method m) {
        return m.getName().length() > SETTER_PREFIX.length()
                && m.getName().startsWith(SETTER_PREFIX)
                && m.getParameterCount() == 1;
    }

    private static void addJsonNames(AnnotatedElement e, Set<String> names) {
        JsonProperty prop = e.getAnnotation(JsonProperty.class);
        if (prop != null && !prop.value().isEmpty()) {
            names.add(prop.value());
        }
        JsonSetter setter = e.getAnnotation(JsonSetter.class);
        if (setter != null && !setter.value().isEmpty()) {
            names.add(setter.value());
        }
        JsonAlias alias = e.getAnnotation(JsonAlias.class);
        if (alias != null) {
            names.addAll(List.of(alias.value()));
        }
    }

    private static String decapitalize(String s) {
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
