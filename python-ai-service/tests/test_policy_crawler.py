from app.core.settings import Settings
from app.services.policy_crawler_service import PolicyCrawlerService


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
    except httpx.HTTPStatusError as exc:
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

    class FakeResponse:
        status_code = 200
        encoding = "utf-8"
        url = "https://example.gov.cn/policy/single.html"
        text = "<html><body><h1>政策</h1><p>2026.07.12</p><p>正文</p></body></html>"

        def raise_for_status(self):
            return None

    class FakeClient:
        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return None

        async def get(self, url, headers=None):
            assert url == "https://example.gov.cn/policy/single.html"
            return FakeResponse()

    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: FakeClient())
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
