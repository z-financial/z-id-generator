package com.zed.idgen.snowflake.core.manager.mysql;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

@Getter
public enum DomainPrefixType {
    POSITIVE_SIGNAL("POSITIVE_SIGNAL"),
    NONE("NONE");

    @EnumValue
    private final String value;

    DomainPrefixType(String value) {
        this.value = value;
    }

}
