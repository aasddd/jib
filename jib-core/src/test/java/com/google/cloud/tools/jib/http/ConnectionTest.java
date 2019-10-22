/*
 * Copyright 2017 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.http;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpMethods;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.cloud.tools.jib.api.LogEvent;
import com.google.cloud.tools.jib.blob.Blobs;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

/** Tests for {@link Connection}. */
@RunWith(MockitoJUnitRunner.class)
public class ConnectionTest {

  @FunctionalInterface
  private interface CallFunction {

    Response call(Connection connection, URL url, Request request) throws IOException;
  }

  @Mock private HttpTransport mockHttpTransport;
  @Mock private HttpTransport mockInsecureHttpTransport;
  @Mock private HttpRequestFactory mockHttpRequestFactory;
  @Mock private HttpRequestFactory mockInsecureHttpRequestFactory;
  @Mock private HttpRequest mockHttpRequest;
  @Mock private HttpRequest mockInsecureHttpRequest;
  @Mock private HttpResponse mockHttpResponse;
  @Mock private Consumer<LogEvent> logger;

  @Captor private ArgumentCaptor<GenericUrl> urlCaptor;

  private final GenericUrl fakeUrl = new GenericUrl("https://crepecake/fake/url");
  private final LongAdder totalByteCount = new LongAdder();

  @Before
  public void setUp() throws IOException {
    ByteArrayInputStream inStream = new ByteArrayInputStream(new byte[] {'b', 'o', 'd', 'y'});
    Mockito.when(mockHttpResponse.getContent()).thenReturn(inStream);
  }

  private Connection newHttpClient(boolean insecure, boolean authOverHttp) throws IOException {
    setUpMocks(mockHttpTransport, mockHttpRequestFactory, mockHttpRequest);
    if (insecure) {
      setUpMocks(
          mockInsecureHttpTransport, mockInsecureHttpRequestFactory, mockInsecureHttpRequest);
    }
    return new Connection(
        insecure, authOverHttp, logger, () -> mockHttpTransport, () -> mockInsecureHttpTransport);
  }

  private void setUpMocks(
      HttpTransport mockHttpTransport,
      HttpRequestFactory mockHttpRequestFactory,
      HttpRequest mockHttpRequest)
      throws IOException {
    Mockito.when(mockHttpTransport.createRequestFactory()).thenReturn(mockHttpRequestFactory);
    Mockito.when(
            mockHttpRequestFactory.buildRequest(Mockito.any(), urlCaptor.capture(), Mockito.any()))
        .thenReturn(mockHttpRequest);

    Mockito.when(mockHttpRequest.setHeaders(Mockito.any(HttpHeaders.class)))
        .thenReturn(mockHttpRequest);
    Mockito.when(mockHttpRequest.setConnectTimeout(Mockito.anyInt())).thenReturn(mockHttpRequest);
    Mockito.when(mockHttpRequest.setReadTimeout(Mockito.anyInt())).thenReturn(mockHttpRequest);
    Mockito.when(mockHttpRequest.execute()).thenReturn(mockHttpResponse);
  }

  @Test
  public void testGet() throws IOException {
    testCall(HttpMethods.GET, Connection::get);
  }

  @Test
  public void testPost() throws IOException {
    testCall(HttpMethods.POST, Connection::post);
  }

  @Test
  public void testPut() throws IOException {
    testCall(HttpMethods.PUT, Connection::put);
  }

  @Test
  public void testHttpTimeout_doNotSetByDefault() throws IOException {
    try (Response ignored = newHttpClient(false, false).get(fakeUrl.toURL(), fakeRequest(null))) {}

    Mockito.verify(mockHttpRequest, Mockito.never()).setConnectTimeout(Mockito.anyInt());
    Mockito.verify(mockHttpRequest, Mockito.never()).setReadTimeout(Mockito.anyInt());
  }

  @Test
  public void testHttpTimeout() throws IOException {
    Connection httpClient = newHttpClient(false, false);
    try (Response ignored = httpClient.get(fakeUrl.toURL(), fakeRequest(5982))) {}

    Mockito.verify(mockHttpRequest).setConnectTimeout(5982);
    Mockito.verify(mockHttpRequest).setReadTimeout(5982);
  }

  @Test
  public void testGet_nonHttpsServer_insecureConnectionAndFailoverDisabled()
      throws MalformedURLException, IOException {
    Connection httpClient = newHttpClient(false, false);
    try (Response response = httpClient.get(new URL("http://plain.http"), fakeRequest(null))) {
      Assert.fail("Should disallow non-HTTP attempt");
    } catch (SSLException ex) {
      Assert.assertEquals(
          "insecure HTTP connection not allowed: http://plain.http", ex.getMessage());
    }
  }

