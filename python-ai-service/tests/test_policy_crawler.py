from app.core.settings import Settings
from app.services.policy_crawler_service import PolicyCrawlerBlockedError, PolicyCrawlerService


def crawler_settings(**overrides):
    values = {"policy_crawler_network_enabled": True}
    values.update(overrides)
    return Settings(_env_file=None, **values)


def test_policy_crawler_extracts_list_links_and_article_metadata():
    service = PolicyCrawlerService(crawler_settings())
    list_html = """
        <html><body>
          <ul>
            <li><a href="/policy/safety-2026.html">\u5efa\u7b51\u65bd\u5de5\u5b89\u5168\u751f\u4ea7\u901a\u77e5</a></li>
            <li><a href="/policy/dust-2026.html">\u626c\u5c18\u6cbb\u7406\u63d0\u793a</a></li>
            <li><a href="/policy/">\u680f\u76ee\u9996\u9875</a></li>
          </ul>
        </body></html>
    """
    links = service._extract_article_links(list_html, "https://example.gov.cn/policy/")

    assert links == [
        ("https://example.gov.cn/policy/safety-2026.html", "\u5efa\u7b51\u65bd\u5de5\u5b89\u5168\u751f\u4ea7\u901a\u77e5"),
        ("https://example.gov.cn/policy/dust-2026.html", "\u626c\u5c18\u6cbb\u7406\u63d0\u793a"),
    ]

    article_html = """
        <html><head><title>\u5efa\u7b51\u65bd\u5de5\u5b89\u5168\u751f\u4ea7\u901a\u77e5</title></head><body>
          <h1>\u5efa\u7b51\u65bd\u5de5\u5b89\u5168\u751f\u4ea7\u901a\u77e5</h1>
          <p>\u53d1\u5e03\u65e5\u671f\uff1a2026\u5e747\u670814\u65e5</p>
          <p>\u5efa\u5b89\u30142026\u301515\u53f7</p>
          <p>\u8bf7\u52a0\u5f3a\u5371\u5927\u5de5\u7a0b\u3001\u9ad8\u5904\u4f5c\u4e1a\u548c\u4e34\u8fb9\u6d1e\u53e3\u5b89\u5168\u7ba1\u7406\u3002</p>
          <p>ICP\u5907\u6848\u4fe1\u606f</p>
        </body></html>
    """
    article = service._build_article(article_html, "https://example.gov.cn/policy/safety-2026.html", "fallback")

    assert article.publishDate == "2026-07-14"
    assert article.policyNo == "\u5efa\u5b89\u30142026\u301515\u53f7"
    assert "ICP\u5907" not in article.content


def test_policy_crawler_single_page_date_formats():
    service = PolicyCrawlerService(crawler_settings())
    article = service._build_article(
        "<html><body><h1>\u5355\u7bc7\u653f\u7b56</h1><p>2026.07.12</p><p>\u6b63\u6587\u5185\u5bb9\u3002</p></body></html>",
        "https://example.gov.cn/policy/single.html",
        "fallback",
    )

    assert article.title == "\u5355\u7bc7\u653f\u7b56"
    assert article.publishDate == "2026-07-12"


def test_policy_crawler_zhihu_article_urls_are_supported():
    service = PolicyCrawlerService(crawler_settings())

    assert service._extract_article_links(
        '<a href="/p/16328033204">智慧工地政策资讯</a>',
        'https://zhuanlan.zhihu.com/',
    ) == [('https://zhuanlan.zhihu.com/p/16328033204', '智慧工地政策资讯')]


def test_policy_crawler_detects_target_site_block_page():
    service = PolicyCrawlerService(crawler_settings())
    import httpx
    response = httpx.Response(
        403,
        text='<html><meta id="zh-zse-ck"><body>访问受限</body></html>',
        request=httpx.Request('GET', 'https://zhuanlan.zhihu.com/p/16328033204'),
    )

    try:
        service._ensure_usable_response(response)
    except PolicyCrawlerBlockedError as exc:
        assert 'anti-bot' in str(exc)
    else:
        raise AssertionError('expected anti-bot HTTPStatusError')


