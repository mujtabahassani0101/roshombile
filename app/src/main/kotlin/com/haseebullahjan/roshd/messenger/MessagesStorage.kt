package com.haseebullahjan.roshd.messenger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.haseebullahjan.roshd.core.*
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** SQLite transactions are authoritative; request bytes are never regenerated on retry. */
class MessagesStorage(private val context: Context): SQLiteOpenHelper(context,"roshd-native.db",null,2) {
    private val secret: SecretKey by lazy {
        val alias="roshd.native.storage.aes.v1"
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey) ?: KeyGenerator.getInstance("AES","AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE documents(scope TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, payload BLOB NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(scope,kind,id))")
        db.execSQL("CREATE TABLE outbox(id TEXT PRIMARY KEY, scope TEXT NOT NULL, kind TEXT NOT NULL, identity TEXT NOT NULL, request BLOB NOT NULL, metadata BLOB NOT NULL, status TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, retryAt INTEGER NOT NULL DEFAULT 0, receipt BLOB, error TEXT, created INTEGER NOT NULL, UNIQUE(scope,kind,identity))")
        db.execSQL("CREATE INDEX outbox_scope ON outbox(scope,kind,status)")
        createCameraWork(db)
    }
    private fun createCameraWork(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS camera_work(scope TEXT NOT NULL,id TEXT NOT NULL,round INTEGER NOT NULL,period TEXT NOT NULL,status TEXT NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(scope,id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS camera_work_pending ON camera_work(scope,status,created)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        require(oldVersion==1 && newVersion==2) { "Unsupported database upgrade; existing data retained" }
        createCameraWork(db)
    }
    fun addCameraWork(scope:String,id:String,round:Int,period:String) {
        val values=ContentValues().apply { put("scope",scope);put("id",id);put("round",round);put("period",period);put("status","capturing");put("created",System.currentTimeMillis()) }
        check(writableDatabase.insertOrThrow("camera_work",null,values)!=-1L)
    }
    fun cameraWorkStatus(scope:String,id:String,status:String) {
        writableDatabase.update("camera_work",ContentValues().apply { put("status",status) },"scope=? AND id=?",arrayOf(scope,id))
    }
    fun deleteCameraWork(scope:String,id:String) { writableDatabase.delete("camera_work","scope=? AND id=?",arrayOf(scope,id)) }
    fun deleteQueuedScan(scope:String,id:String) { writableDatabase.delete("outbox","scope=? AND kind='scan' AND id=?",arrayOf(scope,id)) }
    fun cameraWork(scope:String):List<JSONObject> = readableDatabase.query("camera_work",arrayOf("id","round","period","status","created"),
        "scope=? AND status IN ('capturing','pending','processing')",arrayOf(scope),null,null,"created ASC").use { cursor ->
        buildList { while(cursor.moveToNext())add(json("id" to cursor.getString(0),"round" to cursor.getInt(1),"period" to cursor.getString(2),"status" to cursor.getString(3),"created" to cursor.getLong(4))) }
    }
    fun cameraWorkCount(scope:String):Int = readableDatabase.rawQuery("SELECT count(*) FROM camera_work WHERE scope=? AND status IN ('capturing','pending','processing')",arrayOf(scope)).use { it.moveToFirst();it.getInt(0) }
    private fun seal(scope: String,value: String): ByteArray {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,secret)
        cipher.updateAAD(scope.toByteArray())
        return byteArrayOf(1,cipher.iv.size.toByte())+cipher.iv+cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    }
    private fun open(scope:String,value:ByteArray):String {
        require(value.size>14 && value[0]==1.toByte()) { "ساختار معلومات محفوظ معتبر نیست." }
        val size=value[1].toInt() and 255
        require(size in 12..16 && value.size>size+2)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,secret,GCMParameterSpec(128,value.copyOfRange(2,2+size)))
        cipher.updateAAD(scope.toByteArray())
        return String(cipher.doFinal(value.copyOfRange(2+size,value.size)),Charsets.UTF_8)
    }
    fun put(scope:String,kind:String,id:String,value:JSONObject) {
        writableDatabase.insertWithOnConflict("documents",null,ContentValues().apply {
            put("scope",scope);put("kind",kind);put("id",id);put("payload",seal("$scope/$kind/$id",value.toString()));put("updated",System.currentTimeMillis())
        },SQLiteDatabase.CONFLICT_REPLACE).also { check(it!=-1L) }
    }
    fun get(scope:String,kind:String,id:String):JSONObject? {
        val db=readableDatabase;val nested=db.inTransaction()
        if(!nested)db.beginTransactionNonExclusive()
        try {
            // A full information bundle can exceed Android's CursorWindow. Read encrypted
            // bytes in bounded BLOB slices in one snapshot; retain the original GCM/AAD.
            val length=db.rawQuery("SELECT length(payload) FROM documents WHERE scope=? AND kind=? AND id=?",arrayOf(scope,kind,id)).use {
                if(!it.moveToFirst())return null
                it.getLong(0)
            }
            require(length in 15..64L*1024*1024) { "حجم معلومات محفوظ معتبر نیست؛ اصل فایل در گوشی محفوظ است." }
            val bytes=ByteArray(length.toInt());val block=512*1024
            for(offset in bytes.indices step block) {
                val count=minOf(block,bytes.size-offset)
                db.rawQuery("SELECT substr(payload,?,?) FROM documents WHERE scope=? AND kind=? AND id=?",arrayOf((offset+1).toString(),count.toString(),scope,kind,id)).use {
                    require(it.moveToFirst()) { "معلومات محفوظ پیدا نشد." }
                    val part:ByteArray?=it.getBlob(0)
                    require(part!=null && part.size==count) { "خواندن معلومات محفوظ کامل نشد؛ اصل فایل در گوشی محفوظ است." }
                    part.copyInto(bytes,offset)
                }
            }
            val value=JSONObject(open("$scope/$kind/$id",bytes))
            if(!nested)db.setTransactionSuccessful()
            return value
        } finally { if(!nested)db.endTransaction() }
    }
    fun list(scope:String,kind:String):List<JSONObject> {
        val db=readableDatabase;val nested=db.inTransaction()
        if(!nested)db.beginTransactionNonExclusive()
        try {
            // Keep each BLOB query below the CursorWindow capacity. A single
            // cursor over the entire archive repeatedly refills its window
            // and scans earlier BLOBs, making large histories slower to open.
            val rows=db.query("documents",arrayOf("id","length(payload)"),"scope=? AND kind=?",arrayOf(scope,kind),null,null,"updated DESC").use { cursor ->
                buildList {
                    while(cursor.moveToNext()) {
                        val bytes=cursor.getLong(1)
                        require(bytes in 15..64L*1024*1024) { "حجم معلومات محفوظ معتبر نیست؛ اصل فایل در گوشی محفوظ است." }
                        add(cursor.getString(0) to bytes)
                    }
                }
            }
            val values=HashMap<String,JSONObject>(rows.size)
            val batch=ArrayList<String>(32);var batchBytes=0L
            fun flush() {
                if(batch.isEmpty())return
                val placeholders=batch.joinToString(",") { "?" }
                val args=arrayOf(scope,kind,*batch.toTypedArray())
                db.query("documents",arrayOf("id","payload"),"scope=? AND kind=? AND id IN ($placeholders)",args,null,null,null).use { cursor ->
                    while(cursor.moveToNext()) {
                        val id=cursor.getString(0);values[id]=JSONObject(open("$scope/$kind/$id",cursor.getBlob(1)))
                    }
                }
                batch.clear();batchBytes=0
            }
            for((id,bytes) in rows) {
                if(bytes>512*1024) {
                    flush();values[id]=requireNotNull(get(scope,kind,id))
                } else {
                    if(batchBytes+bytes>512*1024 || batch.size==32)flush()
                    batch.add(id);batchBytes+=bytes
                }
            }
            flush()
            if(!nested)db.setTransactionSuccessful()
            return rows.map { requireNotNull(values[it.first]) }
        } finally { if(!nested)db.endTransaction() }
    }
    fun delete(scope:String,kind:String,id:String) { writableDatabase.delete("documents","scope=? AND kind=? AND id=?",arrayOf(scope,kind,id)) }
    fun transaction(action:()->Unit) {
        val db=writableDatabase;db.beginTransaction()
        try { action();db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    fun enqueue(scope:String,kind:String,id:String,identity:String,request:JSONObject,metadata:JSONObject):Boolean {
        val values=ContentValues().apply {
            put("id",id);put("scope",scope);put("kind",kind);put("identity",identity)
            put("request",seal("$scope/outbox/$id",request.toString()));put("metadata",seal("$scope/meta/$id",metadata.toString()))
            put("status","pending");put("created",System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict("outbox",null,values,SQLiteDatabase.CONFLICT_IGNORE)!=-1L
    }
    fun queue(scope:String,kind:String?=null,includeRequests:Boolean=true,id:String?=null):List<JSONObject> {
        val selection=mutableListOf("scope=?");val args=mutableListOf(scope)
        if(kind!=null) { selection.add("kind=?");args.add(kind) }
        if(id!=null) { selection.add("id=?");args.add(id) }
        val db=readableDatabase;val nested=db.inTransaction()
        if(!nested)db.beginTransactionNonExclusive()
        try {
            // Never put complete encrypted packets in a CursorWindow. A large migrated row
            // must not hide the entire list or abort the rest of a submission batch.
            val columns=arrayOf("id","kind","identity","status","error","attempts","retryAt","created")
            val rows=db.query("outbox",columns,selection.joinToString(" AND "),args.toTypedArray(),null,null,"created DESC").use { cursor ->
                buildList {
                    while(cursor.moveToNext()) {
                        fun s(name:String)=cursor.getString(cursor.getColumnIndexOrThrow(name)) ?: ""
                        val rowId=s("id")
                        fun document(column:String,aad:String):JSONObject? {
                            require(column in setOf("request","metadata","receipt"))
                            val length=db.rawQuery("SELECT length($column) FROM outbox WHERE scope=? AND id=?",arrayOf(scope,rowId)).use {
                                require(it.moveToFirst());if(it.isNull(0))null else it.getLong(0)
                            } ?: return null
                            require(length in 15..64L*1024*1024) { "حجم ثبت محفوظ معتبر نیست." }
                            val bytes=ByteArray(length.toInt())
                            for(offset in bytes.indices step (512*1024)) {
                                val count=minOf(512*1024,bytes.size-offset)
                                db.rawQuery("SELECT substr($column,?,?) FROM outbox WHERE scope=? AND id=?",arrayOf((offset+1).toString(),count.toString(),scope,rowId)).use {
                                    require(it.moveToFirst());val part=it.getBlob(0);require(part!=null && part.size==count);part.copyInto(bytes,offset)
                                }
                            }
                            return JSONObject(open(aad,bytes))
                        }
                        val request=runCatching { requireNotNull(document("request","$scope/outbox/$rowId")) }
                        val metadata=runCatching { requireNotNull(document("metadata","$scope/meta/$rowId")) }
                        val receipt=runCatching { document("receipt","$scope/receipt/$rowId") }
                        val damaged=request.isFailure || metadata.isFailure || receipt.isFailure || (s("status")=="delivered" && receipt.getOrNull()==null)
                        val meta=metadata.getOrElse { JSONObject() }
                        // Older journals did not consistently store names / public IDs in metadata.
                        val payload=runCatching { JSONObject(String(ScannerCrypto.decode(request.getOrNull()?.s("payloadBase64") ?: ""),Charsets.UTF_8)) }.getOrNull()
                        for(key in listOf("examId","studentPublicId","capturedAt"))if(meta.s(key).isBlank())payload?.s(key)?.takeIf { it.isNotBlank() }?.let { meta.put(key,it) }
                        if(meta.s("studentPublicId").isBlank())meta.put("studentPublicId",meta.s("studentCode"))
                        val summary=if(includeRequests)meta else JSONObject().apply {
                            for(key in listOf("schoolId","examId","studentPublicId","studentName","round","grade","createdAt","capturedAt"))if(meta.has(key))put(key,meta.opt(key))
                        }
                        add(json("id" to rowId,"kind" to s("kind"),"identity" to s("identity"),"status" to if(damaged)"storage_error" else s("status"),
                            "error" to if(damaged)"اطلاعات این ثبت قابل خواندن نیست؛ اصل آن در گوشی محفوظ است و ارسال نمی‌شود." else s("error"),
                            "attempts" to cursor.getInt(cursor.getColumnIndexOrThrow("attempts")),"retryAt" to cursor.getLong(cursor.getColumnIndexOrThrow("retryAt")),"created" to cursor.getLong(cursor.getColumnIndexOrThrow("created")),
                            "request" to if(includeRequests)request.getOrElse { JSONObject() } else JSONObject(),"metadata" to summary,"receipt" to receipt.getOrNull()))
                    }
                }
            }
            if(!nested)db.setTransactionSuccessful()
            return rows
        } finally { if(!nested)db.endTransaction() }
    }
    fun status(scope:String,id:String,status:String,error:String?=null,retryAt:Long=0,receipt:JSONObject?=null) {
        val values=ContentValues().apply { put("status",status);put("error",error);put("retryAt",retryAt);if(receipt!=null)put("receipt",seal("$scope/receipt/$id",receipt.toString())) }
        writableDatabase.update("outbox",values,"scope=? AND id=?",arrayOf(scope,id))
    }
    fun bumpAttempt(scope:String,id:String) { writableDatabase.execSQL("UPDATE outbox SET attempts=attempts+1 WHERE scope=? AND id=?",arrayOf(scope,id)) }
    fun mediaFile(scope:String,name:String):File {
        require(Regex("^[A-Za-z0-9._-]+$").matches(name) && !name.contains(".."))
        return File(File(context.noBackupFilesDir,"native-media/${sha256(scope).take(24)}"),name).also { it.parentFile!!.mkdirs() }
    }
    fun stage(scope:String,source:File):File {
        val target=mediaFile(scope,"${java.util.UUID.randomUUID()}.${source.extension.ifEmpty { "jpg" }}")
        val atomic=AtomicFile(target);val stream=atomic.startWrite()
        try { source.inputStream().use { it.copyTo(stream) };atomic.finishWrite(stream) } catch(e:Exception) { atomic.failWrite(stream);throw e }
        return target
    }
}
