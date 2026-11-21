package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.platform.OmrNative
import com.haseebullahjan.roshd.platform.CameraDocumentReader
import com.haseebullahjan.youngscientist.scanner.*
import dev.paperframe.paperframe.PaperframeEngine
import dev.paperframe.paperframe.ScanArguments
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Runs the production adapter against the unmodified source engine, without accounts or uploads. */
class MojiScannerChecks(private val runner:Instrumentation) {
    fun run():String {
        val context=runner.targetContext
        val directory=File(context.cacheDir,"moji-check-${UUID.randomUUID()}").apply { mkdirs() }
        val report=StringBuilder();var checks=0
        fun verify(value:Boolean,label:String) { check(value) { label };checks++;report.append("PASS: $label\n") }
        try {
            DocumentScanner.initialize(context)
            PaperframeEngine(context).use { source ->
                report.append("ARM64 learned dewarping available: ${source.learnedAvailable}\n")
                for(name in listOf("real-curved","real-shadow")) {
                    val input=File(directory,"$name.jpg")
                    runner.context.assets.open("moji/$name.jpg").use { i -> input.outputStream().use { i.copyTo(it) } }
                    val hash=EvidenceArchive.hash(input)
                    val upright=File(directory,"$name-upright.jpg")
                    val imported=source.importImage(ScanArguments(mapOf("path" to input.path,"output" to upright.path)))
                    val corners=(imported["corners"] as? List<*>)?.map { (it as Number).toDouble() }
                        ?: error("Source did not detect $name")
                    verify(DocumentQuad.valid(corners),"$name: valid source corners")
                    for((limit,turns) in listOf(1400 to 0,2600 to 1,6000 to 0)) {
                        val reference=File(directory,"$name-reference-$limit.jpg")
                        val expected=source.render(ScanArguments(mapOf("path" to upright.path,"output" to reference.path,
                            "corners" to corners,"filter" to "original","turns" to turns,"maxDimension" to limit,"dewarp" to true)))
                        val actual=DocumentScanner.prepareForOmr(input,File(directory,"$name-actual-$limit"),corners,turns,limit)
                        verify(actual.rectified,"$name/$limit: production rendering applied")
                        verify(EvidenceArchive.hash(reference)==EvidenceArchive.hash(File(actual.path)),"$name/$limit: exact output bytes match Moji")
                        verify(expected["width"]==actual.metadata.getInt("width") && expected["height"]==actual.metadata.getInt("height"),"$name/$limit: dimensions and rotation match")
                        verify(expected["dewarped"]==actual.metadata.getBoolean("dewarped"),"$name/$limit: dewarping status is truthful")
                        if(source.learnedAvailable)verify(actual.metadata.getBoolean("dewarped"),"$name/$limit: real MNN flattening applied")
                    }
                    val automatic=DocumentScanner.prepareForOmr(input,File(directory,"$name-automatic"))
                    verify(automatic.rectified,"$name: automatic import reaches Moji rendering")
                    val automaticReference=File(directory,"$name-auto-reference.jpg")
                    source.render(ScanArguments(mapOf("path" to upright.path,"output" to automaticReference.path,
                        "corners" to corners,"filter" to "original","turns" to 0,"maxDimension" to 2600,"dewarp" to true)))
                    verify(EvidenceArchive.hash(automaticReference)==EvidenceArchive.hash(File(automatic.path)),"$name: automatic output exactly matches source")
                    verify(EvidenceArchive.hash(input)==hash,"$name: original bytes preserved")
                }
            }
            val templates=listOf("yse_omr_a5_t1_100q_v2.json","yse_omr_a5_t3_40q_v1.json").map { name ->
                File(directory,name).also { target -> context.assets.open("templates/$name").use { i -> target.outputStream().use { i.copyTo(it) } } }.path
            }
            for((family,count) in listOf(1 to 100,3 to 40)) {
                val input=File(directory,"T$family.jpg")
                val fixture=if(family==3)"omr/T3-anchors.jpg" else "omr/T1.jpg"
                runner.context.assets.open(fixture).use { i -> input.outputStream().use { i.copyTo(it) } }
                val hash=EvidenceArchive.hash(input)
                val prepared=DocumentScanner.prepareForOmr(input,File(directory,"T$family-prepared"),listOf(0.0,0.0,1.0,0.0,1.0,1.0,0.0,1.0))
                val result=JSONObject(OmrNative.processJson(json("templateJsonPaths" to templates,"includeQuestions" to true,
                    "maxInputDimension" to 2000,"pages" to listOf(json("pageNumber" to 1,"imagePath" to prepared.path))).toString()))
                val sheet=result.a("sheets").objects().single()
                verify(sheet.optBoolean("success"),"T$family: OMR accepts Moji output: ${sheet.s("error")}")
                val answers=OmrResultParser.observations(sheet,count)
                verify(answers.size==count && answers.all { it.s("state")=="selected" && it.optInt("selectedOption")==((it.optInt("questionNumber")-1)%4)+1 },"T$family: all $count known choices survive")
                val camera=CameraDocumentReader.read(input,json("inputSource" to "camera","captureQuestionCount" to count),
                    json("templateJsonPaths" to templates,"includeQuestions" to true,"returnNormalizedImages" to true,
                        "returnAnnotationGeometry" to true,"outputDirectory" to File(directory,"camera-T$family").path,"maxInputDimension" to 2000))
                    .a("sheets").objects().single()
                val metadata=camera.o("documentScanner")
                verify((metadata.s("engine")=="moji-paperframe-3.0.2" && !metadata.optBoolean("sourceNormalizationFirst",true)) ||
                    (metadata.s("engine")=="roshd-verified-print" && metadata.optBoolean("printedCircleGeometryVerified") && CameraNormalizationPolicy.complete(camera,count) &&
                        metadata.o("framing").s("reason")=="four_printed_squares_rectified"),
                    "T$family: camera uses complete source geometry or Moji dewarping")
                verify(camera.optBoolean("success") && OmrResultParser.observations(camera,count).all {
                    it.s("state")=="selected" && it.optInt("selectedOption")==((it.optInt("questionNumber")-1)%4)+1
                },"T$family: camera path retains all known answers")
                verify(File(camera.s("normalizedImagePath")).isFile,"T$family: selected annotation image survives retry cleanup")
                verify(EvidenceArchive.hash(input)==hash,"T$family: original OMR evidence preserved")
            }
            report.append("$checks Moji integration checks passed.\n")
            return report.toString()
        } catch(error:Throwable) { throw IllegalStateException("$report\n${error.message}",error) }
        finally { directory.deleteRecursively() }
    }
}
