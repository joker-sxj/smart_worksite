import asyncio
import ipaddress
import re
import socket
import time
from datetime import date
from html.parser import HTMLParser
from typing import Any
from urllib.parse import urljoin, urlparse, urlunparse
from urllib.robotparser import RobotFileParser

import httpx

from app.core.settings import Settings
from app.models.schemas import PolicyCrawlArticle, PolicyCrawlData, PolicyCrawlRequest, PolicyPreflightData, PolicyPreflightRequest

USER_AGENT = "SmartWorksitePolicyCrawler/1.0 (+policy-ingestion; respects robots.txt)"
CRAWLER_HEADERS = {"User-Agent": USER_AGENT, "Accept": "text/html,application/xhtml+xml;q=0.9", "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8", "Accept-Encoding": "identity"}
RETRYABLE_STATUS = {408, 425, 429, 500, 502, 503, 504}
METADATA_IPS = {ipaddress.ip_address("169.254.169.254"), ipaddress.ip_address("100.100.100.200")}

class PolicyCrawlerNetworkDisabledError(RuntimeError): pass
class PolicyCrawlerUrlError(ValueError): pass
class PolicyCrawlerResponseError(RuntimeError): pass
class PolicyCrawlerBlockedError(PolicyCrawlerResponseError): pass
class PolicyCrawlerTimeoutError(PolicyCrawlerResponseError): pass

class _HtmlTextExtractor(HTMLParser):
    def __init__(self):
        super().__init__(); self.skip_depth = 0; self.body_parts: list[str] = []
    def handle_starttag(self, tag, attrs):
        tag = tag.lower()
        if tag in {"script", "style", "noscript", "svg", "nav", "footer", "header", "aside"}: self.skip_depth += 1
        if tag in {"p", "div", "section", "article", "br", "li", "tr", "h1", "h2", "h3"}: self.body_parts.append("\n")
    def handle_endtag(self, tag):
        tag = tag.lower()
        if tag in {"script", "style", "noscript", "svg", "nav", "footer", "header", "aside"} and self.skip_depth: self.skip_depth -= 1
        if tag in {"p", "div", "section", "article", "li", "tr", "h1", "h2", "h3"}: self.body_parts.append("\n")
    def handle_data(self, data):
        text = re.sub(r"\s+", " ", data).strip()
        if text and self.skip_depth == 0: self.body_parts.append(text)

class _LinkExtractor(HTMLParser):
    def __init__(self, base_url):
        super().__init__(); self.base_url = base_url; self.current_href = None; self.current_text = []; self.links = []
    def handle_starttag(self, tag, attrs):
        if tag.lower() == "a" and (href := dict(attrs).get("href")):
            self.current_href = urljoin(self.base_url, href); self.current_text = []
    def handle_data(self, data):
        if self.current_href and (text := re.sub(r"\s+", " ", data).strip()): self.current_text.append(text)
    def handle_endtag(self, tag):
        if tag.lower() == "a" and self.current_href:
            if title := " ".join(self.current_text).strip(): self.links.append((self.current_href, title))
            self.current_href = None; self.current_text = []

def _clean_text(text):
    noise = ("ICP备", "公网安备", "版权所有", "分享到", "打印", "关闭窗口")
    lines = [line.strip() for line in re.split(r"[\r\n]+", text) if line.strip()]
    return "\n".join(line for line in lines if not any(word in line for word in noise))

def _extract_title(html, fallback):
    for pattern in (r"<h1[^>]*>(.*?)</h1>", r"<title[^>]*>(.*?)</title>"):
        if match := re.search(pattern, html, flags=re.I | re.S):
            if title := re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", match.group(1))).strip(): return title[:256]
    return fallback[:256]

def _extract_date(text):
    match = re.search(r"(20\d{2})[-年/.](\d{1,2})[-月/.](\d{1,2})", text)
    return f"{int(match[1]):04d}-{int(match[2]):02d}-{int(match[3]):02d}" if match else None

def _extract_policy_no(text):
    match = re.search(r"([\u4e00-\u9fa5]{1,12}〔20\d{2}〕\d+号)", text)
    return match.group(1) if match else None

def _looks_like_blocked_page(html): return any(token in html[:4000] for token in ("zse-ck", "请求存在异常", "Forbidden", "访问受限", "anti-bot"))
def _looks_like_article_url(url):
    parsed = urlparse(url.lower())
    return bool(parsed.netloc.endswith("zhihu.com") and re.fullmatch(r"/p/\d+", parsed.path)) or any(token in parsed.path for token in (".html", ".htm", "/20"))

