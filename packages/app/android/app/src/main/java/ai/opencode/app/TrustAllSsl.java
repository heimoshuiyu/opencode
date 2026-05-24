package ai.opencode.app;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.HttpsURLConnection;
import java.security.cert.X509Certificate;
import java.security.SecureRandom;

/**
 * Installs a trust-all SSL configuration so the app can connect to
 * backends that use self-signed certificates. Acceptable for an MVP
 * fork where operator convenience outweighs strict certificate validation.
 */
public class TrustAllSsl {

    public static void install() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new SecureRandom());

            SSLSocketFactory factory = sslContext.getSocketFactory();
            HttpsURLConnection.setDefaultSSLSocketFactory(factory);
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception ignored) {}
    }
}
