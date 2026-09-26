package com.maxrave.domain.repository

import com.maxrave.domain.data.model.update.UpdateData
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow

interface UpdateRepository {
    fun checkForGithubReleaseUpdate(): Flow<Resource<UpdateData>>
    fun checkForFdroidUpdate(): Flow<Resource<UpdateData>>

    /**
     * HTML 重定向兜底(不消耗 GitHub API 匿名限额):从 releases/latest 的重定向目标
     * 提取最新 tag 名。只有 tag,没有 release notes 正文(更新弹窗本就只显示 tag+跳转)。
     */
    fun checkForGithubReleaseUpdateViaRedirect(): Flow<Resource<UpdateData>>

    fun getFdroidSigningKeys(): Flow<Resource<List<String>>>
}