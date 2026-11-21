package com.haseebullahjan.roshd.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UploadSheetTest {
    private fun fixture(work:(File,JSONObject)->Unit) {
        val folder=Files.createTempDirectory("graded-upload").toFile()
        try {
            val raw=File(folder,"camera.jpg").apply { writeBytes(byteArrayOf(-1,-40,-1,1,2,3)) }
            val graded=File(folder,"graded.jpg").apply { writeBytes(byteArrayOf(-1,-40,-1,4,5,6)) }
            val record=json("id" to "record","state" to "ready","sourcePath" to raw.path,"sourceImageSha256" to EvidenceArchive.hash(raw),
                "answers" to listOf(json("questionNumber" to 1,"state" to "blank","selectedOption" to null)),
                "engine" to json("annotationGeometry" to json("width" to 1480,"height" to 2100)))
            record.put("annotation",json("state" to "ready","path" to graded.path,"sha256" to EvidenceArchive.hash(graded),"sourceImageSha256" to record.s("sourceImageSha256"),
                "answersSha256" to GradedSheet.answersHash(record),"colorPolicyVersion" to GradedSheet.COLOR_POLICY_VERSION,"width" to 1480,"height" to 2100))
            work(folder,record)
        } finally { folder.deleteRecursively() }
    }
    @Test fun aNewGradedCapturePreservesThePreviousImmutableCaptureAndTheCameraOriginal()=fixture { _,record ->
        val raw=File(record.s("sourcePath"));val original=raw.readBytes();val source=json("sourceImageSha256" to record.s("sourceImageSha256"),"qrText" to "exact-QR")
        record.put("scanId","previous-capture").put("sourceSnapshot",source)
        val beforeSource=source.toString();val answers=record.a("answers").toString()
        val evidence=UploadSheet.bind(record)
        assertFalse(record.has("scanId"));assertFalse(record.has("sourceSnapshot"))
        assertEquals("previous-capture",record.a("previousImageCaptures").getJSONObject(0).s("scanId"))
        assertEquals(beforeSource,record.a("previousImageCaptures").getJSONObject(0).o("sourceSnapshot").toString())
        assertEquals(beforeSource,source.toString());assertArrayEquals(original,raw.readBytes());assertEquals(answers,record.a("answers").toString())
        assertNotEquals(record.s("sourceImageSha256"),evidence.s("sha256"));assertEquals(UploadSheet.VARIANT,evidence.s("variant"))
        assertEquals(record.o("annotation").s("path"),UploadSheet.sourcePath(record,json("sourceImageSha256" to evidence.s("sha256"))))
        val frozen=record.toString();UploadSheet.bind(record);assertEquals(frozen,record.toString())
    }
    @Test fun retriesKeepTheirCaptureAndArtifactUntilTheRenderedBytesChange()=fixture { folder,record ->
        val evidence=UploadSheet.bind(record)
        record.put("scanId","graded-capture").put("sourceSnapshot",json("sourceImageSha256" to evidence.s("sha256")))
        val before=record.toString();UploadSheet.bind(record);assertEquals(before,record.toString())
        val changed=File(folder,"new-grade.jpg").apply { writeBytes(byteArrayOf(-1,-40,-1,7,8,9)) }
        record.o("annotation").put("path",changed.path).put("sha256",EvidenceArchive.hash(changed))
        val next=UploadSheet.bind(record)
        assertFalse(record.has("scanId"));assertEquals("graded-capture",record.a("previousImageCaptures").getJSONObject(0).s("scanId"))
        assertEquals(evidence.s("path"),record.a("previousImageCaptures").getJSONObject(0).o("uploadEvidence").s("path"))
        assertEquals(changed.path,next.s("path"));assertTrue(File(evidence.s("path")).isFile)
    }
    @Test fun aSignedRequestCannotBeReboundOrHaveItsImageSilentlyReplaced()=fixture { _,record ->
        val evidence=UploadSheet.bind(record);record.put("sealed",true)
        val source=json("sourceImageSha256" to evidence.s("sha256"));val before=record.toString()
        assertThrows(IllegalArgumentException::class.java) { UploadSheet.bind(record) };assertEquals(before,record.toString())
        assertEquals(evidence.s("path"),UploadSheet.sourcePath(record,source))
        assertEquals(evidence.s("path"),UploadSheet.transferPath(record,source))
        record.remove("uploadEvidence")
        assertEquals(record.s("sourcePath"),UploadSheet.sourcePath(record,json("sourceImageSha256" to record.s("sourceImageSha256"))))
        assertThrows(IllegalArgumentException::class.java) { UploadSheet.transferPath(record,json("sourceImageSha256" to record.s("sourceImageSha256"))) }
    }
    @Test fun corruptStaleOrUnmeasuredArtifactsAreNeverSignedAsNewEvidence()=fixture { _,record ->
        val annotation=record.o("annotation");val original=annotation.toString()
        annotation.put("sha256","wrong")
        assertThrows(IllegalArgumentException::class.java) { UploadSheet.bind(record) };assertFalse(record.has("uploadEvidence"))
        record.put("annotation",JSONObject(original));record.o("annotation").put("height",100)
        assertThrows(IllegalArgumentException::class.java) { UploadSheet.bind(record) }
        record.put("annotation",JSONObject(original));record.a("answers").getJSONObject(0).put("state","selected").put("selectedOption",2)
        assertThrows(IllegalStateException::class.java) { UploadSheet.bind(record) }
        record.remove("sealed")
        assertThrows(IllegalArgumentException::class.java) { UploadSheet.sourcePath(record,json("sourceImageSha256" to record.s("sourceImageSha256"))) }
    }
}
