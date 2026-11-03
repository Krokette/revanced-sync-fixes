package app.revanced.patches.krokette.syncfixes

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/** Sync for Reddit (free and Pro). The patches use their obfuscated names, so they only apply to this version. */
internal const val SYNC_PACKAGE = "com.laurencedawson.reddit_sync"
internal const val SYNC_PRO_PACKAGE = "com.laurencedawson.reddit_sync.pro"
internal const val SYNC_VERSION = "v23.06.30-13:39"

// Names no other bundle uses. Manager loads every bundle together, and the patcher keeps only the first class or
// extension file of a given name: another Sync fix that also called its extension syncfixes/SyncFixes broke
// galleries in whichever bundle lost (NoSuchMethodError).
internal const val EXTENSION = "extensions/krokette-syncfixes.rve"
internal const val SYNC_FIXES = "Lapp/revanced/extension/krokette/syncfixes/SyncFixes;"

internal const val STRING = "Ljava/lang/String;"

/** A method by its class and exact signature; throws if it isn't there. */
internal fun BytecodePatchContext.method(
    type: String,
    name: String,
    parameters: List<String>,
    returnType: String,
): MutableMethod {
    val classDef = classDefs[type] ?: throw PatchException("$type not found")
    return classDefs.getOrReplaceMutable(classDef).methods.firstOrNull { method ->
        method.name == name &&
            method.returnType == returnType &&
            method.parameterTypes.map { it.toString() } == parameters
    } ?: throw PatchException("$type->$name(${parameters.joinToString("")})$returnType not found")
}

internal val MutableMethod.instructionList: List<Instruction>
    get() = implementation?.instructions?.toList() ?: throw PatchException("$name has no code")

internal fun Instruction.references(reference: String) =
    this is ReferenceInstruction && this.reference.toString() == reference

/** Index of the single instruction matching [predicate]; throws when there are none or several. */
internal fun MutableMethod.indexOfOnly(what: String, predicate: (Instruction) -> Boolean): Int {
    val matches = instructionList.withIndex().filter { predicate(it.value) }.map { it.index }
    return matches.singleOrNull()
        ?: throw PatchException("$definingClass->$name: expected one $what, found ${matches.size}")
}

/** The `new String(networkResponse.data)` in a request's parseNetworkResponse: index of the constructor call. */
internal fun MutableMethod.indexOfBodyString() = indexOfOnly("new String(byte[])") {
    it.opcode == Opcode.INVOKE_DIRECT && it.references("Ljava/lang/String;-><init>([B)V")
}
