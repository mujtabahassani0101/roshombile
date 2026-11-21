package com.haseebullahjan.roshd.qa

import android.app.Instrumentation
import android.graphics.*
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.platform.OmrNative
import org.json.JSONObject
import java.io.File

/** PDF-derived fixtures use the supplied millimetre map, not engine coordinates. */
class NewFortySheetChecks(private val runner:Instrumentation) {
    fun run():String {
        val context=runner.targetContext
        require(context.packageName.endsWith(".nativeqa"))
        val folder=File(context.cacheDir,"new-forty-sheet").apply { mkdirs() }
        val report=StringBuilder();var checks=0
        fun verify(ok:Boolean,label:String) { check(ok) { label };checks++;report.append("PASS: $label\n") }
        try {
            val templates=SheetFamilies.templates.map { name -> File(folder,name).also { f ->
                context.assets.open("templates/$name").use { input -> f.outputStream().use { input.copyTo(it) } }
            }.path }
            fun read(file:File,paths:List<String> = templates):JSONObject {
                val response=JSONObject(OmrNative.processJson(json("templateJsonPaths" to paths,"includeQuestions" to true,
                    "returnAnnotationGeometry" to true,"returnNormalizedImages" to false,"maxInputDimension" to 2000,
                    "pages" to listOf(json("pageNumber" to 1,"imagePath" to file.path))).toString()))
                return response.a("sheets").objects().single().also {
                    File(folder,"${file.nameWithoutExtension}-result.json").writeText(it.toString(2))
                }
            }
            val source=runner.context.assets.open("omr/T3-anchors.jpg").use { BitmapFactory.decodeStream(it) }
            try {
                for(angle in listOf(0,90,180,270)) {
                    val bitmap=if(angle==0)source else Bitmap.createBitmap(source,0,0,source.width,source.height,Matrix().apply { postRotate(angle.toFloat()) },true)
                    val file=File(folder,"rotation-$angle.jpg")
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,97,it) }
                    if(bitmap!==source)bitmap.recycle()
                    val sheet=read(file)
                    verify(sheet.optBoolean("success") && sheet.o("sheetType").s("code")=="T3" && OmrResultParser.qr(sheet)=="T3:213327:rZeGOHtv",
                        "New 40-question PDF: measured geometry and exact QR at $angle degrees")
                    val answers=OmrResultParser.observations(sheet,40)
                    verify(answers.size==40 && answers.all { it.s("state")=="selected" && it.optInt("selectedOption")==((it.optInt("questionNumber")-1)%4)+1 },
                        "All 40 independent known choices are correct at $angle degrees")
                    verify(sheet.o("annotationGeometry").a("questions").length()==40,"All 40 color-mark positions survive $angle-degree normalization")
                }
            } finally { source.recycle() }
            for(name in listOf("perspective","blank","wrong-qr","no-qr")) {
                val file=File(folder,"$name.jpg").also { f -> runner.context.assets.open("omr/T3-anchors-$name.jpg").use { input -> f.outputStream().use { input.copyTo(it) } } }
                val sheet=read(file)
                when(name) {
                    "perspective" -> {
                        val answers=OmrResultParser.observations(sheet,40)
                        verify(sheet.optBoolean("success") && answers.size==40 && answers.all { it.s("state")=="selected" && it.optInt("selectedOption")==((it.optInt("questionNumber")-1)%4)+1 },
                            "Perspective-distorted supplied PDF returns all 40 exact choices")
                    }
                    "blank" -> {
                        val answers=OmrResultParser.observations(sheet,40)
                        verify(sheet.optBoolean("success") && answers.size==40 && answers.all { it.s("state")=="blank" },
                            "Blank print has zero false answers across all 40 bubbles")
                    }
                    else -> verify(!sheet.optBoolean("success") && sheet.a("questions").length()==0,"$name cannot produce answers or a grade")
                }
            }
            val wrongSelection=read(File(folder,"rotation-0.jpg"),listOf(templates.first()))
            verify(!wrongSelection.optBoolean("success") && wrongSelection.a("questions").length()==0,"100-question selection cannot grade a 40-question sheet")
            report.append(DocumentScannerChecks(runner).run())
            return "$report$checks new 40-question sheet checks passed.\n"
        } catch(error:Throwable) { throw IllegalStateException("$report\n${error.message}",error) }

    }
}
