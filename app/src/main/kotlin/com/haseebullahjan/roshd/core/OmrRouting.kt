package com.haseebullahjan.roshd.core

object OmrRouting {
    fun historicalBindings(packages:Collection<OfflineExam>,round:Int):List<org.json.JSONObject> =
        packages.filter { it.valid && it.exam.round==round }.flatMap { pack ->
            pack.roster.filter { Regex("^S2[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{18}$").matches(it.sheetQr) }.map { it.sheetQr to pack.exam.template }
        }.groupBy { it.first }.values.filter { it.size==1 && it.single().second.isNotEmpty() }
            .map { rows -> json("qrText" to rows.single().first,"sheetType" to rows.single().second) }
    fun historicalFamily(qr:String,packages:Collection<OfflineExam>,round:Int):String? = historicalBindings(packages,round).singleOrNull { it.s("qrText")==qr }?.s("sheetType")
}
