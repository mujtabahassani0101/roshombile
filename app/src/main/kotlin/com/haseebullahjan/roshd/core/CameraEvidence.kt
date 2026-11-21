package com.haseebullahjan.roshd.core

import java.io.File
import java.io.RandomAccessFile

object CameraEvidence {
    /** A reservation is recoverable only after CameraX has committed a complete JPEG. */
    fun complete(file:File):Boolean = runCatching {
        if(!file.isFile || file.length() !in 4..16*1024*1024L)return false
        RandomAccessFile(file,"r").use { input ->
            val start=input.readUnsignedShort();input.seek(input.length()-2)
            start==0xffd8 && input.readUnsignedShort()==0xffd9
        }
    }.getOrDefault(false)
}
