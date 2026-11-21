package com.haseebullahjan.roshd.core

import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

object EvidenceArchive {
    fun restore(source:File,target:File,expectedHash:String?=null):File {
        require(source.isFile)
        if(source.extension!="ysheet") {
            if(expectedHash!=null)require(hash(source)==expectedHash) { "اصل عکس با اثر انگشت محفوظ مطابقت ندارد." }
            return source
        }
        DataInputStream(source.inputStream().buffered()).use { input ->
            val magic=ByteArray(8);input.readFully(magic);require(String(magic,Charsets.US_ASCII)=="YSESHT01")
            val codec=input.readUnsignedByte();val size=input.readLong();val hash=ByteArray(32);input.readFully(hash)
            require(codec in 0..1 && size in 1..512L*1024*1024)
            val expected=hash.joinToString("") { "%02x".format(it) };if(expectedHash!=null)require(expected==expectedHash)
            if(target.exists() && target.length()==size && hash(target)==expected)return target
            val stream=if(codec==1)GZIPInputStream(input) else input
            val temporary=File(target.path+".partial");target.parentFile!!.mkdirs()
            try {
                var count=0L;val digest=MessageDigest.getInstance("SHA-256")
                temporary.outputStream().use { output -> val buffer=ByteArray(65536);while(true) { val n=stream.read(buffer);if(n<0)break;count+=n;require(count<=size);digest.update(buffer,0,n);output.write(buffer,0,n) };output.fd.sync() }
                require(count==size && digest.digest().contentEquals(hash)) { "آرشیف برگه کامل یا سالم نیست؛ فایل اصلی محفوظ است." }
                require(temporary.renameTo(target));return target
            } finally { temporary.delete() }
        }
    }
    fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes=ByteArray(65536);while(true) { val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
