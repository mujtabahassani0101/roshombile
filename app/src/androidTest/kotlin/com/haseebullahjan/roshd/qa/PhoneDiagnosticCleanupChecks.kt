package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import com.haseebullahjan.roshd.core.*
import org.json.JSONArray
import java.io.File

/** Only disposable UUID-named QA directories; never application files or captured evidence. */
class PhoneDiagnosticCleanupChecks(private val runner:Instrumentation) {
    fun run():org.json.JSONObject {
        val cache=runner.targetContext.cacheDir
        val entries=JSONArray();var removed=0L
        val disposable=Regex("(?:phone-performance|omr-speed|phone-accuracy|moji-port|live-edges)-[0-9a-f-]{36}")
        for(f in cache.listFiles().orEmpty()) {
            val bytes=if(f.isDirectory)f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()
            val delete=f.isDirectory && disposable.matches(f.name) && f.canonicalFile.parentFile==cache.canonicalFile
            if(delete) { check(f.deleteRecursively());removed+=bytes }
            entries.put(json("kind" to if(delete)"qa-temporary-removed" else "retained","bytes" to bytes))
        }
        return json("removedQaBytes" to removed,"cacheEntries" to entries)
    }
}
