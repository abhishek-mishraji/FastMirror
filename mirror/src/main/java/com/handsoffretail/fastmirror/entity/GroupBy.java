package com.handsoffretail.fastmirror.entity;

/**
 * How copied files are grouped below {@code <yyyy>/<month>} in the mirror.
 *
 * <p>Weekly grouping was considered and deliberately left out.
 */
public enum GroupBy {

    /** {@code CPJ/2026/october/2026-10-15/CPJ432.xml} */
    DATE,

    /** {@code CPJ/2026/october/CPJ432.xml} */
    MONTH
}
