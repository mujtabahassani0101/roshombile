package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import com.haseebullahjan.roshd.DocumentImagePreparation
import com.haseebullahjan.roshd.platform.OmrNative
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Local, read-only engine evaluation. Inputs are staged outside APK assets. */
class FileEngineAuditChecks(private val runner: Instrumentation) {
    fun run(): String {
        val context = runner.targetContext
        require(context.packageName.endsWith(".nativeqa"))
        val folder = File(context.filesDir, "qa/file-engine-audit")
        val input = JSONArray(File(folder, "input-manifest.json").readText())
        require(input.length() in 1..100)
        val templates = JSONArray()
        for (name in listOf("yse_omr_a5_t1_100q_v2.json", "yse_omr_a5_t3_40q_v1.json")) {
            val file = File(folder, name)
            context.assets.open("templates/$name").use { source -> file.outputStream().use { source.copyTo(it) } }
            templates.put(file.path)
        }
        val pages = JSONArray()
        for (i in 0 until input.length()) {
            val item = input.getJSONObject(i)
            val name = item.getString("file")
            require(name.matches(Regex("[0-9]+\\.(jpg|png)")))
            val source = File(folder, name)
            val path = DocumentImagePreparation.prepareForHeader(source, File(folder, "prepared-$i"), 2000)
            pages.put(JSONObject().put("pageNumber", item.getInt("pageNumber")).put("imagePath", path))
        }
        val request = JSONObject().put("templateJsonPaths", templates).put("pages", pages)
            .put("outputDirectory", File(folder, "output").path).put("includeQuestions", true)
            .put("compactResult", false).put("returnDebugImages", false)
            .put("returnNormalizedImages", false).put("maxInputDimension", 2000)
        val started = android.os.SystemClock.elapsedRealtime()
        val result = JSONObject(OmrNative.processJson(request.toString()))
        require(result.getJSONArray("sheets").length() == input.length())
        File(folder, "result.json").writeText(result.toString())
        val metadata = JSONObject().put("engineRevision", OmrNative.engineRevision())
            .put("supportedAbis", JSONArray(android.os.Build.SUPPORTED_ABIS.toList()))
            .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
            .put("sdk", android.os.Build.VERSION.SDK_INT).put("sheetCount", input.length())
        File(folder, "metadata.json").writeText(metadata.toString(2))
        return "File audit completed: ${input.length()} sheets; ${OmrNative.engineRevision()}\n"
    }
}
