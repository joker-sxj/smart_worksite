import re
from html.parser import HTMLParser
from typing import Any
from urllib.parse import urljoin, urlparse

import httpx

from app.models.schemas import PolicyCrawlArticle, PolicyCrawlData, PolicyCrawlRequest


class _HtmlTextExtractor(HTMLParser):
    def __init__(self):
        super().__init__()
        self.skip_depth = 0
        self.body_parts: list[str] = []
        self.in_title = False
        self.title_parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]):
        tag = tag.lower()
        if tag in {"script", "style", "noscript", "svg", "nav", "footer", "header", "aside"}:
            self.skip_depth += 1
        if tag == "title":
            self.in_title = True
        if tag in {"p", "div", "section", "article", "br", "li", "tr", "h1", "h2", "h3"}:
            self.body_parts.append("\n")

    def handle_endtag(self, tag: str):
        tag = tag.lower()
        if tag in {"script", "style", "noscript", "svg", "nav", "footer", "header", "aside"} and self.skip_depth > 0:
            self.skip_depth -= 1
        if tag == "title":
            self.in_title = False
        if tag in {"p", "div", "section", "article", "li", "tr", "h1", "h2", "h3"}:
            self.body_parts.append("\n")

    def handle_data(self, data: str):
        text = re.sub(r"\s+", " ", data).strip()
        if not text:
            return
        if self.in_title:
            self.title_parts.append(text)
        if self.skip_depth == 0:
            self.body_parts.append(text)


class _LinkExtractor(HTMLParser):
    def __init__(self, base_url: str):
        super().__init__()
        self.base_url = base_url
        self.current_href: str | None = None
        self.current_text: list[str] = []
        self.links: list[tuple[str, str]] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]):
        if tag.lower() != "a":
            return
        attrs_map = {key.lower(): value for key, value in attrs if key}
        href = attrs_map.get("href")
        if href:
            self.current_href = urljoin(self.base_url, href)
            self.current_text = []

    def handle_data(self, data: str):
        if self.current_href:
            text = re.sub(r"\s+", " ", data).strip()
            if text:
                self.current_text.append(text)

    def handle_endtag(self, tag: str):
        if tag.lower() == "a" and self.current_href:
            title = " ".join(self.current_text).strip()
            if title:
                self.links.append((self.current_href, title))
            self.current_href = None
            self.current_text = []


def _clean_text(text: str) -> str:
    lines = [line.strip() for line in re.split(r"[\r\n]+", text) if line.strip()]
    noise = ("ICP\u5907", "\u516c\u7f51\u5b89\u5907", "\u7248\u6743\u6240\u6709", "\u5206\u4eab\u5230", "\u6253\u5370", "\u5173\u95ed\u7a97\u53e3")
    kept = [line for line in lines if not any(word in line for word in noise)]
    return "\n".join(kept)


def _extract_title(html: str, fallback: str) -> str:
    for pattern in [r"<h1[^>]*>(.*?)</h1>", r"<title[^>]*>(.*?)</title>"]:
        match = re.search(pattern, html, flags=re.I | re.S)
        if match:
            title = re.sub(r"<[^>]+>", "", match.group(1))
            title = re.sub(r"\s+", " ", title).strip()
            if title:
                return title[:256]
    return fallback[:256]


def _extract_date(text: str) -> str | None:
    match = re.search(r"(20\d{2})[-\u5e74/.](\d{1,2})[-\u6708/.](\d{1,2})", text)
    if not match:
        return None
    y, m, d = match.groups()
    return f"{int(y):04d}-{int(m):02d}-{int(d):02d}"


def _extract_policy_no(text: str) -> str | None:
    match = re.search(r"([\u4e00-\u9fa5]{1,12}\u301420\d{2}\u3015\d+\u53f7)", text)
    return match.group(1) if match else None


def _looks_like_blocked_page(html: str) -> bool:
    text = html[:2000]
    return any(token in text for token in ("zse-ck", "请求存在异常", "Forbidden", "访问受限", "anti-bot"))


def _looks_like_article_url(url: str) -> bool:
    lowered = url.lower()
    parsed = urlparse(lowered)
    if parsed.netloc.endswith("zhihu.com") and re.fullmatch(r"/p/\d+", parsed.path):
        return True
    return any(token in lowered for token in (".html", ".htm", "/20"))


CRAWLER_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 SmartWorksitePolicyCrawler/1.0",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
}


class PolicyCrawlerService:
    async def crawl(self, request: PolicyCrawlRequest) -> tuple[PolicyCrawlData, dict[str, Any]]:
        async with httpx.AsyncClient(follow_redirects=True, timeout=30.0) as client:
            response = await client.get(request.url, headers=CRAWLER_HEADERS)
            self._ensure_usable_response(response)
            response.encoding = response.encoding or "utf-8"
            root_html = response.text
            links = self._extract_article_links(root_html, str(response.url) if response.url else request.url)
            if links:
                articles: list[PolicyCrawlArticle] = []
                for url, fallback_title in links[:20]:
                    try:
                        article_response = await client.get(url, headers=CRAWLER_HEADERS)
                        self._ensure_usable_response(article_response)
                        article_response.encoding = article_response.encoding or "utf-8"
                        articles.append(self._build_article(article_response.text, str(article_response.url), fallback_title))
                    except Exception:
                        continue
                if articles:
                    return PolicyCrawlData(fetchedCount=len(articles), message="policy list crawled", articles=articles), {"provider": "HTTPX", "fetched": len(articles)}
            article = self._build_article(root_html, str(response.url) if response.url else request.url, request.url)
            return PolicyCrawlData(fetchedCount=1, message="policy page crawled", articles=[article]), {"provider": "HTTPX", "fetched": 1}

    def _ensure_usable_response(self, response: httpx.Response) -> None:
        if response.status_code < 400:
            return
        body = response.text or ""
        if response.status_code in {401, 403} and _looks_like_blocked_page(body):
            raise httpx.HTTPStatusError(
                "policy crawler was blocked by the target site anti-bot page",
                request=response.request,
                response=response,
            )
        response.raise_for_status()

    def _extract_article_links(self, html: str, base_url: str) -> list[tuple[str, str]]:
        extractor = _LinkExtractor(base_url)
        extractor.feed(html)
        seen: set[str] = set()
        links: list[tuple[str, str]] = []
        for url, title in extractor.links:
            if url in seen or not _looks_like_article_url(url):
                continue
            seen.add(url)
            links.append((url, title[:256]))
        return links

    def _build_article(self, html: str, url: str, fallback_title: str) -> PolicyCrawlArticle:
        extractor = _HtmlTextExtractor()
        extractor.feed(html)
        content = _clean_text("\n".join(extractor.body_parts))
        if not content:
            raise ValueError("policy crawler extracted empty content")
        title = _extract_title(html, fallback_title)
        return PolicyCrawlArticle(
            title=title,
            url=url,
            summary=content[:300],
            content=content,
            publishDate=_extract_date(content),
            category=None,
            policyNo=_extract_policy_no(content),
            sourceName=None,
        )
