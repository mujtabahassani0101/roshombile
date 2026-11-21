package com.haseebullahjan.roshd.core

/** Printed identities stay exact. Historical formats are authorized only by a verified roster row. */
object SheetQrIdentity {
    private val compactAlias=Regex("^(?:[A-Za-z0-9]{8}|S2[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{18})$")
    private val historicalAlias=Regex("^[A-Za-z0-9]{1,32}$")
    private val boundedQr=Regex("^[A-Za-z0-9:_-]{1,128}$")
    private val printed=Regex("^T[13]:[0-9]{1,32}:(?:[A-Za-z0-9]{8}|S2[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{18})$")
    private val typedPrefix=Regex("^T[0-9]+$")
    private val card=Regex("^C2[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{18}$")
    fun currentAlias(value:String)=compactAlias.matches(value)
    fun isPrinted(value:String)=printed.matches(value)
    fun canBind(value:String,family:String):Boolean {
        if(family !in setOf("T1","T3") || !boundedQr.matches(value) || card.matches(value))return false
        val prefix=value.substringBefore(':')
        return !typedPrefix.matches(prefix) || (value.contains(':') && prefix==family)
    }
    fun historical(qr:String,alias:String,code:String,family:String):Boolean =
        historicalAlias.matches(alias) && canBind(qr,family) && qr.substringAfterLast(':')==alias &&
            (!typedPrefix.matches(qr.substringBefore(':')) || qr=="$family:$code:$alias")
}
