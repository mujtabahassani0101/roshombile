package com.haseebullahjan.roshd.core

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class GradedSheetTest {
    @Test fun backgroundColorsCannotOverwriteEditedAnswersNewIdentityOrNewScores() {
        val snapshot=record().put("id","photo-one").put("qrText","T1:123456:ABCD1234").put("state","ready")
            .put("annotationPending",true).put("offlineScore",json("correctCount" to 0,"questionCount" to 40))
        assertTrue(GradedSheet.acceptsDerivedImage(snapshot.copy(),snapshot))
        for(key in listOf("id","qrText","studentPublicId","examId","schoolId","sourceImageSha256","state")) {
            assertFalse(key,GradedSheet.acceptsDerivedImage(snapshot.copy().put(key,"changed"),snapshot))
        }
        val edited=snapshot.copy();edited.a("answers").getJSONObject(0).put("selectedOption",2)
        assertFalse(GradedSheet.acceptsDerivedImage(edited,snapshot))
        val newScore=snapshot.copy();newScore.o("offlineScore").put("correctCount",1)
        assertFalse(GradedSheet.acceptsDerivedImage(newScore,snapshot))
        assertFalse(GradedSheet.acceptsDerivedImage(snapshot.copy().put("annotationPending",false),snapshot))
        val alreadyColored=snapshot.copy().put("annotation",GradedSheet.metadata(snapshot,snapshot.o("offlineScore"),"/existing.jpg",json()))
        assertFalse(GradedSheet.acceptsDerivedImage(alreadyColored,snapshot))
    }

    private fun record(count:Int=40)=json("sourcePath" to "/private/original.jpg","sourceImageSha256" to "original-hash",
        "exam" to json("questionCount" to count),"answers" to (1..count).map { json("questionNumber" to it,"state" to "blank","selectedOption" to null) })
    private fun sheet(count:Int=40)=json("success" to true,"normalizedImagePath" to "/private/normalized.jpg",
        "annotationGeometry" to json("width" to 1000,"height" to 2000,"questions" to (1..count).map { number ->
            json("questionNumber" to number,"options" to (1..4).map { option -> json("option" to option,"x" to 100+option*60,"y" to 100+number*15,"radius" to 8) }) }))
    private fun score(record:org.json.JSONObject,verdicts:List<String>)=json("questionCount" to verdicts.size,
        "answersSha256" to GradedSheet.answersHash(record),"questionVerdicts" to verdicts,"correctCount" to 1)
    @Test fun selectedAndMultipleMarksUseMeasuredCentersAndNeutralAnswersStayUntouched() {
        for(count in listOf(40,75,100)) {
            val record=record(count);val answers=record.a("answers")
            answers.getJSONObject(0).put("state","selected").put("selectedOption",3)
            answers.getJSONObject(1).put("state","selected").put("selectedOption",2)
            answers.getJSONObject(2).put("state","multiple").put("markedOptions",JSONArray(listOf(1,4)))
            answers.getJSONObject(3).put("state","ambiguous")
            answers.getJSONObject(4).put("state","unreadable")
            answers.getJSONObject(5).put("state","selected").put("selectedOption",1)
            val verdicts=MutableList(count) { "blank" }.apply { set(0,"correct");set(1,"incorrect");set(2,"multiple");set(3,"review");set(4,"review");set(5,"voided") }
            val original=record.toString();val request=GradedSheet.request(record,score(record,verdicts),sheet(count),"/private/colored.jpg")
            val marks=request.a("marks").objects()
            assertEquals(4,marks.size);assertEquals("correct",marks[0].s("verdict"));assertEquals(3,marks[0].optInt("option"))
            assertEquals(280.0,marks[0].optDouble("x"),0.0);assertEquals(115.0,marks[0].optDouble("y"),0.0)
            assertTrue(marks.drop(1).all { it.s("verdict")=="incorrect" })
            assertEquals(listOf(1,4),marks.drop(2).map { it.optInt("option") })
            assertEquals(original,record.toString());assertFalse(request.has("correctOptions"));assertFalse(request.has("acceptedOptions"))
        }
    }
    @Test fun allBlankSheetsHaveNoInventedColoredChoice() {
        val record=record();assertEquals(0,GradedSheet.request(record,score(record,List(40) { "blank" }),sheet(),"/private/new.jpg").a("marks").length())
    }
    @Test fun verifiedKeyDrawsGreenCorrectRedIncorrectAndYellowCorrectOptionsForBlanks() {
        val record=record();val answers=record.a("answers")
        answers.getJSONObject(0).put("state","selected").put("selectedOption",3)
        answers.getJSONObject(1).put("state","selected").put("selectedOption",2)
        answers.getJSONObject(2).put("state","multiple").put("markedOptions",JSONArray(listOf(1,4))).put("needsReview",true)
        val verdicts=List(40) { when(it) { 0->"correct";1->"incorrect";2->"multiple";else->"blank" } }
        val hints=List(40) { intArrayOf(1) };val before=record.toString()
        val request=GradedSheet.request(record,score(record,verdicts),sheet(),"/private/three-colors.jpg",hints)
        val marks=request.a("marks").objects()
        assertEquals(1,marks.count { it.s("verdict")=="correct" });assertEquals(3,marks.count { it.s("verdict")=="incorrect" })
        assertEquals(37,marks.count { it.s("verdict")=="blank_correct" });assertEquals(3,marks.first().optInt("option"))
        // Question 2 chose 2, the key is 1: green ring on 1. Question 3 already marked 1 itself.
        assertEquals(listOf(2 to 1),marks.filter { it.s("verdict")=="missed_correct" }.map { it.optInt("questionNumber") to it.optInt("option") })
        assertTrue(marks.filter { it.s("verdict")=="blank_correct" }.all { it.optInt("questionNumber")>=4 && it.optInt("option")==1 })
        assertEquals(before,record.toString());assertFalse(request.has("correctOptions"));assertFalse(request.has("acceptedOptions"))
    }
    @Test fun blankHintsSupportAcceptedAlternativesButNeverGuessAVoidedAnswer() {
        val record=record();val hints=List(40) { when(it) { 0->intArrayOf();1->intArrayOf(2,4);else->intArrayOf(1) } }
        val marks=GradedSheet.request(record,score(record,List(40) { "blank" }),sheet(),"/private/new.jpg",hints).a("marks").objects()
        assertTrue(marks.none { it.optInt("questionNumber")==1 })
        assertEquals(listOf(2,4),marks.filter { it.optInt("questionNumber")==2 }.map { it.optInt("option") })
        assertTrue(marks.all { it.s("verdict")=="blank_correct" })
        for(invalid in listOf(intArrayOf(5),intArrayOf(1,1)))assertThrows(IllegalArgumentException::class.java) {
            GradedSheet.request(record,score(record,List(40) { "blank" }),sheet(),"/private/new.jpg",List(40) { invalid })
        }
    }
    @Test fun uncertainSelectedCandidateIsNeutralUntilExplicitlyConfirmed() {
        val record=record();val answer=record.a("answers").getJSONObject(0)
        answer.put("state","selected").put("selectedOption",2).put("needsReview",true)
        val verdicts=List(40) { if(it==0)"incorrect" else "blank" }
        assertEquals(0,GradedSheet.request(record,score(record,verdicts),sheet(),"/private/new.jpg").a("marks").length())
        ScanReview.confirmAnswer(answer,2)
        assertEquals(1,GradedSheet.request(record,score(record,verdicts),sheet(),"/private/new.jpg").a("marks").length())
    }
    @Test fun staleVerdictsAndMismatchedStatesFailClosed() {
        val record=record();val score=score(record,List(40) { "blank" })
        record.a("answers").getJSONObject(0).put("state","selected").put("selectedOption",1)
        assertThrows(IllegalArgumentException::class.java) { GradedSheet.request(record,score,sheet(),"/private/new.jpg") }
        score.put("answersSha256",GradedSheet.answersHash(record))
        assertThrows(IllegalArgumentException::class.java) { GradedSheet.request(record,score,sheet(),"/private/new.jpg") }
    }
    @Test fun changedAnswersOrEvidenceInvalidatesStoredColors() {
        val record=record();val grade=score(record,List(40) { "blank" })
        record.put("annotation",GradedSheet.metadata(record,grade,"/private/new.jpg",json()))
        assertNotNull(GradedSheet.current(record));assertFalse(GradedSheet.current(record)!!.o("grade").has("questionVerdicts"))
        record.put("sourceImageSha256","different");assertNull(GradedSheet.current(record))
        record.put("sourceImageSha256","original-hash");record.a("answers").getJSONObject(0).put("state","unreadable")
        assertNull(GradedSheet.current(record));GradedSheet.invalidate(record);assertFalse(record.has("annotation"))
    }
    @Test fun aNewAnswerKeyCannotReuseThePreviousKeysColors() {
        val record=record();val grade=score(record,List(40) { "blank" }).put("releaseVersion","release-1").put("answerKeyVersion","key-1")
        record.put("annotation",GradedSheet.metadata(record,grade,"/private/new.jpg",json()))
        val key=json("releaseVersion" to "release-1","answerKeyVersion" to "key-1")
        val pack=OfflineExam(Exam(json()),json(),json("offlineGrading" to key),emptyList())
        assertTrue(GradedSheet.matchesKey(record,pack))
        key.put("answerKeyVersion","key-2");assertFalse(GradedSheet.matchesKey(record,pack))
        key.put("answerKeyVersion","key-1").put("releaseVersion","release-2");assertFalse(GradedSheet.matchesKey(record,pack))
        key.remove("releaseVersion");assertFalse(GradedSheet.matchesKey(record,pack))
    }
    @Test fun invalidGeometryAndOverwritePathsAreRejected() {
        val record=record();record.a("answers").getJSONObject(0).put("state","selected").put("selectedOption",1)
        val grade=score(record,List(40) { if(it==0)"correct" else "blank" })
        for(field in listOf("x","y","radius")) {
            val bad=sheet();bad.o("annotationGeometry").a("questions").getJSONObject(0).a("options").getJSONObject(0).put(field,-5)
            assertThrows(IllegalArgumentException::class.java) { GradedSheet.request(record,grade,bad,"/private/new.jpg") }
        }
        val duplicate=sheet();duplicate.o("annotationGeometry").a("questions").getJSONObject(0).a("options").getJSONObject(0).put("option",2)
        assertThrows(IllegalArgumentException::class.java) { GradedSheet.request(record,grade,duplicate,"/private/new.jpg") }
        for(path in listOf("/private/original.jpg","/private/normalized.jpg"))
            assertThrows(IllegalArgumentException::class.java) { GradedSheet.request(record,grade,sheet(),path) }
    }
    @Test fun wrongAnswersShowEveryAcceptedOptionInGreenButUncertainAndVoidedQuestionsDoNot() {
        val record=record();val answers=record.a("answers")
        answers.getJSONObject(0).put("state","selected").put("selectedOption",4)
        answers.getJSONObject(1).put("state","multiple").put("markedOptions",JSONArray(listOf(1,3)))
        answers.getJSONObject(2).put("state","selected").put("selectedOption",2).put("needsReview",true)
        answers.getJSONObject(3).put("state","selected").put("selectedOption",1)
        answers.getJSONObject(4).put("state","selected").put("selectedOption",3)
        val verdicts=List(40) { when(it) { 0->"incorrect";1->"multiple";2->"incorrect";3->"voided";4->"correct";else->"blank" } }
        val hints=List(40) { when(it) { 0->intArrayOf(2,3);1->intArrayOf(3,4);2->intArrayOf(4);3->intArrayOf();4->intArrayOf(3);else->intArrayOf(1) } }
        val marks=GradedSheet.request(record,score(record,verdicts),sheet(),"/private/new.jpg",hints).a("marks").objects()
        fun on(question:Int)=marks.filter { it.optInt("questionNumber")==question }.map { it.optInt("option") to it.s("verdict") }
        assertEquals(listOf(4 to "incorrect",2 to "missed_correct",3 to "missed_correct"),on(1))
        assertEquals(listOf(1 to "incorrect",3 to "incorrect",4 to "missed_correct"),on(2))
        assertTrue(on(3).isEmpty());assertTrue(on(4).isEmpty())
        assertEquals(listOf(3 to "correct"),on(5))
        assertEquals(listOf(1 to "blank_correct"),on(6))
        // Without the key nothing beyond the student's own marks is drawn.
        assertTrue(GradedSheet.request(record,score(record,verdicts),sheet(),"/private/new.jpg").a("marks").objects().none { it.s("verdict")=="missed_correct" })
    }
    @Test fun scoreFooterSitsUnderTheGridBetweenTheBottomCornerSquares() {
        // Roshd A5 sheets are normalized to 1750x2480; the last row is near y=2207, anchors at x 71-154 and 1596-1679.
        val rows=listOf(json("questionNumber" to 1,"options" to (1..4).map { json("option" to it,"x" to 300+it*60,"y" to 2207.2,"radius" to 30.7) }))
        val box=GradedSheet.footerBox(1750,2480,rows)!!
        val left=box.optInt("left");val top=box.optInt("top");val right=left+box.optInt("pixelWidth");val bottom=top+box.optInt("pixelHeight")
        assertTrue(top>=2207.2+30.7*1.5 && bottom<=2480*.975)
        assertTrue(left>154 && right<1596 && box.optInt("pixelHeight")>=120)
        // A grid reaching the bottom leaves no room: no footer rather than covering answers.
        assertNull(GradedSheet.footerBox(1750,2480,listOf(json("questionNumber" to 1,"options" to listOf(json("option" to 1,"x" to 300,"y" to 2400,"radius" to 30))))))
        val request=GradedSheet.request(record(),score(record(),List(40) { "blank" }),sheet(),"/private/new.jpg")
        val small=request.o("footerBox");assertTrue(small.optInt("top")>=100+40*15+12 && small.optInt("top")+small.optInt("pixelHeight")<=1950)
    }
    @Test fun retiredSixtyQuestionSheetCannotBeColored() {
        val record=record(60);assertThrows(IllegalArgumentException::class.java) { GradedSheet.request(record,score(record,List(60) { "blank" }),sheet(60),"/private/new.jpg") }
    }
}