class PolicyCrawlerService:
    def __init__(self, settings: Settings):
        self.settings = settings; self._last_request_at = 0.0; self._robots = {}

    async def crawl(self, request: PolicyCrawlRequest):
        if not self.settings.policy_crawler_network_enabled:
            raise PolicyCrawlerNetworkDisabledError("Policy crawler network access is disabled; set POLICY_CRAWLER_NETWORK_ENABLED=true to enable HTTP fetching")
        source_url = self._validate_url(request.url)
        timeout = httpx.Timeout(connect=self.settings.policy_crawler_connect_timeout_seconds, read=self.settings.policy_crawler_read_timeout_seconds, write=self.settings.policy_crawler_read_timeout_seconds, pool=self.settings.policy_crawler_connect_timeout_seconds)
        async with asyncio.timeout(self.settings.policy_crawler_total_timeout_seconds):
            async with httpx.AsyncClient(follow_redirects=False, timeout=timeout, trust_env=False) as client:
                self._invalidate_robots(source_url)
                if not await self._robots_allowed(client, source_url): raise PolicyCrawlerUrlError("policy crawler is disallowed by robots.txt")
                response, final_url = await self._fetch(client, source_url)
                root_html = self._decode(response)
                links = self._select_article_links(root_html, final_url)
                articles, failed = await self._crawl_articles(client, links)
                if not articles: articles = [self._build_article(root_html, final_url, request.url)]
                return PolicyCrawlData(fetchedCount=len(articles) + failed, failedCount=failed, message="policy content crawled", articles=articles), {"provider": "HTTPX", "sourceUrl": request.url, "finalUrl": final_url, "fetched": len(articles), "failed": failed}

    async def preflight(self, request: PolicyPreflightRequest) -> PolicyPreflightData:
        if not self.settings.policy_crawler_network_enabled:
            return PolicyPreflightData(status="UNKNOWN", reason="NETWORK_DISABLED", message="当前环境未启用政策源网络访问，保存后仍会在爬取时重新检查。")
        source_url = self._validate_url(request.url)
        timeout = httpx.Timeout(connect=self.settings.policy_crawler_connect_timeout_seconds, read=self.settings.policy_crawler_read_timeout_seconds, write=self.settings.policy_crawler_read_timeout_seconds, pool=self.settings.policy_crawler_connect_timeout_seconds)
        async with httpx.AsyncClient(follow_redirects=False, timeout=timeout, trust_env=False) as client:
            return await self._robots_decision(client, source_url)

    async def _crawl_articles(self, client, links):
        semaphore = asyncio.Semaphore(self.settings.policy_crawler_max_concurrency)
        async def one(url, title):
            try:
                async with semaphore:
                    return await self._crawl_one_article(client, url, title)
            except (httpx.HTTPError, PolicyCrawlerUrlError, PolicyCrawlerResponseError, ValueError): return None
        results = await asyncio.gather(*(one(*link) for link in links))
        return [item for item in results if item is not None], sum(item is None for item in results)

    async def _crawl_one_article(self, client, url, title):
        checked = self._validate_url(url)
        if not await self._robots_allowed(client, checked):
            raise PolicyCrawlerUrlError("article is disallowed by robots.txt")
        response, final_url = await self._fetch(client, checked)
        return self._build_article(self._decode(response), final_url, title)

    def _validate_url(self, url):
        parsed = self._parse_url(url)
        self._resolve_public_addresses(url)
        return urlunparse(parsed._replace(fragment=""))

    def _parse_url(self, url):
        parsed = urlparse(url)
        if parsed.scheme.lower() not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
            raise PolicyCrawlerUrlError("policy URL must be an http(s) URL without credentials")
        host = parsed.hostname.rstrip(".").lower()
        if host in {"localhost", "metadata.google.internal"}: raise PolicyCrawlerUrlError("policy URL resolves to a private or metadata address")
        return parsed

    def _resolve_public_addresses(self, url):
        parsed = urlparse(url); host = parsed.hostname
        try: values = {item[4][0] for item in socket.getaddrinfo(host, parsed.port or (443 if parsed.scheme == "https" else 80), type=socket.SOCK_STREAM)}
        except socket.gaierror as exc: raise PolicyCrawlerUrlError("policy URL host cannot be resolved") from exc
        addresses = []
        for value in values:
            address = ipaddress.ip_address(value.split("%")[0])
            if address in METADATA_IPS: raise PolicyCrawlerUrlError("policy URL resolves to a metadata address")
            if not address.is_global: raise PolicyCrawlerUrlError("policy URL resolves to a private, loopback, link-local or reserved address")
            addresses.append(str(address))
        if not addresses: raise PolicyCrawlerUrlError("policy URL host cannot be resolved")
        # Prefer IPv4 on dual-stack hosts; some enterprise networks advertise
        # IPv6 but do not provide a usable route to the public site.
        return sorted(addresses, key=lambda value: (":" in value, value))

    async def _request_once(self, client, url):
        """Resolve once, validate every answer, then connect to that exact IP to close DNS-rebinding TOCTOU."""
        parsed = self._parse_url(url); address = self._resolve_public_addresses(url)[0]
        pinned_host = f"[{address}]" if ":" in address else address
        pinned_netloc = pinned_host + (f":{parsed.port}" if parsed.port else "")
        pinned_url = urlunparse(parsed._replace(netloc=pinned_netloc))
        host_header = parsed.hostname + (f":{parsed.port}" if parsed.port else "")
        headers = {**CRAWLER_HEADERS, "Host": host_header}
        if hasattr(client, "build_request") and hasattr(client, "send"):
            request = client.build_request("GET", pinned_url, headers=headers, extensions={"sni_hostname": parsed.hostname})
            response = await client.send(request, stream=True)
            try:
                content = bytearray()
                async for chunk in response.aiter_bytes():
                    content.extend(chunk)
                    if len(content) > self.settings.policy_crawler_max_response_bytes:
                        raise PolicyCrawlerResponseError("policy crawler response size limit exceeded")
                return httpx.Response(response.status_code, headers=response.headers, content=bytes(content), request=request)
            finally:
                await response.aclose()
        return await client.request("GET", pinned_url, headers=headers, extensions={"sni_hostname": parsed.hostname})

    async def _fetch(self, client, url, robots=False):
        current = url
        for redirect_no in range(self.settings.policy_crawler_max_redirects + 1):
            current = self._validate_url(current)
            response = await self._request_with_retries(client, current)
            if response.status_code in {301, 302, 303, 307, 308}:
                if redirect_no >= self.settings.policy_crawler_max_redirects: raise PolicyCrawlerResponseError("policy crawler redirect limit exceeded")
                if not (location := response.headers.get("location")): raise PolicyCrawlerResponseError("policy crawler redirect has no Location header")
                current = urljoin(current, location); continue
            if not robots: self._ensure_usable_response(response)
            elif len(response.content) > self.settings.policy_crawler_max_response_bytes: raise PolicyCrawlerResponseError("robots.txt response size limit exceeded")
            return response, current
        raise PolicyCrawlerResponseError("policy crawler redirect limit exceeded")

    async def _request_with_retries(self, client, url):
        for attempt in range(self.settings.policy_crawler_max_retries + 1):
            try:
                await self._respect_interval(); response = await self._request_once(client, url)
                if response.status_code not in RETRYABLE_STATUS or attempt == self.settings.policy_crawler_max_retries: return response
            except (httpx.ReadTimeout, httpx.ConnectTimeout) as exc:
                if attempt == self.settings.policy_crawler_max_retries:
                    raise PolicyCrawlerTimeoutError("policy crawler target timed out") from exc
            except httpx.ConnectError:
                if attempt == self.settings.policy_crawler_max_retries: raise
            await asyncio.sleep(self.settings.policy_crawler_retry_backoff_seconds * (2 ** attempt))
        raise PolicyCrawlerResponseError("policy crawler request failed")

    async def _respect_interval(self):
        delay = self.settings.policy_crawler_request_interval_seconds - (time.monotonic() - self._last_request_at)
        if delay > 0: await asyncio.sleep(delay)
        self._last_request_at = time.monotonic()

    def _invalidate_robots(self, url):
        parsed = urlparse(url)
        self._robots.pop(f"{parsed.scheme}://{parsed.netloc}", None)

    async def _robots_allowed(self, client, url):
        return (await self._robots_decision(client, url)).status == "ALLOWED"

    async def _robots_decision(self, client, url):
        parsed = urlparse(url); origin = f"{parsed.scheme}://{parsed.netloc}"; parser = self._robots.get(origin)
        if parser is None:
            parser = RobotFileParser(); parser.set_url(f"{origin}/robots.txt")
            try:
                response, _ = await self._fetch(client, parser.url, robots=True)
                if response.status_code == 200:
                    parser.parse(response.text.splitlines())
                elif response.status_code in {404, 410}:
                    parser.allow_all = True
                    self._robots[origin] = parser
                    return PolicyPreflightData(status="ALLOWED", reason="ROBOTS_NOT_PUBLISHED", message="站点未发布 robots.txt；爬取时仍会重新检查。")
                else:
                    return PolicyPreflightData(status="UNKNOWN", reason="ROBOTS_UNUSABLE", message="暂时无法确认 robots.txt 规则，请谨慎保存并稍后重试。")
            except (httpx.HTTPError, PolicyCrawlerUrlError, PolicyCrawlerResponseError):
                return PolicyPreflightData(status="UNKNOWN", reason="ROBOTS_UNAVAILABLE", message="暂时无法读取 robots.txt，请谨慎保存并稍后重试。")
            self._robots[origin] = parser
        allowed = parser.can_fetch(USER_AGENT, url)
        if allowed:
            return PolicyPreflightData(status="ALLOWED", reason="ROBOTS_ALLOW", message="robots.txt 允许访问该地址；实际爬取时会再次检查。")
        return PolicyPreflightData(status="RESTRICTED", reason="ROBOTS_DISALLOW", message="robots.txt 不允许自动抓取该地址，可改用授权文件上传或人工录入。")

    def _ensure_usable_response(self, response):
        declared_length = response.headers.get("content-length")
        if declared_length and declared_length.isdigit() and int(declared_length) > self.settings.policy_crawler_max_response_bytes:
            raise PolicyCrawlerResponseError("policy crawler response size limit exceeded")
        if len(response.content) > self.settings.policy_crawler_max_response_bytes: raise PolicyCrawlerResponseError("policy crawler response size limit exceeded")
        if response.status_code in {401, 403} and _looks_like_blocked_page(response.text or ""):
            raise PolicyCrawlerBlockedError("policy crawler was blocked by the target site anti-bot protection")
        content_type = response.headers.get("content-type", "").split(";", 1)[0].strip().lower()
        if content_type not in {"text/html", "application/xhtml+xml"}: raise PolicyCrawlerResponseError("policy crawler requires an HTML Content-Type")
        if response.status_code >= 400:
            response.raise_for_status()

    def _decode(self, response):
        if not response.encoding: response.encoding = "utf-8"
        return response.text

    def _extract_article_links(self, html, base_url):
        extractor = _LinkExtractor(base_url); extractor.feed(html); seen = set(); links = []
        base = urlparse(base_url)
        for url, title in extractor.links:
            parsed = urlparse(url)
            if base.scheme == "https" and parsed.scheme == "http" and parsed.hostname == base.hostname:
                parsed = parsed._replace(scheme="https")
            normalized = urlunparse(parsed._replace(fragment=""))
            if normalized not in seen and _looks_like_article_url(normalized): seen.add(normalized); links.append((normalized, title[:256]))
        return links

    def _select_article_links(self, html, base_url):
        links = self._extract_article_links(html, base_url)
        dated_links = []
        undated_links = []
        for original_index, link in enumerate(links):
            url_date = self._extract_url_date(link[0])
            if url_date is None:
                undated_links.append(link)
            else:
                dated_links.append((url_date, original_index, link))
        dated_links.sort(key=lambda item: (-item[0].toordinal(), item[1]))
        prioritized = [link for _, _, link in dated_links] + undated_links
        return prioritized[:self.settings.policy_crawler_max_articles]

    @staticmethod
    def _extract_url_date(url):
        path = urlparse(url).path
        for match in re.finditer(r"(?<!\d)(20\d{2})[/_-]?(0[1-9]|1[0-2])[/_-]?(0[1-9]|[12]\d|3[01])(?!\d)", path):
            try:
                return date(int(match[1]), int(match[2]), int(match[3]))
            except ValueError:
                continue
        return None

    def _build_article(self, html, url, fallback_title):
        extractor = _HtmlTextExtractor(); extractor.feed(html); content = _clean_text("\n".join(extractor.body_parts))
        if not content: raise ValueError("policy crawler extracted empty content")
        return PolicyCrawlArticle(title=_extract_title(html, fallback_title), url=url, summary=content[:300], content=content, publishDate=_extract_date(content), category=None, policyNo=_extract_policy_no(content), sourceName=None)
