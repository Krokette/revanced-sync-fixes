import app.revanced.extension.krokette.syncfixes.SyncFixes;

import org.json.JSONObject;

/** Plain JVM checks of the extension's pure logic (no Android needed). Run: java -cp ... SyncFixesTest */
public final class SyncFixesTest {
    private static int failures;

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    private static String t1(String id, String parent, String replies) {
        String r = replies.isEmpty() ? "\"\"" : "{\"kind\":\"Listing\",\"data\":{\"children\":[" + replies + "]}}";
        return "{\"kind\":\"t1\",\"data\":{\"id\":\"" + id + "\",\"name\":\"t1_" + id + "\",\"parent_id\":\"" + parent
                + "\",\"link_id\":\"t3_post1\",\"subreddit\":\"test\",\"body\":\"x\",\"replies\":" + r + "}}";
    }

    private static String more(String id, String parent, String children) {
        StringBuilder c = new StringBuilder();
        for (String k : children.split(",")) c.append(c.length() > 0 ? "," : "").append("\"").append(k).append("\"");
        return "{\"kind\":\"more\",\"data\":{\"id\":\"" + id + "\",\"name\":\"t1_" + id + "\",\"parent_id\":\"" + parent
                + "\",\"count\":2,\"children\":[" + c + "]}}";
    }

