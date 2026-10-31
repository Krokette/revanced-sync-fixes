package app.revanced.extension.krokette.syncfixes;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Reddit blocked its bulk endpoints for third-party apps in Oct 2026 (403 "Blocked" on /api/info, /by_id and
 * /api/morechildren). /comments/... still works, so the broken features are rebuilt on it:
 *
 * - Galleries: galleryUrl() sends /api/info?id=t3_X to /comments/X.json and listing() hands Sync the first element
 *   of the reply, the same Listing /api/info returned.
 * - "Load more comments": moreParent()/moreUrl()/moreRegister() turn the morechildren POST into a GET of the
 *   parent's subtree, and moreBody() picks out the wanted comments in the flat
 *   {"json":{"data":{"things":[...]}}} shape, parents before their replies, as morechildren did.
 */
@SuppressWarnings("unused")
public final class SyncFixes {
    private static final Pattern INFO_POST =
            Pattern.compile("^https://(oauth|www)\\.reddit\\.com/api/info(?:\\.json)?/?\\?id=t3_([a-z0-9]+)$");
    /** The only host these requests go to: the one Sync sends its signed-in requests to. */
    private static final String BASE = "https://oauth.reddit.com/";
    /** Reddit ids are base 36. Anything else is left alone, so it can't change the request's path or query. */
    private static final Pattern ID = Pattern.compile("[a-z0-9]+");
    /** Comment sorts Reddit knows, as Sync writes them once lower-cased and without "&" ("q&a" is "qa"). */
    private static final Set<String> SORTS = new HashSet<>(Arrays.asList(
            "confidence", "best", "top", "new", "controversial", "old", "random", "qa", "live"));
    /** Deeper than any thread Reddit returns for depth=10: a reply nested further is not walked. */
    private static final int MAX_DEPTH = 50;

    /** Set by the comments presenter right before it creates the request: {parent fullname, sort, focus id}. */
    private static final ThreadLocal<String[]> PARENT = new ThreadLocal<>();
    /** Set by moreUrl() in the request's constructor, claimed by moreRegister() at its end. */
    private static final ThreadLocal<String[]> PENDING = new ThreadLocal<>();
    /** Request -> {parent fullname, comma-joined wanted ids}. Read on Volley's network thread. */
    private static final Map<Object, String[]> REQUESTS = Collections.synchronizedMap(new WeakHashMap<>());

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

    // region Load more comments

    /**
     * Hooked into the comments presenter, with the parent of the "more" row being loaded, the thread's comment sort
     * and the focused comment (set when the page shows a single thread, which Sync loads without a sort).
     */
    public static void moreParent(String parent, String sort, String focus) {
        PARENT.set(new String[]{parent, sort, focus});
    }

    /**
     * Hooked into the morechildren request's constructor, which then sends it as GET: the URL of the parent's
     * subtree instead of morechildren.
     *
     * @param original      Sync's morechildren URL, returned unchanged when an id doesn't look like a Reddit id.
     * @param link          post id without its t3_ prefix.
     * @param children      comma-joined ids the "more" row stands for.
     * @param suggestedSort the post's suggested sort, which Sync sent to morechildren.
     */
    public static String moreUrl(String original, String link, String children, String suggestedSort) {
        String[] context = PARENT.get();
        PARENT.remove();
        String parent = context == null ? null : context[0];
        String sort;
        if (context != null && context[2] != null) {
            sort = null;  // like Sync's own request for a single thread
        } else {
            sort = context != null && usable(context[1]) ? context[1] : suggestedSort;
        }

        boolean nested = parent != null && parent.startsWith("t1_");
        if (!isId(link) || (nested && !isId(parent.substring(3)))) {
            return original;
        }
        StringBuilder url = new StringBuilder(BASE).append("comments/").append(link);
        if (nested) {
            url.append("/_/").append(parent.substring(3));
        }
        url.append(".json?always_show_media=1&profile_img=true");
        // Reddit caps a fetch at 500 comments. At the top level, spend most of them on more top-level comments:
        // replies below the cut come back as "Continue thread" rows, which open the thread on its own page.
        url.append("&limit=500&depth=").append(nested ? 10 : 3);
        if (usable(sort)) {
            String name = sort.toLowerCase(Locale.ENGLISH).replace("&", "");
            if (SORTS.contains(name)) {
                url.append("&sort=").append(name);
            }
        }

        if (parent == null) {
            parent = "t3_" + link;
        }
        PENDING.set(new String[]{parent, children == null ? "" : children});
        return url.toString();
    }