def test_policy_crawler_disabled_fails_before_http_client_creation(monkeypatch):
    import asyncio
    import httpx
    import pytest
    from app.core.settings import Settings
    from app.services.policy_crawler_service import PolicyCrawlerNetworkDisabledError
    from app.models.schemas import PolicyCrawlRequest

    client_created = False

    def fail_if_client_created(*args, **kwargs):
        nonlocal client_created
        client_created = True
        raise AssertionError("HTTP client must not be created when crawler network is disabled")

    monkeypatch.setattr(httpx, "AsyncClient", fail_if_client_created)

    service = PolicyCrawlerService(Settings(_env_file=None, policy_crawler_network_enabled=False))
    with pytest.raises(PolicyCrawlerNetworkDisabledError, match="POLICY_CRAWLER_NETWORK_ENABLED"):
        asyncio.run(service.crawl(PolicyCrawlRequest(projectId=1, sourceId=1, url="https://example.gov.cn/policy")))

    assert client_created is False


def test_policy_crawler_enabled_retains_http_fetch(monkeypatch):
    import asyncio
    import httpx
    from app.core.settings import Settings
    from app.models.schemas import PolicyCrawlRequest

    class FakeClient:
        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return None

        async def request(self, method, url, headers=None, extensions=None):
            assert url == "https://93.184.216.34/policy/single.html"
            assert headers["Host"] == "example.gov.cn"
            return httpx.Response(200, headers={"content-type": "text/html"},
                                  content="<html><body><h1>政策</h1><p>2026.07.12</p><p>正文</p></body></html>".encode(),
                                  request=httpx.Request("GET", url))

    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: FakeClient())
    monkeypatch.setattr(PolicyCrawlerService, "_resolve_public_addresses", lambda self, url: ["93.184.216.34"])
    async def allow(*args): return True
    monkeypatch.setattr(PolicyCrawlerService, "_robots_allowed", allow)
    service = PolicyCrawlerService(Settings(_env_file=None, policy_crawler_network_enabled=True))

    data, usage = asyncio.run(
        service.crawl(PolicyCrawlRequest(projectId=1, sourceId=1, url="https://example.gov.cn/policy/single.html"))
    )

    assert data.fetchedCount == 1
    assert usage["provider"] == "HTTPX"


def test_routes_policy_service_shares_settings(monkeypatch):
    from app.core.settings import Settings
    from app.api import routes

    settings = Settings(_env_file=None, policy_crawler_network_enabled=True)
    monkeypatch.setattr(routes, "get_settings", lambda: settings)

    assert routes.services()["policy"].settings is settings


def test_policy_crawler_rejects_private_metadata_and_non_http_urls(monkeypatch):
    import socket
    import pytest
    from app.services.policy_crawler_service import PolicyCrawlerUrlError

    service = PolicyCrawlerService(crawler_settings())
    with pytest.raises(PolicyCrawlerUrlError, match="private"):
        service._validate_url("http://127.0.0.1/policy")
    with pytest.raises(PolicyCrawlerUrlError, match="metadata"):
        service._validate_url("http://169.254.169.254/latest/meta-data")
    with pytest.raises(PolicyCrawlerUrlError, match="http"):
        service._validate_url("file:///etc/passwd")

    monkeypatch.setattr(socket, "getaddrinfo", lambda *args, **kwargs: [
        (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("10.0.0.8", 443))
    ])
    with pytest.raises(PolicyCrawlerUrlError, match="private"):
        service._validate_url("https://public.example/policy")