    public static void main(String[] args) throws Exception {
        // Gallery URL rewrite.
        check("gallery url rewritten",
                SyncFixes.galleryUrl("https://oauth.reddit.com/api/info.json?id=t3_1abc2d")
                        .equals("https://oauth.reddit.com/comments/1abc2d.json?limit=1&depth=1"));
        check("other urls untouched",
                SyncFixes.galleryUrl("https://oauth.reddit.com/api/info/?id=t1_xyz").equals(
                        "https://oauth.reddit.com/api/info/?id=t1_xyz"));
        check("null url", SyncFixes.galleryUrl(null) == null);

        // Gallery body unwrap: /comments answers [posts, comments].
        String post = "{\"kind\":\"Listing\",\"data\":{\"children\":[{\"kind\":\"t3\",\"data\":{\"id\":\"1abc2d\","
                + "\"gallery_data\":{\"items\":[{\"media_id\":\"m1\"}]},\"media_metadata\":{\"m1\":{\"s\":{\"u\":\"x\"}}}}}]}}";
        String comments = "{\"kind\":\"Listing\",\"data\":{\"children\":[]}}";
        String unwrapped = SyncFixes.listing("[" + post + "," + comments + "]");
        JSONObject data = new JSONObject(unwrapped).getJSONObject("data").getJSONArray("children")
                .getJSONObject(0).getJSONObject("data");
        check("listing unwrapped to the post", data.getString("id").equals("1abc2d")
                && data.getJSONObject("gallery_data").getJSONArray("items").length() == 1);
        check("object body untouched", SyncFixes.listing(post).equals(post));
        check("broken body untouched", SyncFixes.listing("[oops").equals("[oops"));


        // Load more: nested parent (t1_p), wanted = c1,c2 (c0 already shown).
        SyncFixes.moreParent("t1_p", "top", null);
        String url = SyncFixes.moreUrl("https://oauth.reddit.com/api/morechildren?always_show_media=1&profile_img=true",
                "post1", "c1,c2", "");
        check("nested more url", url.equals("https://oauth.reddit.com/comments/post1/_/p.json?always_show_media=1"
                + "&profile_img=true&limit=500&depth=10&sort=top"));
        Object request = new Object();
        SyncFixes.moreRegister(request);
        String nested = "[" + post + ",{\"kind\":\"Listing\",\"data\":{\"children\":[" + t1("p", "t1_root",
                t1("c0", "t1_p", "") + "," + t1("c1", "t1_p", t1("g1", "t1_c1", t1("gg1", "t1_g1", "")) + ","
                        + more("m1", "t1_c1", "g2,g3")) + "," + t1("c2", "t1_p", "") + "," + more("m0", "t1_p", "c9"))
                + "]}}]";
        JSONObject out = new JSONObject(SyncFixes.moreBody(nested, request));
        org.json.JSONArray things = out.getJSONObject("json").getJSONObject("data").getJSONArray("things");
        StringBuilder order = new StringBuilder();
        boolean flat = true;
        for (int i = 0; i < things.length(); i++) {
            JSONObject d = things.getJSONObject(i).getJSONObject("data");
            order.append(d.getString("id")).append(" ");
            if (things.getJSONObject(i).getString("kind").equals("t1") && !"".equals(d.opt("replies"))) flat = false;
        }
        check("nested things pre-order, wanted only: " + order, order.toString().equals("c1 g1 gg1 m1 c2 "));
        check("nested replies flattened", flat);
        check("unregistered request untouched", SyncFixes.moreBody(nested, new Object()).equals(nested));

        // Top level: parent unknown falls back to the post, depth 2, suggested sort ignored.
        url = SyncFixes.moreUrl("https://oauth.reddit.com/api/morechildren?x", "post1", "a2", "suggested");
        check("top-level more url", url.equals("https://oauth.reddit.com/comments/post1.json?always_show_media=1"
                + "&profile_img=true&limit=500&depth=3"));
        Object top = new Object();
        SyncFixes.moreRegister(top);
        String topBody = "[" + post + ",{\"kind\":\"Listing\",\"data\":{\"children\":[" + t1("a1", "t3_post1", "")
                + "," + t1("a2", "t3_post1", t1("b2", "t1_a2", "")) + "," + more("mt", "t3_post1", "a3,a4") + "]}}]";
        things = new JSONObject(SyncFixes.moreBody(topBody, top)).getJSONObject("json").getJSONObject("data")
                .getJSONArray("things");
        check("top-level picks a2 and its reply only", things.length() == 2
                && things.getJSONObject(0).getJSONObject("data").getString("id").equals("a2")
                && things.getJSONObject(1).getJSONObject("data").getString("id").equals("b2"));

        // Nothing new: an empty list, so Sync drops the row instead of retrying it forever.
        SyncFixes.moreParent("t3_post1", null, null);
        SyncFixes.moreUrl("https://oauth.reddit.com/api/morechildren", "post1", "zz", null);
        Object none = new Object();
        SyncFixes.moreRegister(none);
        check("nothing new gives an empty list", new JSONObject(SyncFixes.moreBody(topBody, none))
                .getJSONObject("json").getJSONObject("data").getJSONArray("things").length() == 0);

        // A focused thread: no sort, like Sync's own request for it.
        SyncFixes.moreParent("t1_p", "top", "focus1");
        check("focused thread sends no sort", !SyncFixes.moreUrl("https://oauth.reddit.com/api/morechildren",
                "post1", "c1", "new").contains("sort="));
        SyncFixes.moreRegister(new Object());

        // Ids and sorts that don't look like Reddit's can't reach the URL.
        String original = "https://oauth.reddit.com/api/morechildren?always_show_media=1&profile_img=true";
        SyncFixes.moreParent("t1_a/../../api/v1/me?", null, null);
        check("odd parent id leaves the request alone",
                SyncFixes.moreUrl(original, "post1", "c1", null).equals(original));
        SyncFixes.moreRegister(new Object());
        SyncFixes.moreParent("t3_post1", null, null);
        check("odd post id leaves the request alone",
                SyncFixes.moreUrl(original, "x@evil.com", "c1", null).equals(original));
        SyncFixes.moreRegister(new Object());
        SyncFixes.moreParent("t3_post1", "new#x&y=1", null);
        check("unknown sort dropped",
                !SyncFixes.moreUrl(original, "post1", "c1", null).contains("sort="));
        SyncFixes.moreRegister(new Object());
        SyncFixes.moreParent("t3_post1", "Q&A", null);
        check("q&a sent as qa", SyncFixes.moreUrl(original, "post1", "c1", null).endsWith("&sort=qa"));
        SyncFixes.moreRegister(new Object());

        // A reply nested far deeper than Reddit sends is cut off instead of overflowing the stack.
        StringBuilder deep = new StringBuilder(t1("z", "t1_d199", ""));
        for (int i = 199; i >= 0; i--) {
            deep = new StringBuilder(t1("d" + i, i == 0 ? "t3_post1" : "t1_d" + (i - 1), deep.toString()));
        }
        SyncFixes.moreParent("t3_post1", null, null);
        SyncFixes.moreUrl(original, "post1", "d0", null);
        Object deepRequest = new Object();
        SyncFixes.moreRegister(deepRequest);
        String deepOut = SyncFixes.moreBody("[" + post + ",{\"kind\":\"Listing\",\"data\":{\"children\":["
                + deep + "]}}]", deepRequest);
        check("very deep reply cut at 50 levels", new JSONObject(deepOut).getJSONObject("json").getJSONObject("data")
                .getJSONArray("things").length() == 51);

        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("all passed");
    }
}
