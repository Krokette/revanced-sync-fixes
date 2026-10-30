import app.revanced.extension.syncfixes.SyncFixes;

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


        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("all passed");
    }
}
