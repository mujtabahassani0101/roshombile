package com.haseebullahjan.roshd.core

import org.junit.Assert.*
import org.junit.Test

class ExamPeriodTest {
    private fun year(rounds:List<Int>,current:Int?=null)=json("openRounds" to rounds,"currentRound" to current)
    @Test fun severalOpenRoundsRequireAnExplicitChoice() {
        assertEquals(0,ExamPeriod.currentRound(year(listOf(1,8,12),8)))
        assertEquals(0,ExamPeriod.currentRound(year(listOf(2,12,2,0,13))))
        assertEquals(12,ExamPeriod.currentRound(year(listOf(12,12))))
        assertEquals(listOf(1,4,12),ExamPeriod.openRounds(year(listOf(12,4,1,4,0,13))))
    }
    @Test fun gradeBandRoundPlanBoundsAndPeriodVersionAreRespected() {
        assertEquals(listOf(1,6),ExamPeriod.openRounds(year(listOf(1,6,7,12)).put("roundCount",6)))
        val first=year(listOf(1)).put("id","year").put("version","a")
        assertNotEquals(ExamPeriod.key(first),ExamPeriod.key(first.copy().put("version","b")))
    }
    @Test fun noOpenRoundDoesNotReuseAnOldSelection() {
        assertEquals(0,ExamPeriod.currentRound(year(emptyList(),1)))
        assertEquals(0,ExamPeriod.currentRound(null))
    }
    @Test fun onlyUnsentReadableSheetPacketsCanBeRetried() {
        fun packet(status:String,retryAt:Long=0)=json("kind" to "scan","status" to status,"retryAt" to retryAt)
        assertTrue(ScanQueuePolicy.canSend(packet("rejected",5000),1000))
        assertTrue(ScanQueuePolicy.canSend(packet("sending"),1000))
        assertFalse(ScanQueuePolicy.canSend(packet("retry_wait",5000),1000))
        for(status in listOf("delivered","storage_error"))assertFalse(ScanQueuePolicy.canSend(packet(status),1000))
        assertFalse(ScanQueuePolicy.canSend(packet("pending").put("kind","unsupported"),1000))
    }
}
