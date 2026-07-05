package com.stonewu.fusion.controller.cost.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class BatchUpdateCostConfigReqVO {

    private List<Item> items;

    @Data
    public static class Item {
        private Long modelId;
        private String mediaType;
        private String billingMode;
        private BigDecimal unitPrice;
        private String currency;
        private Boolean enabled;
        private String remark;
    }
}
