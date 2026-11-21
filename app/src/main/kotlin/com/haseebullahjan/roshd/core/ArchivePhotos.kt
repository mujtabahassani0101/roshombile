package com.haseebullahjan.roshd.core

import org.json.JSONObject

/** Archive membership is separate from the durable scan and its upload evidence. */
class ArchivePhotos(
    private val read:(String,String,String)->JSONObject?,
    private val write:(String,String,String,JSONObject)->Unit,
    private val delete:(String,String,String)->Unit
) {
    fun removed(scope:String,id:String)=read(scope,"archive-removed",id)!=null
    fun remove(scope:String,id:String) {
        require(read(scope,"archive",id)!=null || read(scope,"scan",id)!=null) { "عکس در آرشیف پیدا نشد." }
        write(scope,"archive-removed",id,json("id" to id,"removedAt" to now()))
        delete(scope,"archive",id)
    }
}
