package com.laotoua.dawnislandk.data.local.entity

import com.laotoua.dawnislandk.data.local.entity.Post
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Trend(
    val rank: String,
    val hits: String,
    val forum: String,
    val id: String,
    val content: String
) {
    fun toPost(fid: String): Post {
        return Post(
            id = id,
            fid = fid,
            img = "",
            ext = "",
            now = "",
            userid = "",
            name = "",
            email = "",
            title = "",
            content = "",
            admin = ""
        )

    }
}