package com.xd.smartworksite.common.result;

import java.util.List;

public class PageResult<T> {

    private final int pageNo;
    private final int pageSize;
    private final long total;
    private final List<T> records;

    public PageResult(int pageNo, int pageSize, long total, List<T> records) {
        this.pageNo = pageNo;
        this.pageSize = pageSize;
        this.total = total;
        this.records = records;
    }

    public int getPageNo() {
        return pageNo;
    }

    public int getPageSize() {
        return pageSize;
    }

    public long getTotal() {
        return total;
    }

    public List<T> getRecords() {
        return records;
    }
}
