package com.stonewu.fusion.controller.storyboard.vo;

import lombok.Data;

@Data
public class EvaluateStoryboardVideoQualityReqVO {

    /**
     * Select the best candidate and write it back to storyboard item when score passes minScore.
     */
    private Boolean autoSelect;

    /**
     * Minimum score for auto selection. Default is 75.
     */
    private Integer minScore;
}
