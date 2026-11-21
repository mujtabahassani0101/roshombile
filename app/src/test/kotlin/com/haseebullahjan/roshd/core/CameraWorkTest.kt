package com.haseebullahjan.roshd.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CameraWorkTest {
    private fun job(id:Int,round:Int=1,period:String="year-v1",status:String="pending")=json("id" to id.toString(),"round" to round,"period" to period,"status" to status,"created" to id)
    @Test fun fifoIsBoundedToTwoPagesEvenWhen120PhotosAreWaiting() {
        val jobs=(1..120).map(::job).reversed()
        assertEquals(listOf("1","2"),CameraWorkPolicy.select(jobs,"year-v1",setOf(1),1).map { it.s("id") })
    }
    @Test fun aBatchNeverMixesCaptureRounds() {
        val jobs=listOf(job(1,2),job(2,1),job(3,2),job(4,1))
        assertEquals(listOf("1","3"),CameraWorkPolicy.select(jobs,"year-v1",setOf(1,2),1).map { it.s("id") })
    }
    @Test fun aSingleFreeWorkerSlotCannotAdmitASecondImage() {
        assertEquals(1,CameraWorkPolicy.select((1..10).map(::job),"year-v1",setOf(1),1,maximum=1).size)
        assertThrows(IllegalArgumentException::class.java) { CameraWorkPolicy.select(emptyList(),"year-v1",setOf(1),1,maximum=3) }
    }
    @Test fun changingSelectedRoundDoesNotRelabelAlreadyCapturedSheets() {
        assertEquals(1,CameraWorkPolicy.select(listOf(job(1)),"year-v1",setOf(1,2),2).single().optInt("round"))
    }
    @Test fun olderPeriodsAndUnavailablePackagesStayWaiting() {
        assertTrue(CameraWorkPolicy.select(listOf(job(1)),"year-v2",setOf(1),1).isEmpty())
        assertTrue(CameraWorkPolicy.select(listOf(job(1)),"year-v1",emptySet(),1).isEmpty())
    }
    @Test fun metadataRefreshCannotInvalidateOrReassignTheCaptureEdition() {
        val year=json("id" to "year","academicYearId" to "common","editionId" to "edition","provinceId" to "province","version" to "v1")
        assertEquals(CameraWorkPolicy.period(year),CameraWorkPolicy.period(year.copy().put("version","v2")))
        assertNotEquals(CameraWorkPolicy.period(year),CameraWorkPolicy.period(year.copy().put("editionId","different")))
    }
    @Test fun capturingProcessingAndFinishedRowsAreNotScheduledTwice() {
        val rows=listOf(job(1,status="capturing"),job(2,status="processing"),job(3,status="done"),job(4))
        assertEquals("4",CameraWorkPolicy.select(rows,"year-v1",setOf(1),1).single().s("id"))
    }
    @Test fun interruptedWorkBecomesEligibleOnlyAfterPersistentRecovery() {
        val row=job(1,status="processing")
        assertTrue(CameraWorkPolicy.select(listOf(row),"year-v1",setOf(1),1).isEmpty())
        row.put("status","pending")
        assertEquals(1,CameraWorkPolicy.select(listOf(row),"year-v1",setOf(1),1).size)
    }
    @Test fun incompleteAndOversizedCameraFilesCannotBeEnqueuedAsComplete() {
        val file=Files.createTempFile("roshd-camera-",".jpg").toFile()
        try {
            assertFalse(CameraEvidence.complete(file))
            file.writeBytes(byteArrayOf(-1,-40,0,0));assertFalse(CameraEvidence.complete(file))
            file.writeBytes(byteArrayOf(-1,-40,0,0,-1,-39));assertTrue(CameraEvidence.complete(file))
            java.io.RandomAccessFile(file,"rw").use { it.setLength(16*1024*1024L+1) }
            assertFalse(CameraEvidence.complete(file))
        } finally { file.delete() }
    }
}
