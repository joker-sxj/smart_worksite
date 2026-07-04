package com.xd.smartworksite.intelligence.infra;

import org.springframework.http.client.ClientHttpRequestFactory;

import java.time.Duration;

public interface ModelProviderRequestFactoryProvider {

    ClientHttpRequestFactory requestFactory(Duration timeout);
}
