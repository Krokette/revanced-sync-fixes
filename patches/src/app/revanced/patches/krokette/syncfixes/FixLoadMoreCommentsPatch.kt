package app.revanced.patches.krokette.syncfixes

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

/** CommentsPresenterImpl: V(xa.d) / z0(ia.d) creates the morechildren request for a "View more" row. */
private const val FREE_PRESENTER = "Lya/d;"
private const val PRO_PRESENTER = "Lja/d;"

/** A comment or "more" row; t0() is its parent_id column. */
private const val FREE_ROW = "Lxa/d;"
private const val PRO_ROW = "Lia/d;"

/** OAuthCommentsMoreRequest: POSTs to api/morechildren and reads {"json":{"data":{"things":[...]}}}. */
private const val FREE_MORE_REQUEST = "Ly8/f;"
private const val PRO_MORE_REQUEST = "Lj8/f;"

private val MORE_REQUEST_PARAMETERS = listOf(
    "Landroid/content/Context;", STRING, STRING, STRING, STRING, "I", "I", STRING,
    "Lcom/android/volley/Response\$Listener;", "Lcom/android/volley/Response\$ErrorListener;",
)

private const val BASE_CONSTRUCTOR_SIGNATURE = "<init>(Landroid/content/Context;ILjava/lang/String;" +
    "Lcom/android/volley/Response\$Listener;Lcom/android/volley/Response\$ErrorListener;)V"
private const val FREE_BASE_CONSTRUCTOR = "Lx8/a;->$BASE_CONSTRUCTOR_SIGNATURE"
private const val PRO_BASE_CONSTRUCTOR = "Li8/a;->$BASE_CONSTRUCTOR_SIGNATURE"

@Suppress("unused")
val fixLoadMoreCommentsPatch = bytecodePatch(
    name = "Fix load more comments",
    description = "Fixes \"Error loading more comments\" since Reddit blocked /api/morechildren for third-party " +
        "apps (Oct 2026). Sync loads the comments from /comments instead.",
) {
    compatibleWith(
        SYNC_PACKAGE(SYNC_VERSION),
        SYNC_PRO_PACKAGE(SYNC_VERSION),
    )
    extendWith(EXTENSION)

    apply {
        val pro = classDefs[FREE_MORE_REQUEST] == null
        val presenterClass = if (pro) PRO_PRESENTER else FREE_PRESENTER
        val presenterMethod = if (pro) "z0" else "V"
        val rowClass = if (pro) PRO_ROW else FREE_ROW
        val moreRequest = if (pro) PRO_MORE_REQUEST else FREE_MORE_REQUEST
        val baseConstructor = if (pro) PRO_BASE_CONSTRUCTOR else FREE_BASE_CONSTRUCTOR

        // 1. The presenter knows the row's parent, the thread's sort and its focused comment, the request doesn't:
        //    hand them over right before it builds the request. p1 is still the row there, and v0-v2 are set again
        //    right after.
        val presenter = method(presenterClass, presenterMethod, listOf(rowClass), "V")
        val create = presenter.indexOfOnly("new $moreRequest") {
            it.opcode == Opcode.NEW_INSTANCE && it.references(moreRequest)
        }
        if ((presenter.instructionList[create] as OneRegisterInstruction).registerA != 0) {
            throw PatchException("$presenterClass->$presenterMethod: expected new-instance v0, $moreRequest")
        }
        presenter.addInstructions(
            create,
            """
                invoke-virtual { p1 }, $rowClass->t0()$STRING
                move-result-object v0
                iget-object v1, p0, $presenterClass->e:$STRING
                iget-object v2, p0, $presenterClass->d:$STRING
                invoke-static { v0, v1, v2 }, $SYNC_FIXES->moreParent($STRING$STRING$STRING)V
            """,
        )

        // 2. The request's constructor: the subtree URL instead of morechildren, sent as GET (method 0, which
        //    leaves out the POST form body).
        val constructor = method(moreRequest, "<init>", MORE_REQUEST_PARAMETERS, "V")
        val superCall = constructor.indexOfOnly("super constructor call") {
            it.opcode == Opcode.INVOKE_DIRECT_RANGE && it.references(baseConstructor)
        }
        val range = constructor.instructionList[superCall] as RegisterRangeInstruction
        // Arguments: this, context, method, url, listener, errorListener.
        val methodRegister = range.startRegister + 2
        val urlRegister = range.startRegister + 3
        val locals = constructor.implementation!!.registerCount - 11 // this + 10 parameters
        // Sync copies p8 (the suggested sort) to v8 first, as p8 is out of reach for a plain invoke.
        val sortCopy = constructor.instructionList.take(superCall).indexOfFirst {
            it.opcode == Opcode.MOVE_OBJECT_FROM16 && (it as TwoRegisterInstruction).registerB == locals + 8
        }
        if (sortCopy < 0) throw PatchException("$moreRequest: copy of the sort parameter not found")
        val sortRegister = (constructor.instructionList[sortCopy] as TwoRegisterInstruction).registerA
        if (locals + 5 > 15 || urlRegister > 15 || sortRegister > 15) {
            throw PatchException("$moreRequest: registers out of range for invoke-static")
        }
        constructor.addInstructions(
            superCall,
            """
                invoke-static { v$urlRegister, p3, p5, v$sortRegister }, $SYNC_FIXES->moreUrl($STRING$STRING$STRING$STRING)$STRING
                move-result-object v$urlRegister
                const/4 v$methodRegister, 0x0
            """,
        )
        // At its end, tie what moreUrl worked out to this request, for the parser.
        val end = constructor.indexOfOnly("return-void") { it.opcode == Opcode.RETURN_VOID }
        constructor.addInstructions(end, "invoke-static { p0 }, $SYNC_FIXES->moreRegister(Ljava/lang/Object;)V")

        // 3. parseNetworkResponse: turn the /comments reply into the morechildren shape before Sync parses it.
        //    Its first instruction copies p0 to a low register, which the hook uses for the request.
        val parse = method(
            moreRequest,
            "parseNetworkResponse",
            listOf("Lcom/android/volley/NetworkResponse;"),
            "Lcom/android/volley/Response;",
        )
        val parseLocals = parse.implementation!!.registerCount - 2
        val thisCopy = parse.instructionList.first()
        if (thisCopy.opcode != Opcode.MOVE_OBJECT_FROM16 ||
            (thisCopy as TwoRegisterInstruction).registerB != parseLocals
        ) {
            throw PatchException("$moreRequest->parseNetworkResponse: expected move-object/from16 vX, p0 first")
        }
        val thisRegister = thisCopy.registerA
        val body = parse.indexOfBodyString()
        val bodyRegister = (parse.instructionList[body] as FiveRegisterInstruction).registerC
        if (thisRegister > 15 || bodyRegister > 15) {
            throw PatchException("$moreRequest->parseNetworkResponse: registers out of range for invoke-static")
        }
        parse.addInstructions(
            body + 1,
            """
                invoke-static { v$bodyRegister, v$thisRegister }, $SYNC_FIXES->moreBody($STRING${"Ljava/lang/Object;"})$STRING
                move-result-object v$bodyRegister
            """,
        )
    }
}
