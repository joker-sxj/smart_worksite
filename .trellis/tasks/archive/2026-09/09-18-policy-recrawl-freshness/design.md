# 技术设计

前端通过纯函数识别明显的单篇文章 URL，仅做提示，不阻止保存。列表与详情直接使用已有 `publishDate`、`createdAt`、`updatedAt`。Python 在 `_extract_article_links` 返回候选后、应用 max_articles 上限前执行稳定日期排序。
