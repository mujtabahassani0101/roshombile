package com.haseebullahjan.roshd.core

import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Authenticate the entire artifact before network I/O, retaining only part hashes. */
internal class ImageUploadSnapshot private constructor(val size:Int,val mime:String,private val hashes:List<String>) {
    val partCount get()=hashes.size
    fun partHash(number:Int)=hashes[number-1]
    fun partSize(number:Int):Int { require(number in 1..partCount);return minOf(PART_SIZE,size-(number-1)*PART_SIZE) }
    fun readPart(file:File,number:Int):ByteArray {
        val bytes=ByteArray(partSize(number))
        RandomAccessFile(file,"r").use {
            require(it.length()==size.toLong()) { "عکس محفوظ هنگام ارسال تغییر کرده است؛ ارسال متوقف شد." }
            it.seek((number-1).toLong()*PART_SIZE);it.readFully(bytes)
        }
        require(sha256(bytes)==partHash(number)) { "بایت‌های بخش عکس با اصل تأییدشده مطابقت ندارند؛ ارسال متوقف شد." }
        return bytes
    }
    companion object {
        private const val PART_SIZE=1024*1024
        fun verify(file:File,expectedHash:String):ImageUploadSnapshot {
            val length=file.length()
            require(length in 1..16L*1024*1024) { "اصل عکس خالی یا بزرگ‌تر از ۱۶ مگابایت است." }
            val buffer=ByteArray(minOf(length.toInt(),PART_SIZE))
            val whole=MessageDigest.getInstance("SHA-256");val hashes=mutableListOf<String>()
            var mime=""
            DataInputStream(file.inputStream().buffered()).use { input ->
                for(offset in 0 until length.toInt() step PART_SIZE) {
                    val count=minOf(buffer.size,length.toInt()-offset);input.readFully(buffer,0,count)
                    if(offset==0)mime=when {
                        count>=8 && buffer.copyOfRange(0,8).contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))->"image/png"
                        count>=3 && buffer[0]==255.toByte() && buffer[1]==216.toByte()->"image/jpeg"
                        else->error("اصل عکس باید JPEG یا PNG باشد.")
                    }
                    whole.update(buffer,0,count)
                    val part=MessageDigest.getInstance("SHA-256").apply { update(buffer,0,count) }
                    hashes.add(part.digest().hex())
                }
                require(input.read()==-1) { "عکس محفوظ هنگام بررسی تغییر کرده است." }
            }
            require(whole.digest().hex()==expectedHash) { "اصل عکس با اثر انگشت ثبت‌شده مطابقت ندارد." }
            return ImageUploadSnapshot(length.toInt(),mime,hashes)
        }
        private fun ByteArray.hex()=joinToString("") { "%02x".format(it) }
    }
}
