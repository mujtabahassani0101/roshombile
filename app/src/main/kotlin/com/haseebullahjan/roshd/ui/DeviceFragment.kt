package com.haseebullahjan.roshd.ui

import com.haseebullahjan.roshd.MainActivity
import com.haseebullahjan.roshd.core.*
import com.haseebullahjan.roshd.ui.actionbar.Route
import com.haseebullahjan.roshd.ui.components.*
import org.json.JSONObject
import java.io.File

class DeviceFragment(activity:MainActivity,route:Route):PageFragment(activity,route,"ثبت و وضعیت دستگاه") {
    private var details:JSONObject?=null
    private var selected=""
    override fun render() {
        val owner=app.session ?: return
        if(selected.isEmpty())selected=owner.schools.firstOrNull() ?: ""
        note("دستگاه با کلید اختصاصی همین حساب و مکتب ثبت می‌شود.")
        if(owner.province)owner.schools.forEach { school -> action(app.exams.firstOrNull { it.schoolId==school }?.raw?.s("schoolName")?.ifEmpty { null } ?: "مکتب ${school.take(8)}",selected==school) { selected=school;details=null;rerender() } }
        action("آماده‌سازی مشخصات دستگاه",true) {
            app.run("آماده‌سازی ثبت دستگاه…",{ result(it);rerender() }) {
                require(selected in owner.schools)
                val crypto=ScannerCrypto(app.native);val local=crypto.identity(owner.scope(selected))
                val request=crypto.registration(owner.scope(selected),local)
                details=json("request" to request,"keys" to local.keys,"deviceUuid" to local.deviceUuid)
                app.storage.put(owner.scope,"registration-request",selected,request)
                "مشخصات دستگاه آماده شد."
            }
        }
        details?.let { data ->
            item(data.o("request").s("deviceName"),data.o("request").s("appVersion"),0xe2ce)
            note("شناسهٔ دستگاه: ${data.s("deviceUuid")}")
            note("اثر انگشت امضا: ${data.o("keys").s("signingPublicKeySha256").take(24)}…")
            if(data.o("keys").optBoolean("encryptionUpgradeRequired")) {
                note("این دستگاه کلید قدیمی دارد. پس از ارسال صف‌ها و عکس‌های قبلی، کلید نمره‌دهی را ارتقا دهید.")
                action("ارتقای کلید نمره‌دهی") { app.upgradeDevice(selected) { outcome -> result(outcome);if(outcome.isSuccess) { details=null;rerender() } } }
            }
            action("ثبت آنلاین دستگاه") {
                app.run("ثبت دستگاه…",{ result(it) }) {
                    val request=data.o("request")
                    val response=app.network.request("POST","/api/v1/scanner/devices",owner,request,selected).data()
                    val receipt=response.optJSONObject("device") ?: response
                    require(receipt.s("deviceUuid")==data.s("deviceUuid") && receipt.s("signingPublicKeySha256")==data.o("keys").s("signingPublicKeySha256")) { "رسید دستگاه با کلید فعلی مطابقت ندارد." }
                    app.storage.put(owner.scope,"registration",selected,receipt)
                    "دستگاه ثبت شد. برای کار آفلاین معلومات دور را دریافت کنید."
                }
            }
            action("اشتراک‌گذاری درخواست ثبت") {
                app.run("آماده‌سازی فایل ثبت…",::result) {
                    val file=File(activity.filesDir,"native-reports/device-registration.json").apply { parentFile!!.mkdirs();writeText(data.o("request").toString(2)) }
                    app.native.call("shareFile",mapOf("path" to file.path,"mimeType" to "application/json")){}
                    "فایل ثبت آماده شد."
                }
            }
        }
    }
}
