package com.ruigu.rbox.workflow.strategy.convert;

import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;

import java.lang.annotation.*;

/**
 * @Author panjianwei
 */
@Target({ElementType.FIELD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExcelStyle {


    String fontName() default "宋体";

    short fontHeightInPoints() default 12;

    HorizontalAlignment horizontalAlignment() default HorizontalAlignment.LEFT;

    VerticalAlignment verticalAlignment() default VerticalAlignment.CENTER;
}