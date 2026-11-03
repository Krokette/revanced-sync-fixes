package app.revanced.patches.krokette.syncfixes

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/** OAuthGalleryRequest: b/c(String) builds the /api/info URL, parseNetworkResponse reads the Listing. */
private const val FREE_GALLERY_REQUEST = "Ly8/q;"
private const val PRO_GALLERY_REQUEST = "Lj8/q;"

/** The gallery viewer, which remembers the page you were on in the request's cache entry. */
private const val GALLERY_ACTIVITY = "Lcom/laurencedawson/reddit_sync/ui/activities/media/MultiImageActivity;"

@Suppress("unused")
val fixGalleriesPatch = bytecodePatch(
    name = "Fix Reddit galleries",
    description = "Fixes \"Could not load gallery\" since Reddit blocked /api/info for third-party apps (Oct 2026). " +
        "Sync loads the gallery post from /comments instead.",
) {
    compatibleWith(
        SYNC_PACKAGE(SYNC_VERSION),
        SYNC_PRO_PACKAGE(SYNC_VERSION),
    )
    extendWith(EXTENSION)

    apply {
        val pro = classDefs[FREE_GALLERY_REQUEST] == null
        val galleryRequest = if (pro) PRO_GALLERY_REQUEST else FREE_GALLERY_REQUEST
        val urlMethod = if (pro) "c" else "b"

        // b/c(String): pass the URL it returns through SyncFixes.galleryUrl.
        val url = method(galleryRequest, urlMethod, listOf(STRING), STRING)
        val ret = url.indexOfOnly("return-object") { it.opcode == Opcode.RETURN_OBJECT }
        val urlRegister = (url.instructionList[ret] as OneRegisterInstruction).registerA
        url.addInstructions(
            ret,
            """
                invoke-static { v$urlRegister }, $SYNC_FIXES->galleryUrl($STRING)$STRING
                move-result-object v$urlRegister
            """,
        )

        // parseNetworkResponse: /comments answers [post Listing, comments Listing]; hand Sync the post Listing,
        // which is what /api/info returned.
        val parse = method(
            galleryRequest,
            "parseNetworkResponse",
            listOf("Lcom/android/volley/NetworkResponse;"),
            "Lcom/android/volley/Response;",
        )
        val body = parse.indexOfBodyString()
        val bodyRegister = (parse.instructionList[body] as FiveRegisterInstruction).registerC
        parse.addInstructions(
            body + 1,
            """
                invoke-static { v$bodyRegister }, $SYNC_FIXES->listing($STRING)$STRING
                move-result-object v$bodyRegister
            """,
        )

        // onDestroy saves the page you were on into the cached reply, which it reads as a JSON object: convert the
        // cached /comments array the same way, so it can (and the reopened gallery starts on that page again).
        val destroy = method(GALLERY_ACTIVITY, "onDestroy", emptyList(), "V")
        // The cache key is built twice there: for the read (the first), then for the write back.
        val cacheKey = destroy.instructionList.indexOfFirst {
            it.opcode == Opcode.INVOKE_STATIC && it.references("$galleryRequest->$urlMethod($STRING)$STRING")
        }
        if (cacheKey < 0) throw PatchException("$GALLERY_ACTIVITY->onDestroy: gallery cache key not found")
        val cached = destroy.instructionList.withIndex().firstOrNull { (index, instruction) ->
            index > cacheKey && instruction.opcode == Opcode.INVOKE_DIRECT &&
                instruction.references("Ljava/lang/String;-><init>([B)V")
        }?.index ?: throw PatchException("$GALLERY_ACTIVITY->onDestroy: cached gallery reply not found")
        val cachedRegister = (destroy.instructionList[cached] as FiveRegisterInstruction).registerC
        destroy.addInstructions(
            cached + 1,
            """
                invoke-static { v$cachedRegister }, $SYNC_FIXES->listing($STRING)$STRING
                move-result-object v$cachedRegister
            """,
        )
    }
}