  @Test
  public void testCall_secureClientOnUnverifiableServer() throws IOException {
    Connection httpClient = newHttpClient(false, false);

    Mockito.when(mockHttpRequest.execute()).thenThrow(new SSLPeerUnverifiedException("unverified"));

    try (Response response = httpClient.get(new URL("https://insecure"), fakeRequest(null))) {
      Assert.fail("Secure caller should fail if cannot verify server");
    } catch (SSLException ex) {
      Assert.assertEquals("unverified", ex.getMessage());
      Mockito.verifyNoInteractions(logger);
    }
  }

  @Test
  public void testCall_insecureClientOnUnverifiableServer() throws IOException {
    Connection insecureHttpClient = newHttpClient(true, false);

    Mockito.when(mockHttpRequest.execute()).thenThrow(new SSLPeerUnverifiedException(""));

    try (Response response =
        insecureHttpClient.get(new URL("https://insecure"), fakeRequest(null))) {
      byte[] bytes = new byte[4];
      Assert.assertEquals(4, response.getBody().read(bytes));
      Assert.assertEquals("body", new String(bytes, StandardCharsets.UTF_8));
    }

    Assert.assertEquals(2, urlCaptor.getAllValues().size());
    Assert.assertEquals(new GenericUrl("https://insecure"), urlCaptor.getAllValues().get(0));
    Assert.assertEquals(new GenericUrl("https://insecure"), urlCaptor.getAllValues().get(1));

    String log =
        "Cannot verify server at https://insecure. Attempting again with no TLS verification.";
    Mockito.verify(logger).accept(LogEvent.info(log));
    Mockito.verifyNoMoreInteractions(logger);
  }

  @Test
  public void testCall_insecureClientOnHttpServer() throws IOException {
    Connection insecureHttpClient = newHttpClient(true, false);

    Mockito.when(mockHttpRequest.execute())
        .thenThrow(new SSLException("")) // server is not HTTPS
        .thenReturn(mockHttpResponse);
    Mockito.when(mockInsecureHttpRequest.execute())
        .thenThrow(new SSLException("")); // server is not HTTPS

    try (Response response =
        insecureHttpClient.get(new URL("https://insecure"), fakeRequest(null))) {
      byte[] bytes = new byte[4];
      Assert.assertEquals(4, response.getBody().read(bytes));
      Assert.assertEquals("body", new String(bytes, StandardCharsets.UTF_8));
    }

    Mockito.verify(mockHttpRequest, Mockito.times(2)).execute();
    Mockito.verify(mockInsecureHttpRequest, Mockito.times(1)).execute();

    Assert.assertEquals(3, urlCaptor.getAllValues().size());
    Assert.assertEquals(new GenericUrl("https://insecure"), urlCaptor.getAllValues().get(0));
    Assert.assertEquals(new GenericUrl("https://insecure"), urlCaptor.getAllValues().get(1));
    Assert.assertEquals(new GenericUrl("http://insecure"), urlCaptor.getAllValues().get(2));

    String log1 =
        "Cannot verify server at https://insecure. Attempting again with no TLS verification.";
    String log2 = "Failed to connect to https://insecure over HTTPS. Attempting again with HTTP.";
    Mockito.verify(logger).accept(LogEvent.info(log1));
    Mockito.verify(logger).accept(LogEvent.info(log2));
    Mockito.verifyNoMoreInteractions(logger);
  }

  @Test
  public void testCall_insecureClientOnHttpServerAndNoPortSpecified() throws IOException {
    Connection insecureHttpClient = newHttpClient(true, false);

    Mockito.when(mockHttpRequest.execute())
        .thenThrow(new ConnectException()) // server is not listening on 443
        .thenReturn(mockHttpResponse); // respond when connected through 80

    try (Response response =
        insecureHttpClient.get(new URL("https://insecure"), fakeRequest(null))) {
      byte[] bytes = new byte[4];
      Assert.assertEquals(4, response.getBody().read(bytes));
      Assert.assertEquals("body", new String(bytes, StandardCharsets.UTF_8));
    }

    Mockito.verify(mockHttpRequest, Mockito.times(2)).execute();
    Mockito.verifyNoInteractions(mockInsecureHttpRequest);

    Assert.assertEquals(2, urlCaptor.getAllValues().size());
    Assert.assertEquals(new GenericUrl("https://insecure"), urlCaptor.getAllValues().get(0));
    Assert.assertEquals(new GenericUrl("http://insecure"), urlCaptor.getAllValues().get(1));

    String log = "Failed to connect to https://insecure over HTTPS. Attempting again with HTTP.";
    Mockito.verify(logger).accept(LogEvent.info(log));
    Mockito.verifyNoMoreInteractions(logger);
  }

