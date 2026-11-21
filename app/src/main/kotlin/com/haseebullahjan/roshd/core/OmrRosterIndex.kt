package com.haseebullahjan.roshd.core

import org.json.JSONObject
import java.time.Instant

/** Built once per verified package set and round, rather than scanning every roster per photo. */
class OmrRosterIndex(packages:Collection<OfflineExam>,val round:Int) {
    data class Entry(val pack:OfflineExam,val row:RosterRow)
    private val included=packages.filter { it.valid && it.exam.round==round }
    private val expires=included.minOfOrNull { it.expires }
    private val rows=included.flatMap { pack -> pack.roster.map { Entry(pack,it) } }.groupBy { it.row.sheetQr }
    val current get()=expires?.isAfter(Instant.now()) ?: true
    val historicalBindings:List<JSONObject> = rows.filter { (qr,entries) ->
        entries.size==1 && !SheetQrIdentity.isPrinted(qr) && SheetQrIdentity.canBind(qr,entries.single().pack.exam.template)
    }.map { (qr,entries) -> json("qrText" to qr,"sheetType" to entries.single().pack.exam.template) }
    // A printed T1 QR still identifies the 100-capacity physical family. Only
    // the verified current roster authorizes its 75-active-question exam mode.
    val recognitionBindings:List<JSONObject> by lazy { rows.filter { (qr,entries) ->
        entries.size==1 && SheetQrIdentity.canBind(qr,entries.single().pack.exam.template) &&
            (entries.single().pack.exam.count==75 || !SheetQrIdentity.isPrinted(qr))
    }.map { (qr,entries) ->
        val exam=entries.single().pack.exam
        json("qrText" to qr,"sheetType" to exam.template).apply { if(exam.count==75)put("activeQuestionCount",75) }
    } }
    fun matches(qr:String,record:JSONObject):List<Entry> = rows[qr].orEmpty().filter {
        it.pack.valid && ScanReview.matchesFrozenSource(record,it.pack,it.row)
    }
}
