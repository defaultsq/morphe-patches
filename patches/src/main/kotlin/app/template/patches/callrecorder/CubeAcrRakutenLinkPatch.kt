package app.template.patches.callrecorder

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.template.patches.shared.Constants.CALLRECORDER_COMPATIBILITY
import app.template.patches.shared.Constants.CALLRECORDER_HELPER_COMPATIBILITY
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.w3c.dom.Element

private val bbmRecordingFingerprint = Fingerprint(
    definingClass = "Lcom/catalinagroup/callrecorder/service/recordings/BBMRecording;",
    name = "getPackageName",
    parameters = emptyList(),
    returnType = "Ljava/lang/String;"
)

private val rakutenLinkPackageVisibilityPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { document ->
            val packages = document.getElementsByTagName("package")
            var replaced = false

            for (index in 0 until packages.length) {
                val element = packages.item(index) as? Element ?: continue
                if (element.getAttribute("android:name") == "com.bbm") {
                    element.setAttribute("android:name", "jp.co.rakuten.mobile.rcs")
                    replaced = true
                }
            }

            check(replaced) { "Cube's com.bbm package visibility entry was not found" }
        }
    }
}

/**
 * Reassign the retired BBM VoIP handler to Rakuten Link in both Cube ACR and
 * Cube's separately installed App Connector accessibility service.
 *
 * Cube's ActivityCallRecording base class supplies the same shared VoIP audio
 * source/profile used by LINE, so this patch changes detection only. It neither
 * changes recording entitlement checks nor any billing code.
 */
@Suppress("unused")
val cubeAcrRakutenLinkVoipPatch = bytecodePatch(
    name = "Add Rakuten Link VoIP support",
    description = "Uses Cube ACR's BBM handler to detect Rakuten Link call screens in Cube and App Connector.",
    default = false
) {
    compatibleWith(CALLRECORDER_COMPATIBILITY, CALLRECORDER_HELPER_COMPATIBILITY)
    dependsOn(rakutenLinkPackageVisibilityPatch)

    execute {
        val bbmClass = mutableClassDefBy(bbmRecordingFingerprint.definingClass!!)
        val replacements = mapOf(
            "com.bbm" to "jp.co.rakuten.mobile.rcs",
            "com.bbm/.ui.voice.activities.InCallActivity" to
                "jp.co.rakuten.mobile.rcs/.call.activecall.view.CallActivity",
            "com.bbm/.ui.voice.activities.IncomingCallActivity" to
                "jp.co.rakuten.mobile.rcs/.call.activecall.view.CallActivity",
            "com.bbm/.ui.voice.activities.InCallActivityNew" to
                "jp.co.rakuten.mobile.rcs/.call.activecall.view.CallActivity",
            "com.bbm/.ui.voice.activities.IncomingCallActivityNew" to
                "jp.co.rakuten.mobile.rcs/.call.activecall.view.CallActivity",
            "com.bbm:id/voice_mode_call_title" to
                "jp.co.rakuten.mobile.rcs:id/tv_call_name",
            "com.bbm:id/incomingCallDisplayName" to
                "jp.co.rakuten.mobile.rcs:id/tv_call_name",
            "com.bbm:id/new_call_voice_display_name" to
                "jp.co.rakuten.mobile.rcs:id/tv_call_name",
            "com.bbm:id/new_incoming_voice_display_name" to
                "jp.co.rakuten.mobile.rcs:id/tv_call_name"
        )

        bbmClass.methods.forEach { method ->
            method.instructions.withIndex().forEach instructionLoop@{ (index, instruction) ->
                val source = ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
                    ?: return@instructionLoop
                val replacement = replacements[source] ?: return@instructionLoop
                val register = (instruction as? OneRegisterInstruction)?.registerA
                    ?: error("Expected a one-register const-string for $source")
                method.replaceInstruction(index, "const-string v$register, \"$replacement\"")
            }
        }

    }
}