def test_policy_crawler_enforces_response_content_type_and_size():
    import httpx
    import pytest
    from app.services.policy_crawler_service import PolicyCrawlerResponseError

    service = PolicyCrawlerService(crawler_settings(policy_crawler_max_response_bytes=16))
    too_large = httpx.Response(200, headers={"content-type": "text/html"}, content=b"x" * 17)
    with pytest.raises(PolicyCrawlerResponseError, match="size"):
        service._ensure_usable_response(too_large)
    wrong_type = httpx.Response(200, headers={"content-type": "application/pdf"}, content=b"ok")
    with pytest.raises(PolicyCrawlerResponseError, match="Content-Type"):
        service._ensure_usable_response(wrong_type)
    declared_too_large = httpx.Response(200, headers={"content-type": "text/html", "content-length": "17"}, content=b"ok")
    with pytest.raises(PolicyCrawlerResponseError, match="size"):
        service._ensure_usable_response(declared_too_large)


def test_policy_crawler_retries_transient_fetches_with_bounded_attempts(monkeypatch):
    import asyncio
    import httpx
    from app.models.schemas import PolicyCrawlRequest

    class Client:
        attempts = 0
        async def __aenter__(self): return self
        async def __aexit__(self, *args): return None
        async def request(self, method, url, **kwargs):
            self.attempts += 1
            if self.attempts < 3:
                raise httpx.ConnectError("temporary", request=httpx.Request("GET", url))
            return httpx.Response(200, headers={"content-type": "text/html"},
                                  content=b"<html><body><h1>Policy</h1><p>content</p></body></html>",
                                  request=httpx.Request("GET", url))

    client = Client()
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: client)
    monkeypatch.setattr(PolicyCrawlerService, "_resolve_public_addresses", lambda self, url: ["93.184.216.34"])
    async def allow(*args): return True
    monkeypatch.setattr(PolicyCrawlerService, "_robots_allowed", allow)
    service = PolicyCrawlerService(crawler_settings(policy_crawler_max_retries=2, policy_crawler_retry_backoff_seconds=0))
    data, _ = asyncio.run(service.crawl(PolicyCrawlRequest(projectId=1, sourceId=1, url="https://example.gov/policy")))
    assert data.fetchedCount == 1
    assert client.attempts == 3


def test_policy_crawler_pins_validated_dns_address_for_request(monkeypatch):
    import asyncio
    import socket

    monkeypatch.setattr(socket, "getaddrinfo", lambda *args, **kwargs: [
        (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("93.184.216.34", 443))
    ])
    service = PolicyCrawlerService(crawler_settings(policy_crawler_request_interval_seconds=0))

    class Client:
        async def request(self, method, url, **kwargs):
            assert url == "https://93.184.216.34/policy"
            assert kwargs["headers"]["Host"] == "public.example"
            return object()

    asyncio.run(service._request_once(Client(), "https://public.example/policy"))


def test_policy_crawler_prefers_ipv4_when_host_has_unreachable_ipv6(monkeypatch):
    import socket

    monkeypatch.setattr(socket, "getaddrinfo", lambda *args, **kwargs: [
        (socket.AF_INET6, socket.SOCK_STREAM, 6, "", ("2408:8614:e20::1:2", 443, 0, 0)),
        (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("27.223.1.56", 443)),
    ])

    service = PolicyCrawlerService(crawler_settings())

    assert service._resolve_public_addresses("https://public.example/policy") == [
        "27.223.1.56",
        "2408:8614:e20::1:2",
    ]


def test_policy_crawler_checks_robots_and_redirect_targets(monkeypatch):
    import asyncio
    import httpx

    service = PolicyCrawlerService(crawler_settings(policy_crawler_max_redirects=1, policy_crawler_request_interval_seconds=0))
    validated = []
    monkeypatch.setattr(service, "_validate_url", lambda url: validated.append(url) or url)
    responses = iter([
        httpx.Response(302, headers={"location": "http://169.254.169.254/secret"}),
        httpx.Response(200, headers={"content-type": "text/html"}, content=b"ok"),
    ])
    monkeypatch.setattr(service, "_request_with_retries", lambda client, url: _async_value(next(responses)))
    asyncio.run(service._fetch(object(), "https://public.example/policy"))
    assert validated == ["https://public.example/policy", "http://169.254.169.254/secret"]

    from urllib.robotparser import RobotFileParser
    parser = RobotFileParser(); parser.parse(["User-agent: *", "Disallow: /private"])
    service._robots["https://public.example"] = parser
    assert asyncio.run(service._robots_allowed(object(), "https://public.example/private")) is False