    /** Hooked into the end of the morechildren request's constructor. */
    public static void moreRegister(Object request) {
        String[] pending = PENDING.get();
        PENDING.remove();
        if (request != null && pending != null) {
            REQUESTS.put(request, pending);
        }
    }

    /** Hooked into the morechildren request's parser with the response body and the request itself. */
    public static String moreBody(String body, Object request) {
        String[] more = request == null ? null : REQUESTS.get(request);
        if (more == null || body == null || !body.trim().startsWith("[")) {
            return body;
        }
        try {
            return more(body, more[0], more[1]);
        } catch (JSONException e) {
            return body;
        }
    }

    static String more(String text, String parent, String want) throws JSONException {
        Set<String> wanted = new HashSet<>(Arrays.asList(want.split(",")));
        Set<String> shown = new HashSet<>();
        shown.add(parent);
        JSONArray things = new JSONArray();
        JSONArray top = new JSONArray(text.trim()).getJSONObject(1).getJSONObject("data").getJSONArray("children");
        walk(top, parent, wanted, shown, things, 0);
        // With nothing new (the rest lies beyond Reddit's 500-comment window of a huge thread), this is an empty
        // list: Sync removes the row. An error would keep it, and Sync reloads top-level rows by itself every time
        // the list is redrawn, which an error does: it would retry the same request forever.
        return new JSONObject().put("json", new JSONObject()
                .put("errors", new JSONArray())
                .put("data", new JSONObject().put("things", things))).toString();
    }

    /**
     * Pre-order walk, like morechildren: a parent always comes before its replies, because Sync works out a
     * comment's level from a parent it has already read. Every reply is flattened out (replies set to ""), so each
     * thing makes one row. Returns the number of comments added.
     */
    private static int walk(JSONArray children, String parent, Set<String> wanted, Set<String> shown,
                            JSONArray things, int depth) throws JSONException {
        int added = 0;
        if (depth > MAX_DEPTH) {
            return 0;
        }
        for (int i = 0; i < children.length(); i++) {
            JSONObject child = children.optJSONObject(i);
            if (child == null) {
                continue;
            }
            JSONObject data = child.optJSONObject("data");
            if (data == null) {
                continue;
            }
            String parentId = data.optString("parent_id");
            String kind = child.optString("kind");
            if ("t1".equals(kind)) {
                JSONObject replies = data.optJSONObject("replies");
                // Like morechildren: the asked-for comments plus everything under them.
                boolean underAdded = shown.contains(parentId) && !parentId.equals(parent);
                if (underAdded || (wanted.contains(data.optString("id")) && shown.contains(parentId))) {
                    data.put("replies", "");
                    things.put(child);
                    shown.add(data.optString("name"));
                    added++;
                }
                if (replies != null && replies.optJSONObject("data") != null) {
                    JSONArray sub = replies.optJSONObject("data").optJSONArray("children");
                    if (sub != null) {
                        added += walk(sub, parent, wanted, shown, things, depth + 1);
                    }
                }
            } else if ("more".equals(kind) && shown.contains(parentId) && !parentId.equals(parent)) {
                // A deeper "load more" or "Continue thread" under a comment we added: keep it. One directly under
                // the parent holds what lies beyond this window, and loading it would fetch this same window again.
                things.put(child);
            }
        }
        return added;
    }

    private static boolean isId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    private static boolean usable(String sort) {
        return sort != null && !sort.isEmpty() && !"suggested".equalsIgnoreCase(sort);
    }

    // endregion
}
