package com.explorelk.auth.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The platform password policy, in one place so registration, reset and change
 * cannot drift apart.
 *
 * @see PasswordPolicyValidator
 */
@Documented
@Constraint(validatedBy = PasswordPolicyValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {

    String message() default "Password does not meet the policy";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
