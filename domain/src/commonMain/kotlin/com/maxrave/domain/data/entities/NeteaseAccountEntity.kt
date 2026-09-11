package com.maxrave.domain.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 网易云多账户(GoogleAccountEntity 同构):一行一个账户,cookies 为完整会话 JSON */
@Entity(tableName = "netease_account")
data class NeteaseAccountEntity(
    @PrimaryKey(autoGenerate = false)
    val userId: Long = 0,
    val nickname: String = "",
    val avatarUrl: String = "",
    val cookies: String = "",
    val isUsed: Boolean = false,
)
