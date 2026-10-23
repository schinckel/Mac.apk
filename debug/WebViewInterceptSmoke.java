package android.webkit;

import android.os.Looper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** End-to-end HTTPS interception and synchronous Java bridge regression. */
public final class WebViewInterceptSmoke {
    private static WebView view;
    private static volatile boolean documentSeen;
    private static volatile boolean scriptSeen;
    private static String networkOrigin;
    private static String appOrigin = "https://localhost";
    private static String tlsOrigin;
    private static volatile boolean rejectionSeen;
    private static volatile boolean resultSeen;
    private static volatile boolean cancellationClosed = System.getenv("MACAPK_SMOKE_CANCEL") == null;
    private static final java.util.concurrent.atomic.AtomicInteger failureNetworkHits = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger deniedNetworkHits = new java.util.concurrent.atomic.AtomicInteger();

    private static void startFixtureServers() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            networkOrigin = "http://127.0.0.1:" + server.getAddress().getPort();
            if (System.getenv("MACAPK_SMOKE_HTTP") != null) appOrigin = networkOrigin;
            server.createContext("/", WebViewInterceptSmoke::serveNetwork);
            server.start();
            startTlsServer();
        } catch (Exception e) {
            fail("test server failed: " + e);
        }
    }

    private static void serveNetwork(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("-failure")) failureNetworkHits.incrementAndGet();
        if (path.equals("/deny")) deniedNetworkHits.incrementAndGet();
        System.out.println("TEST network " + exchange.getRequestMethod() + " " + path);
        if (path.equals("/redirect")) {
            exchange.getResponseHeaders().set("Location", "/landing");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
        }
        if (path.equals("/redirect-cross")) {
            exchange.getResponseHeaders().set("Location", networkOrigin.replace("127.0.0.1", "localhost") + "/cookie-set");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", appOrigin);
            exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
        }
        if (!path.equals("/deny")) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", appOrigin);
            exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");
        }
        if (exchange.getRequestMethod().equals("OPTIONS")) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (path.equals("/cookie-set")) exchange.getResponseHeaders().set("Set-Cookie", "smoke=present; Path=/; SameSite=Lax");
        if (path.equals("/cookie-set-default")) exchange.getResponseHeaders().set("Set-Cookie", "plain=present; Path=/");
        String body = networkBody(exchange);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void startTlsServer() throws Exception {
        String certificate = System.getenv("MACAPK_SMOKE_KEYSTORE");
        if (certificate != null) {
            char[] password = System.getenv("MACAPK_SMOKE_CERT_PASSWORD").toCharArray();
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (java.io.InputStream stream = Files.newInputStream(Path.of(certificate))) {
                store.load(stream, password);
            }
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, password);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(keys.getKeyManagers(), null, null);
            HttpsServer untrusted = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            untrusted.setHttpsConfigurator(new HttpsConfigurator(ssl));
            untrusted.createContext("/", exchange -> {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", appOrigin);
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
            });
            untrusted.start();
            tlsOrigin = "https://127.0.0.1:" + untrusted.getAddress().getPort();
        }
    }

    private static String networkBody(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
        return switch (exchange.getRequestURI().getPath()) {
            case "/landing" -> "redirected";
            case "/cookie-read" -> String.valueOf(exchange.getRequestHeaders().getFirst("Cookie"));
            case "/authorization" -> String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
            case "/post" -> exchange.getRequestMethod() + ":"
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            default -> "network";
        };
    }

    public static void main(String[] args) {
        if (System.getenv("MACAPK_SMOKE_API") != null) {
            WebResourceResponse response = new WebResourceResponse("text/plain", "UTF-8", null);
            if (response.getStatusCode() != 0 || response.getReasonPhrase() != null) {
                fail("three-argument response defaults differ from AOSP");
            }
            System.out.println("PASS WebResourceResponse: AOSP defaults");
            System.exit(0);
        }
        startFixtureServers();
        Looper.prepareMainLooper();
        if (System.getenv("MACAPK_SMOKE_CANCEL") != null) startCancellationScenario();
        else startPage();
        Looper.loop();
    }

    private static void startCancellationScenario() {
        java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(4);
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(4);
        java.util.List<WebView> abandoned = new java.util.ArrayList<>();
        for (int i = 0; i < 4; ++i) {
            WebView blocked = new WebView(null);
            blocked.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView webView, WebResourceRequest request) {
                    return new WebResourceResponse("text/html", "UTF-8", new BlockingStream(reading, closed));
                }
            });
            abandoned.add(blocked);
            blocked.loadUrl("https://localhost/stalled");
        }
        new Thread(() -> {
            try {
                if (!reading.await(10, java.util.concurrent.TimeUnit.SECONDS)) fail("stalled reads did not start");
                new android.os.Handler(Looper.getMainLooper()).post(() -> {
                    for (WebView blocked : abandoned) blocked.destroy();
                    startPage();
                });
                if (!closed.await(10, java.util.concurrent.TimeUnit.SECONDS)) fail("cancelled streams were not closed");
                cancellationClosed = true;
                new android.os.Handler(Looper.getMainLooper()).post(WebViewInterceptSmoke::finish);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("cancellation test interrupted");
            }
        }, "CancellationProbe").start();
    }

    private static final class BlockingStream extends java.io.InputStream {
        private final java.util.concurrent.CountDownLatch reading;
        private final java.util.concurrent.CountDownLatch closed;
        private boolean stopped;

        BlockingStream(java.util.concurrent.CountDownLatch reading, java.util.concurrent.CountDownLatch closed) {
            this.reading = reading;
            this.closed = closed;
        }

        @Override public synchronized int read() throws java.io.IOException {
            reading.countDown();
            while (!stopped) {
                try { wait(); }
                catch (InterruptedException ignored) { /* Closing the stream must unblock this read. */ }
            }
            throw new java.io.IOException("cancelled fixture stream");
        }

        @Override public synchronized void close() {
            if (stopped) return;
            stopped = true;
            closed.countDown();
            notifyAll();
        }
    }

    private static void startPage() {
        if (appOrigin.startsWith("http:")) {
            String crossOrigin = networkOrigin.replace("127.0.0.1", "localhost");
            CookieManager.getInstance().setCookie(crossOrigin, "strict=private; Path=/; SameSite=Strict");
            CookieManager.getInstance().setCookie(crossOrigin, "lax=private; Path=/; SameSite=Lax");
        }
        view = new WebView(null);
        view.getSettings().setJavaScriptEnabled(true);
        view.addJavascriptInterface(new Result(), "NativeProbe");
        view.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                if (message.message().contains("Uncaught (in promise) Error: smoke rejection")) {
                    rejectionSeen = true;
                    finish();
                }
                return true;
            }
        });
        view.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(
                    WebView webView, WebResourceRequest request) {
                return interceptRequest(request);
            }

            @Override public void onReceivedError(WebView webView, WebResourceRequest request,
                    WebResourceError error) {
                fail("load failed: " + request.getUrl() + " code=" + error.getErrorCode());
            }
        });
        view.loadUrl(appOrigin + "/");
    }

    private static WebResourceResponse interceptRequest(WebResourceRequest request) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            fail("interception ran on the UI thread");
        }
        String url = request.getUrl().toString();
        if (url.equals(appOrigin + "/")) {
            if (!"GET".equals(request.getMethod())) fail("wrong request method");
            documentSeen = request.isForMainFrame();
            return response("text/html", 200,
                    "<!doctype html><script src='/main.js'></script>");
        }
        if (url.equals(appOrigin + "/main.js")) {
            scriptSeen = !request.isForMainFrame();
            return new WebResourceResponse("application/javascript", "UTF-8",
                    new ByteArrayInputStream(pageScript().getBytes(StandardCharsets.UTF_8)));
        }
        if (url.equals(appOrigin + "/encoded") || url.equals(appOrigin + "/encoded-document")) {
            return encodedResponse(url.endsWith("/encoded-document"));
        }
        if (url.equals(appOrigin + "/callback-failure")) throw new IllegalStateException("fixture callback failure");
        if (url.equals(appOrigin + "/stream-failure")) return new WebResourceResponse("text/plain", "UTF-8",
                new java.io.InputStream() {
                    @Override public int read() throws java.io.IOException { throw new java.io.IOException("fixture read failure"); }
                });
        if (url.equals(appOrigin + "/missing")) {
            if (request.getRequestHeaders().entrySet().stream().noneMatch(
                    entry -> entry.getKey().equalsIgnoreCase("X-Smoke") && entry.getValue().equals("request"))) {
                fail("request header lost");
            }
            return response("text/plain", 404, "missing");
        }
        return null;
    }

    private static WebResourceResponse encodedResponse(boolean document) {
        String mime = document ? "text/html" : "text/plain";
        String body = document ? "<p>Привет</p>" : "Привет";
        return new WebResourceResponse(mime, "windows-1251", 200, "OK", Map.of("Content-Type", mime),
                new ByteArrayInputStream(body.getBytes(java.nio.charset.Charset.forName("windows-1251"))));
    }

    private static String pageScript() {
        String networkChecks = appOrigin.startsWith("http:") ? """
            async function checkSameSiteRequests() {
                const post=await fetch('/post',{method:'POST',body:'payload'}).then(r=>r.text());
                await fetch('/cookie-set'); const cookie=await fetch('/cookie-read').then(r=>r.text());
                const omitted=await fetch('/cookie-read',{credentials:'omit'}).then(r=>r.text());
                if(omitted!=='null') throw Error('omitted credentials leaked');
                document.cookie='smoke=; Max-Age=0; Path=/';
                const deleted=await fetch('/cookie-read').then(r=>r.text());
                if(deleted!=='null') throw Error('deleted cookie leaked');
                return {post,cookie};
            }
            async function checkRedirect() {
                const redirected=await fetch('/redirect');
                if(!redirected.redirected || !redirected.url.endsWith('/landing') || await redirected.text()!=='redirected')
                    throw Error('redirect semantics lost');
            }
            async function checkCrossOriginAccess(cross) {
                if(await fetch(cross+'/allow').then(r=>r.text())!=='network') throw Error('cross-origin control failed');
                const authorized=await fetch(cross+'/authorization',{headers:{Authorization:'Bearer fixture'}}).then(r=>r.text());
                if(authorized!=='Bearer fixture') throw Error('cross-site authorization header lost');
                let cors=false; try { await fetch(cross+'/deny'); } catch(e) { cors=true; }
                return cors;
            }
            async function checkCrossSiteCookies(cross) {
                const seeded=NativeProbe.cookies(cross);
                if(!seeded.includes('strict=private') || !seeded.includes('lax=private')) throw Error('cross-site fixture cookies missing');
                const crossCookies=await fetch(cross+'/cookie-read',{method:'POST',credentials:'include'}).then(r=>r.text());
                if(crossCookies!=='null') throw Error('cross-site SameSite cookies leaked: '+crossCookies);
                await fetch('/redirect-cross',{credentials:'include'});
                if(NativeProbe.cookies(cross).includes('smoke=present')) throw Error('redirect used stale cookie context');
                await fetch(cross+'/cookie-set-default',{credentials:'include'});
                if(NativeProbe.cookies(cross).includes('plain=present')) throw Error('cross-site default cookie accepted');
                await fetch(cross+'/cookie-set',{credentials:'include'});
                if(NativeProbe.cookies(cross).includes('smoke=present')) throw Error('cross-site Lax cookie accepted');
            }
            async function checkTls() {
                let rejected=false; try { await fetch('__TLS__/'); } catch(e) { rejected=true; }
                if(!rejected) throw Error('untrusted TLS accepted');
            }
            ({post,cookie}=await checkSameSiteRequests());
            await checkRedirect();
            const cross='__CROSS__';
            cors=await checkCrossOriginAccess(cross);
            await checkCrossSiteCookies(cross);
            await checkTls();
            """.replace("__CROSS__", networkOrigin.replace("127.0.0.1", "localhost"))
                .replace("__TLS__", String.valueOf(tlsOrigin)) : "";
        return """
            Promise.reject(new Error('smoke rejection'));
            (async()=>{try{
                const r=await fetch('/missing',{headers:{'X-Smoke':'request'}});
                await new Promise((resolve,reject)=>{
                    const xhr=new XMLHttpRequest(); xhr.open('GET','/encoded');
                    xhr.onerror=()=>reject(Error('encoded response failed'));
                    xhr.onload=()=>{
                        if(xhr.responseText!=='Привет') return reject(Error('explicit response encoding lost: '+xhr.responseText));
                        if(xhr.getResponseHeader('Content-Type')!=='text/plain') return reject(Error('response header was rewritten'));
                        resolve();
                    };
                    xhr.send();
                });
                await new Promise((resolve,reject)=>{
                    const xhr=new XMLHttpRequest(); xhr.open('GET','/encoded-document'); xhr.responseType='document';
                    xhr.onerror=()=>reject(Error('encoded document failed'));
                    xhr.onload=()=>{
                        if(!xhr.responseXML || xhr.responseXML.body.textContent!=='Привет')
                            return reject(Error('explicit document metadata lost: '+(xhr.responseXML
                                ? JSON.stringify({type:xhr.responseXML.contentType,encoding:xhr.responseXML.characterSet,body:xhr.responseXML.body.textContent}) : 'no document')));
                        if(xhr.getResponseHeader('Content-Type')!=='text/html') return reject(Error('document header was rewritten'));
                        resolve();
                    };
                    xhr.send();
                });
                for(const path of ['/callback-failure','/stream-failure']) {
                    let rejected=false; try { await fetch(path); } catch(e) { rejected=true; }
                    if(!rejected) throw Error('interception failure became a network response: '+path);
                }
                let post='POST:payload',cors=true,cookie='smoke=present';
            """ + networkChecks + """
                NativeProbe.complete(JSON.stringify({origin:location.origin,secure:isSecureContext,
                    status:r.status,statusText:r.statusText,header:r.headers.get('X-Probe'),
                    body:await r.text(),echo:NativeProbe.echo('native'),post,cors,cookie}));
            }catch(e){NativeProbe.complete('ERROR '+e)}})();
            """;
    }

    private static WebResourceResponse response(String mime, int status, String body) {
        return new WebResourceResponse(mime, "UTF-8", status, status == 200 ? "OK" : "Asset unavailable",
                Map.of("X-Probe", "intercepted"),
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static void check(boolean condition, String message) {
        if (!condition) fail(message);
    }

    private static void fail(String message) {
        System.err.println("FAIL WebViewInterceptSmoke: " + message);
        System.exit(1);
    }

    private static void finish() {
        if (!resultSeen || !rejectionSeen || !cancellationClosed) return;
        System.out.println("PASS WebViewInterceptSmoke: " + appOrigin + " document, subresource, response, native bridge, rejection message");
        System.exit(0);
    }

    public static final class Result {
        @JavascriptInterface public String echo(String value) { return "echo:" + value; }
        @JavascriptInterface public String cookies(String url) { return String.valueOf(CookieManager.getInstance().getCookie(url)); }

        @JavascriptInterface public void complete(String value) {
            try {
                org.json.JSONObject result = new org.json.JSONObject(value);
                check(documentSeen && scriptSeen, "document or subresource callback missing");
                check(failureNetworkHits.get() == 0, "interception failure retried against network");
                check(!appOrigin.startsWith("http:") || deniedNetworkHits.get() > 0, "CORS denial never reached server");
                check(appOrigin.equals(result.getString("origin")), "origin changed");
                check(result.getBoolean("secure"), "secure context lost");
                check(result.getInt("status") == 404, "response status lost");
                check("Asset unavailable".equals(result.getString("statusText")), "response reason phrase lost");
                check("intercepted".equals(result.getString("header")), "response header lost");
                check("missing".equals(result.getString("body")), "response body lost");
                check("echo:native".equals(result.getString("echo")), "native bridge failed");
                check("POST:payload".equals(result.getString("post")), "network request body lost");
                check(result.getBoolean("cors"), "cross-origin policy bypassed");
                check(result.getString("cookie").contains("smoke=present"), "network response cookie lost: " + result.getString("cookie"));
                resultSeen = true;
                finish();
            } catch (Exception e) {
                fail("invalid page result: " + value + " " + e);
            }
        }
    }
}
