package com.haseebullahjan.roshd.core

/** Physical print families; retired sheets must never be relabelled as another layout. */
object SheetFamilies {
    val templates=listOf("yse_omr_a5_t1_100q_v2.json","yse_omr_a5_t3_40q_v1.json","yse_omr_a5_t1_75q_20261115.json",
        "yse_omr_a5_t3_40q_legacy_v1.json","yse_omr_a5_t1_100q_legacy_v2.json")
    val scanCounts=listOf(40,75,100)
    fun templateForSelection(count:Int):String=when(count) {
        40->templates[1];75->templates[2];100->templates[0];else->throw IllegalArgumentException("نوع برگه باید ۴۰، ۷۵ یا ۱۰۰سؤالی باشد.")
    }
    /** Older 75Q archives used the 100Q print with 25 inactive questions.
     * The native adapter verifies the printed circles before accepting either.
     */
    fun templatesForSelection(count:Int)=when(count) {
        0->templates
        75->listOf(templates[2],templates[0],templates[4])
        40->listOf(templates[1],templates[3])
        100->listOf(templates[0],templates[4])
        else->listOf(templateForSelection(count))
    }
    fun previewForSelection(count:Int)=when(count) {
        40->"omr-t3-preview.png";75->"omr-75-preview.png";100->"omr-t1-preview.png";else->throw IllegalArgumentException("نوع برگه معتبر نیست.")
    }
    fun matchesSelection(count:Int,family:String)=count==0 || (count in scanCounts && forCount(count)==family)
    fun matchesExamSelection(count:Int,examCount:Int)=count==0 ||
        (matchesSelection(count,forCount(examCount)) && (count==75)==(examCount==75))
    fun forCount(count:Int)=when(count) { in 1..40->"T3";in 61..100->"T1";else->"" }
}
