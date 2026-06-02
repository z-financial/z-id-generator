package com.zed.idgen.snowflake.core.manager.mysql.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

@Getter
public enum DomainTimeUnit {
    MILLISECOND("MILLISECOND"),
    SECOND("SECOND");

    @EnumValue
    private final String value;

    DomainTimeUnit(String value) {
        this.value = value;
    }

}
