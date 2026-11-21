package com.haseebullahjan.roshd.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArchivePhotosTest {
    @Test fun deletingOnePhotoPersistsWithoutTouchingOtherPhotosResultsOrUploadEvidence() {
        val data=mutableMapOf<Triple<String,String,String>,JSONObject>()
        fun put(scope:String,kind:String,id:String,value:JSONObject) { data[Triple(scope,kind,id)]=value.copy() }
        fun read(scope:String,kind:String,id:String)=data[Triple(scope,kind,id)]?.copy()
        fun repository()=ArchivePhotos(::read,::put,{ scope,kind,id -> data.remove(Triple(scope,kind,id)) })
        val scan=json("id" to "one","sourcePath" to "/evidence/one.jpg","sealed" to true,"scanId" to "signed-one")
        put("account-a","scan","one",scan)
        put("account-a","image","signed-one",json("phase" to "uploading"))
        put("account-a","archive","one",json("sourcePath" to "/evidence/one.jpg"))
        put("account-a","archive","graded:one",json("sourcePath" to "/evidence/graded-one.jpg"))
        put("account-a","archive","two",json("sourcePath" to "/evidence/two.jpg"))
        put("account-b","archive","one",json("sourcePath" to "/other/one.jpg"))
        repository().remove("account-a","one")
        assertNull(read("account-a","archive","one"))
        assertTrue(repository().removed("account-a","one")) // Survives repository/app recreation.
        assertFalse(repository().removed("account-b","one"))
        assertEquals(canonical(scan),canonical(read("account-a","scan","one")))
        assertEquals("uploading",read("account-a","image","signed-one")!!.s("phase"))
        for((scope,id) in listOf("account-a" to "graded:one","account-a" to "two","account-b" to "one"))assertNotNull(read(scope,"archive",id))
        repository().remove("account-a","graded:one")
        assertTrue(repository().removed("account-a","graded:one"))
        assertNotNull(read("account-a","scan","one"))
        assertThrows(IllegalArgumentException::class.java) { repository().remove("account-b","missing") }
        assertFalse(repository().removed("account-b","missing"))
    }
}