  @Test
  public void testCall_secureClientOnNonListeningServerAndNoPortSpecified() throws IOException {
    Connection httpClient = newHttpClient(false, false);

    Mockito.when(mockHttpRequest.execute())
        .thenThrow(new ConnectException("my exception")); // server not listening on 443

    try (Response response = httpClient.get(new URL("https://insecure"), fakeRequest(null))) {
      Assert.fail("Should not fall back to HTTP if port was explicitly given and cannot connect");
    } catch (ConnectException ex) {
      Assert.assertEquals("my exception", ex.getMessage());

      Assert.assertEquals(1, urlCaptor.getAllValues().size());
      Assert.assertEquals(new GenericUrl("https://insecure"), urlCaptor.getAllValues().get(0));

      Mockito.verify(mockHttpRequest, Mockito.times(1)).execute();
      Mockito.verifyNoInteractions(mockInsecureHttpRequest, logger);
    }
  }

  @Test
  public void testCall_insecureClientOnNonListeningServerAndPortSpecified() throws IOException {
    Connection insecureHttpClient = newHttpClient(true, false);

    Mockito.when(mockHttpRequest.execute())
        .thenThrow(new ConnectException("my exception")); // server is not listening on 5000

    try (Response response =
        insecureHttpClient.get(new URL("https://insecure:5000"), fakeRequest(null))) {
      Assert.fail("Should not fall back to HTTP if port was explicitly given and cannot connect");
    } catch (ConnectException ex) {
      Assert.assertEquals("my exception", ex.getMessage());

      Assert.assertEquals(1, urlCaptor.getAllValues().size());
      Assert.assertEquals(new GenericUrl("https://insecure:5000"), urlCaptor.getAllValues().get(0));

      Mockito.verify(mockHttpRequest, Mockito.times(1)).execute();
      Mockito.verifyNoInteractions(mockInsecureHttpRequest, logger);
    }
  }

  @Test
  public void testCall_timeoutFromConnectException() throws IOException {
    Connection insecureHttpClient = newHttpClient(true, false);

    Mockito.when(mockHttpRequest.execute()).thenThrow(new ConnectException("Connection timed out"));

    try (Response response =
        insecureHttpClient.get(new URL("https://insecure"), fakeRequest(null))) {
      Assert.fail("Should not fall back to HTTP if timed out even for ConnectionException");
    } catch (ConnectException ex) {
      Assert.assertEquals("Connection timed out", ex.getMessage());

      Assert.assertEquals(1, urlCaptor.getAllValues().size());
      Assert.assertEquals(new GenericUrl("https://insecure"), urlCaptor.getAllValues().get(0));

      Mockito.verify(mockHttpRequest, Mockito.times(1)).execute();
      Mockito.verifyNoInteractions(mockInsecureHttpRequest, logger);
    }
  }

  private Request fakeRequest(Integer httpTimeout) {
    return Request.builder()
        .setAccept(Arrays.asList("fake.accept", "another.fake.accept"))
        .setUserAgent("fake user agent")
        .setBody(
            new BlobHttpContent(Blobs.from("crepecake"), "fake.content.type", totalByteCount::add))
        .setAuthorization(Authorization.fromBasicCredentials("fake-username", "fake-secret"))
        .setHttpTimeout(httpTimeout)
        .build();
  }

  private void testCall(String httpMethod, CallFunction callFunction) throws IOException {
    Connection httpClient = newHttpClient(false, false);
    try (Response ignored = callFunction.call(httpClient, fakeUrl.toURL(), fakeRequest(null))) {}

    ArgumentCaptor<HttpHeaders> httpHeadersArgumentCaptor =
        ArgumentCaptor.forClass(HttpHeaders.class);
    ArgumentCaptor<BlobHttpContent> blobHttpContentArgumentCaptor =
        ArgumentCaptor.forClass(BlobHttpContent.class);

    Mockito.verify(mockHttpRequest).setHeaders(httpHeadersArgumentCaptor.capture());

    Assert.assertEquals(
        "fake.accept,another.fake.accept", httpHeadersArgumentCaptor.getValue().getAccept());
    Assert.assertEquals("fake user agent", httpHeadersArgumentCaptor.getValue().getUserAgent());
    // Base64 representation of "fake-username:fake-secret"
    Assert.assertEquals(
        "Basic ZmFrZS11c2VybmFtZTpmYWtlLXNlY3JldA==",
        httpHeadersArgumentCaptor.getValue().getAuthorization());

    Mockito.verify(mockHttpRequestFactory)
        .buildRequest(
            Mockito.eq(httpMethod), Mockito.eq(fakeUrl), blobHttpContentArgumentCaptor.capture());
    Assert.assertEquals("fake.content.type", blobHttpContentArgumentCaptor.getValue().getType());

    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    blobHttpContentArgumentCaptor.getValue().writeTo(byteArrayOutputStream);

    Assert.assertEquals(
        "crepecake", new String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8));
    Assert.assertEquals("crepecake".length(), totalByteCount.longValue());
  }
}
