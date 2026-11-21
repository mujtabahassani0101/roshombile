package com.haseebullahjan.roshd.core

import com.haseebullahjan.roshd.messenger.ConnectionsManager
import org.json.JSONObject
import java.io.IOException

data class DeliveryFailure(val message:String,val retry:Boolean,val retryAt:Long,val stop:Boolean) {
    companion object {
        fun from(error:Exception,now:Long):DeliveryFailure {
            val api=error as? ApiFailure
            val retry=error is IOException || (api!=null && (api.status in setOf(0,401,408,429) || api.status>=500))
            return DeliveryFailure(error.message ?: "ارسال این برگه کامل نشد.",retry,
                if(retry)maxOf(api?.retryAt ?: 0,now+30_000) else 0,
                error is IOException || (api!=null && (api.status in setOf(0,401,403,408,410,429) || api.status>=500 || api.retryAt>now)))
        }
    }
}

/** Storage callbacks commit each receipt separately, including successful siblings in a partial batch. */
interface ScanOutbox {
    fun load(id:String):JSONObject?
    fun validate(item:JSONObject)
    fun sending(id:String)
    fun delivered(id:String,receipt:JSONObject)
    fun failed(id:String,failure:DeliveryFailure)
}

data class ScanSendSummary(val sent:Int,val remaining:Int,val deferred:Int,val stopped:Boolean)

class ScanQueueSender(private val network:ConnectionsManager,private val clock:()->Long=System::currentTimeMillis) {
    companion object {
        const val MAX_ITEMS=50
        const val MAX_BYTES=4*1024*1024
        const val MAX_PACKET_BYTES=96*1024
        fun envelope(items:List<JSONObject>)=json("schemaVersion" to "roshd.scan-batch.v1","scans" to items)
    }

    fun send(owner:Session,summaries:List<JSONObject>,batch:Boolean,single:Boolean,outbox:ScanOutbox,
             progress:(Int,Int)->Unit={_,_->}):ScanSendSummary {
        val now=clock()
        val candidates=summaries.filter { ScanQueuePolicy.canSend(it,now) }.sortedBy { it.optLong("created") }
        val deferred=summaries.count { it.s("status")=="retry_wait" && it.optLong("retryAt")>now }
        require(candidates.isEmpty() || batch || single) { "پذیرش پاسخ‌نامه در سرور فعال نیست؛ برگه‌ها محفوظ‌اند." }
        val group=mutableListOf<JSONObject>()
        val overhead=envelope(emptyList()).toString().toByteArray(Charsets.UTF_8).size
        var groupBytes=overhead
        var school=""
        var sent=0;var processed=0;var stopped=false
        progress(0,candidates.size)
        fun done() { progress(++processed,candidates.size) }
        fun fail(id:String,error:Exception) {
            val failure=DeliveryFailure.from(error,clock())
            outbox.failed(id,failure);stopped=stopped || failure.stop;done()
        }
        fun invalidReceipt()=ApiFailure("invalid_receipt","رسید این برگه تأیید نشد؛ درخواست محفوظ برای بررسی دوباره نگه داشته شد.",502)
        fun accept(item:JSONObject,receipt:JSONObject) {
            val id=item.s("id")
            try { ReceiptVerifier.validate("scan",item.o("request"),receipt) }
            catch(_:Exception) { fail(id,invalidReceipt());return }
            outbox.delivered(id,receipt);sent++;done()
        }
        fun flush() {
            if(group.isEmpty() || stopped)return
            val items=group.toList();group.clear();groupBytes=overhead
            items.forEach { outbox.sending(it.s("id")) }
            val body=if(batch)envelope(items.map { it.o("request") }) else items.single().o("request")
            val wire=try {
                network.request("POST",if(batch)"/api/v1/scanner/scans/batch" else "/api/v1/scanner/scans",
                    owner,body=body,school=school)
            } catch(error:Exception) {
                items.forEach { fail(it.s("id"),error) }
                // A failed transport can follow already committed items; keep their exact requests for retry.
                stopped=true;return
            }
            if(!batch) {
                accept(items.single(),wire.optJSONObject("receipt") ?: wire.data().optJSONObject("receipt") ?: wire.data())
                return
            }
            val response=wire.data()
            if(response.s("schemaVersion")!="roshd.scan-batch-result.v1" || response.optJSONArray("items")==null) {
                items.forEach { fail(it.s("id"),invalidReceipt()) };return
            }
            val results=response.a("items").objects()
            items.forEachIndexed { index,item ->
                val id=item.s("id")
                val matches=results.filter { it.optInt("index",-1)==index }
                val row=matches.singleOrNull()
                if(row==null || !row.s("scanId").equals(id,true) || results.count { it.s("scanId").equals(id,true) }!=1) {
                    fail(id,invalidReceipt());return@forEachIndexed
                }
                val status=row.optInt("httpStatus",0)
                val outcome=row.s("outcome")
                when {
                    (outcome=="created" && status==201) || (outcome=="already_accepted" && status==200) -> {
                        val receipt=row.optJSONObject("result")
                        if(receipt==null)fail(id,invalidReceipt()) else accept(item,receipt)
                    }
                    outcome in setOf("rejected","conflict","invalid") && status in 400..599 -> {
                        val code=row.s("errorCode","scan_rejected")
                        fail(id,ApiFailure(code,"سرور این برگه را نپذیرفت ($code؛ $status).",status))
                    }
                    else -> fail(id,invalidReceipt())
                }
            }
        }
        for(summary in candidates) {
            if(stopped)break
            val id=summary.s("id")
            val item=outbox.load(id) ?: continue
            if(!ScanQueuePolicy.canSend(item,clock()))continue
            val itemSchool=item.o("metadata").s("schoolId")
            val size=try {
                require(uuid(itemSchool) in owner.schools && item.o("request").s("scanId")==id)
                outbox.validate(item)
                item.o("request").toString().toByteArray(Charsets.UTF_8).size.also { require(it in 1..MAX_PACKET_BYTES) }
            } catch(error:Exception) { fail(id,error);continue }
            if(group.isNotEmpty() && (school!=itemSchool || group.size==MAX_ITEMS || groupBytes+size+1>MAX_BYTES))flush()
            if(stopped)break
            if(group.isEmpty())school=itemSchool
            groupBytes+=size+if(group.isEmpty())0 else 1
            group.add(item)
            if(!batch || group.size==MAX_ITEMS)flush()
        }
        flush()
        return ScanSendSummary(sent,candidates.size-sent,deferred,stopped)
    }
}
