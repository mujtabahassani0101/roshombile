package com.haseebullahjan.roshd.core

import org.json.JSONObject

object ReceiptVerifier {
    fun validate(kind:String,request:JSONObject,receipt:JSONObject) {
        require(kind=="scan")
        val payload=JSONObject(String(ScannerCrypto.decode(request.s("payloadBase64")),Charsets.UTF_8))
        require(receipt.s("${kind}Id")==request.s("${kind}Id") && receipt.s("examId")==payload.s("examId")) { "رسید با درخواست محفوظ مطابقت ندارد." }
        val compact=payload.s("identityMode")=="qr-lite-v1"
        require(receipt.s("inputSha256")==request.s("inputSha256"))
        if(compact)require(receipt.s("clientSheetId")==payload.s("clientSheetId") && receipt.s("qrText")==payload.s("qrText") && receipt.s("studentPublicId")==payload.s("studentPublicId"))
        else require(payload.s("sheetInstanceId").isNotEmpty() && receipt.s("sheetInstanceId")==payload.s("sheetInstanceId"))
        require(receipt.optInt("questionCount") in 1..120 && receipt.optInt("questionCount")==payload.optInt("questionCount") && receipt.s("status") in setOf("received","needs_review","processing","accepted"))
        parseServerInstant(receipt.s("receivedAt"))
    }
}
