package net.ximatai.muyun.spring.web;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

public final class WebAnnotationSupport {
    private WebAnnotationSupport() {
    }

    public static <A extends Annotation> A findMergedMethodAnnotation(Method method,
                                                              Class<?> containingClass,
                                                              Class<A> annotationType) {
        A annotation = AnnotatedElementUtils.findMergedAnnotation(method, annotationType);
        if (annotation != null) {
            return annotation;
        }
        if (containingClass == null) {
            return null;
        }
        Method classMethod = findCompatibleMethod(containingClass, method);
        if (classMethod != null && !classMethod.equals(method)) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(classMethod, annotationType);
            if (annotation != null) {
                return annotation;
            }
        }
        return findMergedInterfaceMethodAnnotation(containingClass, method, annotationType);
    }

    public static boolean hasMergedMethodOrTypeAnnotation(Method method,
                                                   Class<?> containingClass,
                                                   Class<? extends Annotation> annotationType) {
        if (findMergedMethodAnnotation(method, containingClass, annotationType) != null) {
            return true;
        }
        return hasMergedTypeAnnotation(containingClass, annotationType);
    }

    private static boolean hasMergedTypeAnnotation(Class<?> type,
                                                   Class<? extends Annotation> annotationType) {
        if (type == null) {
            return false;
        }
        if (AnnotatedElementUtils.hasAnnotation(type, annotationType)) {
            return true;
        }
        for (Class<?> interfaceType : type.getInterfaces()) {
            if (hasMergedTypeAnnotation(interfaceType, annotationType)) {
                return true;
            }
        }
        return hasMergedTypeAnnotation(type.getSuperclass(), annotationType);
    }

    private static <A extends Annotation> A findMergedInterfaceMethodAnnotation(Class<?> type,
                                                                               Method method,
                                                                               Class<A> annotationType) {
        if (type == null) {
            return null;
        }
        for (Class<?> interfaceType : type.getInterfaces()) {
            A annotation = findMergedInterfaceMethodAnnotationInType(interfaceType, method, annotationType);
            if (annotation != null) {
                return annotation;
            }
        }
        return findMergedInterfaceMethodAnnotation(type.getSuperclass(), method, annotationType);
    }

    private static <A extends Annotation> A findMergedInterfaceMethodAnnotationInType(Class<?> interfaceType,
                                                                                     Method method,
                                                                                     Class<A> annotationType) {
        Method interfaceMethod = findCompatibleMethod(interfaceType, method);
        if (interfaceMethod != null) {
            A annotation = AnnotatedElementUtils.findMergedAnnotation(interfaceMethod, annotationType);
            if (annotation != null) {
                return annotation;
            }
        }
        for (Class<?> parentInterface : interfaceType.getInterfaces()) {
            A annotation = findMergedInterfaceMethodAnnotationInType(parentInterface, method, annotationType);
            if (annotation != null) {
                return annotation;
            }
        }
        return null;
    }

    private static Method findCompatibleMethod(Class<?> type, Method source) {
        Method exact = ReflectionUtils.findMethod(type, source.getName(), source.getParameterTypes());
        if (exact != null) {
            return exact;
        }
        Method[] methods = type.getMethods();
        for (Method candidate : methods) {
            if (candidate.getName().equals(source.getName())
                    && candidate.getParameterCount() == source.getParameterCount()
                    && parametersCompatible(candidate.getParameterTypes(), source.getParameterTypes())) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean parametersCompatible(Class<?>[] annotationMethodParameters,
                                                Class<?>[] handlerMethodParameters) {
        for (int index = 0; index < annotationMethodParameters.length; index++) {
            Class<?> annotationParameter = ClassUtils.resolvePrimitiveIfNecessary(annotationMethodParameters[index]);
            Class<?> handlerParameter = ClassUtils.resolvePrimitiveIfNecessary(handlerMethodParameters[index]);
            if (!annotationParameter.isAssignableFrom(handlerParameter)
                    && !handlerParameter.isAssignableFrom(annotationParameter)) {
                return false;
            }
        }
        return true;
    }
}
