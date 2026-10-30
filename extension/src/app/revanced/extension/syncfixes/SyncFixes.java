package app.revanced.extension.syncfixes;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;

/**
 * Reddit blocked its bulk endpoints for third-party apps in Oct 2026 (403 "Blocked" on /api/info, /by_id and
 * /api/morechildren). /comments/... still works, so the broken features are rebuilt on it:
 *
 * - Galleries: galleryUrl() sends /api/info?id=t3_X to /comments/X.json and listing() hands Sync the first element
 *   of the reply, the same Listing /api/info returned.
 */
@SuppressWarnings("unused")
public final class SyncFixes {
    private static final Pattern INFO_POST =
            Pattern.compile("^https://(oauth|www)\\.reddit\\.com/api/info(?:\\.json)?/?\\?id=t3_([a-z0-9]+)$");

    private SyncFixes() {
    }

    // region Galleries

    /** Hooked into the gallery request's URL builder: /api/info?id=t3_X becomes /comments/X.json. */
    public static String galleryUrl(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = INFO_POST.matcher(url);
        if (!m.matches()) {
            return url;
        }
        return "https://" + m.group(1) + ".reddit.com/comments/" + m.group(2) + ".json?limit=1&depth=1";
    }

    /**
     * Hooked into the gallery request's parser: /comments answers [post Listing, comments Listing], /api/info
     * answered the post Listing alone. Anything else (an older cached /api/info reply) passes through.
     */
    public static String listing(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.trim();
        if (!trimmed.startsWith("[")) {
            return body;
        }
        try {
            return new JSONArray(trimmed).getJSONObject(0).toString();
        } catch (Exception e) {
            return body;
        }
    }

    // endregion

}
