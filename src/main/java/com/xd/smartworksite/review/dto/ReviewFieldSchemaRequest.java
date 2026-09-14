package com.xd.smartworksite.review.dto;

import com.xd.smartworksite.review.domain.ReviewField;
import java.util.ArrayList;
import java.util.List;

public class ReviewFieldSchemaRequest {
    private List<ReviewField> fields = new ArrayList<>();
    public List<ReviewField> getFields() { return fields; }
    public void setFields(List<ReviewField> fields) { this.fields = fields; }
}
