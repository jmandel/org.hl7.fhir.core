package org.hl7.fhir.utilities.http;

import okhttp3.*;
import org.hl7.fhir.utilities.ToolingClientLogger;
import org.hl7.fhir.utilities.http.okhttpimpl.LoggingInterceptor;
import org.hl7.fhir.utilities.http.okhttpimpl.ProxyAuthenticator;
import org.hl7.fhir.utilities.http.okhttpimpl.RetryInterceptor;
import org.hl7.fhir.utilities.settings.ServerDetailsPOJO;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class ManagedFhirWebAccessor extends ManagedWebAccessorBase<ManagedFhirWebAccessor> {

  /**
   * The singleton instance of the HttpClient, used for all requests.
   */
  private static OkHttpClient okHttpClient;

  /**
   * Global (JVM-wide) cap on concurrent FHIR-server HTTP requests issued through this accessor.
   * <p/>
   * All FHIR tooling client traffic (terminology validate-code, batch validate-code, expand,
   * subsumes, lookup, metadata/capabilities, read, search, transaction) flows through
   * {@link #httpCall(HTTPRequest)} via the version-specific FhirRequestBuilders, so a semaphore
   * here throttles every terminology-server request from every thread. Public terminology servers
   * (notably tx.fhir.org) shed load (nginx 404) when a single IP issues too many concurrent
   * requests; bounding concurrency avoids that while leaving total request volume unchanged.
   * <p/>
   * The semaphore is intentionally static: a new ManagedFhirWebAccessor is constructed per request
   * (see ManagedWebAccess.fhirAccessor()), and the tooling runs one build per JVM, so a static
   * semaphore is the only way to get a process-wide bound across all accessor instances and threads.
   * <p/>
   * Permits default to 4 and can be configured with the system property
   * {@code org.hl7.fhir.tx.maxConcurrency} (absent, unparseable, or &lt;= 0 means the default of 4;
   * a value &gt;= 64 effectively disables throttling for typical thread counts).
   */
  private static final Semaphore REQUEST_THROTTLE = new Semaphore(maxConcurrency(), true);

  private static int maxConcurrency() {
    final int defaultPermits = 4;
    Integer configured = configuredMaxConcurrency();
    return configured == null ? defaultPermits : configured;
  }

  /** the explicitly configured org.hl7.fhir.tx.maxConcurrency, or null when absent/unparseable/non-positive */
  private static Integer configuredMaxConcurrency() {
    String value = System.getProperty("org.hl7.fhir.tx.maxConcurrency");
    if (value == null) {
      return null;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      return parsed <= 0 ? null : parsed;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Adaptive (AIMD-style) concurrency control for FHIR-server HTTP traffic.
   * <p/>
   * Starts at min(12, max). Any 404/429/503 response (public terminology servers shed load with
   * these; tx.fhir.org's nginx notably answers 404 under overload) halves the permitted concurrency
   * (floor 2) and the request is retried only after a backoff. Every 50 consecutive successful
   * responses creep the permit count back up by 1, up to the max.
   * <p/>
   * Enabled by {@code org.hl7.fhir.tx.adaptiveConcurrency=true|false}; default is true when
   * {@code org.hl7.fhir.tx.maxConcurrency} is not explicitly set, otherwise the explicit static
   * value is honored (adaptive off) unless adaptiveConcurrency is explicitly true.
   */
  static class AdaptiveThrottle {
    private final int maxPermits;
    private int permits;
    private int inFlight;
    private int successStreak;

    AdaptiveThrottle(int maxPermits) {
      this.maxPermits = Math.max(2, maxPermits);
      this.permits = Math.min(12, this.maxPermits);
    }

    synchronized void acquire() throws IOException {
      while (inFlight >= permits) {
        try {
          wait(1000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while waiting to issue FHIR HTTP request", e);
        }
      }
      inFlight++;
    }

    /** release a permit; throttled = response was a load-shedding status, success = a usable response */
    synchronized void release(boolean throttled, boolean success) {
      inFlight--;
      if (throttled) {
        permits = Math.max(2, permits / 2);
        successStreak = 0;
      } else if (success) {
        successStreak++;
        if (successStreak >= 50) {
          successStreak = 0;
          if (permits < maxPermits) {
            permits++;
          }
        }
      }
      notifyAll();
    }

    synchronized int currentPermits() {
      return permits;
    }
  }

  /**
   * Hermetic terminology mode: {@code -Dorg.hl7.fhir.tx.hermetic=true} makes every FHIR-server HTTP
   * request through this accessor (the single choke point all terminology/FHIR client traffic flows
   * through) fail immediately with a {@link TxHermeticViolationError} that lists the request, instead
   * of touching the network. Used together with the terminology answer pack
   * ({@code -Dorg.hl7.fhir.tx.pack}) to prove that a build is fully answerable offline.
   */
  private static final boolean HERMETIC = Boolean.parseBoolean(System.getProperty("org.hl7.fhir.tx.hermetic"));

  /**
   * Thrown for any FHIR-server HTTP request attempted while hermetic mode is active. Extends
   * {@link Error} deliberately: terminology clients catch {@code Exception} and convert failures into
   * cached "Error performing tx ..." results, which would both hide the violation and poison the
   * mutable cache. An Error propagates and fails the run loudly at the first network attempt.
   */
  public static class TxHermeticViolationError extends Error {
    public TxHermeticViolationError(String message) {
      super(message);
    }
  }

  private static String describeBlockedRequest(HTTPRequest r) {
    StringBuilder b = new StringBuilder();
    b.append("Hermetic terminology mode (-Dorg.hl7.fhir.tx.hermetic=true) is active, but a FHIR server HTTP request was attempted: ");
    b.append(r.getMethod()).append(" ").append(r.getUrl());
    if (r.getBody() != null) {
      String body = new String(r.getBody(), java.nio.charset.StandardCharsets.UTF_8);
      if (body.length() > 4096) {
        body = body.substring(0, 4096) + "...(" + r.getBody().length + " bytes total)";
      }
      b.append("\nrequest body: ").append(body);
    }
    return b.toString();
  }

  private static final int THROTTLE_RETRY_LIMIT = 4;

  private static final AdaptiveThrottle ADAPTIVE_THROTTLE = initAdaptiveThrottle();

  private static AdaptiveThrottle initAdaptiveThrottle() {
    Integer configuredMax = configuredMaxConcurrency();
    String prop = System.getProperty("org.hl7.fhir.tx.adaptiveConcurrency");
    boolean adaptive;
    if (prop != null) {
      adaptive = "true".equals(prop.trim());
    } else {
      adaptive = configuredMax == null;
    }
    if (!adaptive) {
      return null;
    }
    return new AdaptiveThrottle(configuredMax == null ? 64 : configuredMax);
  }

  private static boolean isLoadSheddingStatus(int code) {
    return code == 404 || code == 429 || code == 503;
  }

  private long timeout;
  private TimeUnit timeoutUnit;
  private int retries;
  private ToolingClientLogger logger;
  private LoggingInterceptor loggingInterceptor;

  public ManagedFhirWebAccessor withTimeout(long timeout, TimeUnit timeoutUnit) {
    this.timeout = timeout;
    this.timeoutUnit = timeoutUnit;
    return this;
  }

  public ManagedFhirWebAccessor withRetries(int retries) {
    this.retries = retries;
    return this;
  }

  public ManagedFhirWebAccessor withLogger(ToolingClientLogger logger) {
    this.logger = logger;
    this.loggingInterceptor = new LoggingInterceptor(logger);
    return this;
  }

  public ManagedFhirWebAccessor(String userAgent, IHTTPAuthenticationProvider authenticationProvider) {
    super(Arrays.asList("fhir"), userAgent, authenticationProvider);
    this.timeout = 5000;
    this.timeoutUnit = TimeUnit.MILLISECONDS;
  }

  protected HTTPRequest httpRequestWithDefaultHeaders(HTTPRequest request) {
    List<HTTPHeader> headers = new ArrayList<>();
    if (HTTPHeaderUtil.getSingleHeader(request.getHeaders(), HTTPHeaderUtil.USER_AGENT) == null
      && getUserAgent() != null) {
      headers.add(new HTTPHeader(HTTPHeaderUtil.USER_AGENT, getUserAgent()));
    }
    request.getHeaders().forEach(headers::add);
    return request.withHeaders(headers);
  }

  protected HTTPRequest requestWithAuthorizationHeaders(HTTPRequest httpRequest) {
    HTTPRequest requestWithDefaultHeaders = httpRequestWithDefaultHeaders(httpRequest);

    List<HTTPHeader> headers = new ArrayList<>();
    requestWithDefaultHeaders.getHeaders().forEach(headers::add);

    for (Map.Entry<String, String> entry : this.getHeaders().entrySet()) {
      headers.add(new HTTPHeader(entry.getKey(), entry.getValue()));
    }

    if (getHttpAuthHeaderProvider() != null && getHttpAuthHeaderProvider().canProvideHeaders(httpRequest.getUrl())) {
      for (Map.Entry<String, String> entry : getHttpAuthHeaderProvider().getHeaders(httpRequest.getUrl()).entrySet()) {
           headers.add(new HTTPHeader(entry.getKey(), entry.getValue()));
      }
    }
    return httpRequest.withHeaders(headers);
  }

  public HTTPResult httpCall(HTTPRequest httpRequest) throws IOException {
    if (HERMETIC) {
      throw new TxHermeticViolationError(describeBlockedRequest(httpRequest));
    }
    if (ADAPTIVE_THROTTLE != null) {
      return httpCallAdaptive(httpRequest);
    }
    try {
      REQUEST_THROTTLE.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting to issue FHIR HTTP request to " + httpRequest.getUrl(), e);
    }
    try {
      return httpCallInner(httpRequest);
    } finally {
      REQUEST_THROTTLE.release();
    }
  }

  /**
   * Adaptive path: acquire a permit, issue the request, and on a load-shedding response (404/429/503)
   * halve the concurrency and retry this request only, after a backoff that grows with each attempt.
   * The permit is released before backing off so other threads are not blocked by the sleeping request.
   */
  private HTTPResult httpCallAdaptive(HTTPRequest httpRequest) throws IOException {
    int attempt = 0;
    while (true) {
      ADAPTIVE_THROTTLE.acquire();
      HTTPResult result = null;
      boolean throttled = false;
      try {
        result = httpCallInner(httpRequest);
        throttled = isLoadSheddingStatus(result.getCode());
      } finally {
        ADAPTIVE_THROTTLE.release(throttled, result != null && !throttled);
      }
      if (!throttled || attempt >= THROTTLE_RETRY_LIMIT) {
        return result;
      }
      attempt++;
      try {
        Thread.sleep(Math.min(250L * (1L << attempt), 5000L));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return result;
      }
    }
  }

  private HTTPResult httpCallInner(HTTPRequest httpRequest) throws IOException {
      switch (ManagedWebAccess.getAccessPolicy()) {
        case DIRECT: {
          HTTPRequest requestWithAuthorizationHeaders = requestWithAuthorizationHeaders(httpRequest);
          assert requestWithAuthorizationHeaders.getUrl() != null;

          RequestBody body = requestWithAuthorizationHeaders.getBody() == null ? null : RequestBody.create(requestWithAuthorizationHeaders.getBody());
          Request.Builder requestBuilder = new Request.Builder()
            .url(requestWithAuthorizationHeaders.getUrl())
            .method(requestWithAuthorizationHeaders.getMethod().name(), body);

          for (HTTPHeader header : requestWithAuthorizationHeaders.getHeaders()) {
            requestBuilder.addHeader(header.getName(), header.getValue());
          }
          OkHttpClient okHttpClient = getOkHttpClient();

          if (!ManagedWebAccess.inAllowedPaths(requestWithAuthorizationHeaders.getUrl().toString())) {
            throw new IOException("The pathname '" + requestWithAuthorizationHeaders.getUrl().toString() + "' cannot be accessed by policy");
          }
          Response response = okHttpClient.newCall(requestBuilder.build()).execute();
          return getHTTPResult(response);
        }
        case MANAGED:
          HTTPRequest requestWithAuthorizationHeaders = requestWithAuthorizationHeaders(httpRequest);
          assert requestWithAuthorizationHeaders.getUrl() != null;
          return ManagedWebAccess.getFhirWebAccessor().httpCall(requestWithAuthorizationHeaders);
        case PROHIBITED:
          throw new IOException("Access to the internet is not allowed by local security policy");
        default:
          throw new IOException("Internal Error");
      }
  }

  private HTTPResult getHTTPResult(Response execute) throws IOException {
    return new HTTPResult(execute.request().url().toString(), execute.code(), execute.message(), execute.header("Content-Type"), execute.body() != null && execute.body().contentLength() != 0 ? execute.body().bytes() : null, getHeadersFromResponse(execute));
  }

  private Iterable<HTTPHeader> getHeadersFromResponse(Response response) {
    List<HTTPHeader> headers = new ArrayList<>();
    for (String name : response.headers().names()) {
      headers.add(new HTTPHeader(name, response.header(name)));
    }
    return headers;
  }

  private OkHttpClient getOkHttpClient() {
    if (okHttpClient == null) {
      okHttpClient = new OkHttpClient();
    }
    OkHttpClient.Builder builder = okHttpClient.newBuilder();
    if (logger != null) builder.addInterceptor(loggingInterceptor);
    builder.addInterceptor(new RetryInterceptor(retries));
    builder.proxyAuthenticator(new ProxyAuthenticator());
    return builder.connectTimeout(timeout, timeoutUnit)
      .writeTimeout(timeout, timeoutUnit)
      .readTimeout(timeout, timeoutUnit).build();
  }

}
