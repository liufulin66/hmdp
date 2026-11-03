package com.hmdp.annotation;

import java.lang.annotation.*;

/** 仅显式标记的方法在 POST 时允许匿名访问，不扩大整段路径权限。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AnonymousPost {}
