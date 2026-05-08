/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.ravel.cdi;

import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.Validation;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.DeclarationInfo;
import jakarta.enterprise.lang.model.types.ClassType;
import jakarta.enterprise.lang.model.types.ParameterizedType;
import jakarta.enterprise.lang.model.types.Type;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;

/**
 * BCE CDI qui valide les points d'injection {@code @ConfigProperty} au déploiement.
 */
public class ConfigCdiExtension implements BuildCompatibleExtension {

    @Validation
    public void validateConfigPropertyInjectionPoints(BeanInfo beanInfo, Messages messages) {
        for (InjectionPointInfo injectionPoint : beanInfo.injectionPoints()) {
            AnnotationInfo cfg = findConfigPropertyQualifier(injectionPoint);
            if (cfg == null) {
                continue;
            }
            DeclarationInfo declaration = injectionPoint.declaration();
            if (!isSupportedType(injectionPoint.type())) {
                messages.error("Unsupported @ConfigProperty injection type: " + injectionPoint.type(), declaration);
                continue;
            }

            boolean optional = isOptionalType(injectionPoint.type());
            String key = resolvePropertyName(cfg, declaration);
            boolean hasDefault = hasConfiguredDefault(cfg);

            if (!optional && !hasDefault && key != null && !key.isBlank()) {
                boolean present = ConfigProvider.getConfig().getOptionalValue(key, String.class).isPresent();
                if (!present) {
                    messages.error("Missing required config property '" + key + "'", declaration);
                }
            }
        }
    }

    static AnnotationInfo findConfigPropertyQualifier(InjectionPointInfo injectionPoint) {
        for (AnnotationInfo qualifier : injectionPoint.qualifiers()) {
            if (ConfigProperty.class.getName().equals(qualifier.name())) {
                return qualifier;
            }
        }
        return null;
    }

    static boolean isSupportedType(Type type) {
        if (type instanceof ClassType classType) {
            return true;
        }
        if (type instanceof ParameterizedType pt) {
            Type raw = pt.genericClass();
            if (!(raw instanceof ClassType classType)) {
                return false;
            }
            String rawName = classType.declaration().name();
            if (!rawName.equals("java.util.Optional")
                    && !rawName.equals("jakarta.inject.Provider")
                    && !rawName.equals("java.util.function.Supplier")) {
                return false;
            }
            return pt.typeArguments().size() == 1 && pt.typeArguments().get(0) instanceof ClassType;
        }
        return false;
    }

    static boolean isOptionalType(Type type) {
        if (type instanceof ParameterizedType pt && pt.genericClass() instanceof ClassType classType) {
            String rawName = classType.declaration().name();
            return rawName.equals("java.util.Optional")
                    || rawName.equals("jakarta.inject.Provider")
                    || rawName.equals("java.util.function.Supplier");
        }
        return false;
    }

    static String resolvePropertyName(AnnotationInfo cfg, DeclarationInfo declaration) {
        Map<String, AnnotationMember> members = cfg.members();
        AnnotationMember name = members.get("name");
        String configured = name != null && name.isString() ? name.asString() : null;
        if (configured != null && !configured.isBlank() && !ConfigProperty.UNCONFIGURED_VALUE.equals(configured)) {
            return configured;
        }
        return switch (declaration.kind()) {
            case FIELD -> declaration.asField().name();
            case PARAMETER -> declaration.asParameter().name();
            default -> "";
        };
    }

    static boolean hasConfiguredDefault(AnnotationInfo cfg) {
        AnnotationMember member = cfg.members().get("defaultValue");
        if (member == null || !member.isString()) {
            return false;
        }
        return !ConfigProperty.UNCONFIGURED_VALUE.equals(member.asString());
    }
}