def test_policy_crawler_deduplicates_links_and_limits_articles():
    service = PolicyCrawlerService(crawler_settings(policy_crawler_max_articles=1))
    links = service._extract_article_links(
        '<a href="/2026/a.html#top">A</a><a href="/2026/a.html">A duplicate</a><a href="/2026/b.html">B</a>',
        "https://public.example/news/",
    )
    assert links[:service.settings.policy_crawler_max_articles] == [("https://public.example/2026/a.html", "A")]


def test_policy_crawler_reports_partial_article_failures(monkeypatch):
    import asyncio

    service = PolicyCrawlerService(crawler_settings())
    async def article_result(client, url, title):
        if title == "bad":
            raise ValueError("blocked")
        return service._build_article("<h1>good</h1><p>body</p>", url, title)
    monkeypatch.setattr(service, "_crawl_one_article", article_result)
    articles, failed = asyncio.run(service._crawl_articles(object(), [("https://a.example/2026/1.html", "good"), ("https://a.example/2026/2.html", "bad")]))
    assert len(articles) == 1
    assert failed == 1


def test_policy_crawler_fails_closed_when_robots_cannot_be_loaded(monkeypatch):
    import asyncio
    import httpx

    service = PolicyCrawlerService(crawler_settings())
    async def unavailable(*args, **kwargs):
        raise httpx.ConnectError("robots unavailable")
    monkeypatch.setattr(service, "_fetch", unavailable)
    assert asyncio.run(service._robots_allowed(object(), "https://public.example/policy")) is False


def test_policy_crawler_allows_robots_404_but_fails_closed_on_auth_errors(monkeypatch):
    import asyncio
    import httpx

    async def response_for(status, **headers):
        return httpx.Response(status, headers={"content-type": "text/plain", **headers}, content=b"", request=httpx.Request("GET", "https://public.example/robots.txt")), "https://public.example/robots.txt"

    service = PolicyCrawlerService(crawler_settings())
    monkeypatch.setattr(service, "_fetch", lambda *args, **kwargs: response_for(404))
    assert asyncio.run(service._robots_allowed(object(), "https://public.example/policy")) is True

    service = PolicyCrawlerService(crawler_settings())
    monkeypatch.setattr(service, "_fetch", lambda *args, **kwargs: response_for(403))
    assert asyncio.run(service._robots_allowed(object(), "https://public.example/policy")) is False


def test_policy_crawler_allows_robots_410_as_missing_policy(monkeypatch):
    import asyncio
    import httpx

    async def gone(*args, **kwargs):
        return httpx.Response(410, headers={"content-type": "text/plain"}, content=b"", request=httpx.Request("GET", "https://public.example/robots.txt")), "https://public.example/robots.txt"

    service = PolicyCrawlerService(crawler_settings())
    monkeypatch.setattr(service, "_fetch", gone)
    assert asyncio.run(service._robots_allowed(object(), "https://public.example/policy")) is True


def test_policy_crawler_fails_closed_on_unexpected_robots_status(monkeypatch):
    import asyncio
    import httpx

    async def unavailable(*args, **kwargs):
        return httpx.Response(500, headers={"content-type": "text/plain"}, content=b"", request=httpx.Request("GET", "https://public.example/robots.txt")), "https://public.example/robots.txt"

    service = PolicyCrawlerService(crawler_settings())
    monkeypatch.setattr(service, "_fetch", unavailable)
    assert asyncio.run(service._robots_allowed(object(), "https://public.example/policy")) is False


async def _async_value(value):
    return value
