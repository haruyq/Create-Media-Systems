package org.haruyq.createmediasystems.stream;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** The pages the API resolves to their media (see API.md); does not grant access outside the server allow-list. */
public final class ProviderUrls {
    private static final Pattern CCTV = Pattern.compile("(?:[a-z0-9-]+\\.)*(?:cctv\\.com|cntv\\.cn|cntv\\.com)");
    private static final Set<String> BILIBILI = Set.of("bilibili.com", "www.bilibili.com", "m.bilibili.com");
    /** The TVer URLs the API resolves; keep in step with its tver.py. */
    private static final Pattern TVER = Pattern.compile(
            "https?://(?:www\\.)?tver\\.jp/(?:episodes?/ep[a-zA-Z0-9]+|live/(ntv|ex|tbs|tx|cx|(?:simul|special)/le[a-zA-Z0-9]+))/*(?:[?#].*)?");

    private ProviderUrls() {}

    /** Whether {@code uri} is on YouTube (youtube.com, any of its subdomains, or youtu.be). */
    public static boolean youtube(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("youtube.com") || host.endsWith(".youtube.com") || host.equals("youtu.be");
    }

    public static boolean matches(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getScheme() == null || uri.getUserInfo() != null
                || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                || uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT), path = uri.getPath();
        if (CCTV.matcher(host).matches() || youtube(uri) || TVER.matcher(uri.toString()).matches()) return true;
        if (BILIBILI.contains(host))
            return path.matches("/(?:[a-z]{2}(?:-[a-zA-Z]{2})?/)?(?:video/(?:[bB][vV][A-Za-z0-9]+|[aA][vV][0-9]+)|bangumi/play/(?:ep|ss)[0-9]+|festival/).*" );
        if (host.equals("b23.tv")) return path.matches("/[A-Za-z0-9]+/?");
        if (host.equals("live.bilibili.com")) return path.matches("/(?:blanc/)?[0-9]+/?");
        return Set.of("iwara.tv", "www.iwara.tv", "ecchi.iwara.tv").contains(host)
                && path.matches("/videos?/[A-Za-z0-9]+(?:/.*)?");
    }

    /** Localized bilibili pages identify the same video. Keep page/part parameters intact. */
    public static URI canonical(URI uri) {
        if (uri != null && uri.getHost() != null
                && BILIBILI.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            String path = uri.getRawPath().replaceFirst("^/[a-z]{2}(?:-[a-zA-Z]{2})?/(?=(?:video|bangumi)/)", "/");
            return URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + path
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        }
        return uri;
    }
}
