/*
 * 音源路由仓库:同一 HomeRepository 契约下按 selectedSource 选 YT/网易实现。
 * ViewModel/Screen 只注入 HomeRepository,对音源无感知 —— 换源 = 换实现实例(策略模式)。
 * YT 侧 emitAll 透传(保留缓存先行+网络刷新的多发射语义);网易侧单发取 first。
 */
package com.maxrave.data.repository

import com.maxrave.domain.data.model.home.HomeItem
import com.maxrave.domain.data.model.home.chart.Chart
import com.maxrave.domain.data.model.mood.Mood
import com.maxrave.domain.data.model.mood.genre.GenreObject
import com.maxrave.domain.data.model.mood.moodmoments.MoodsMomentObject
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.source.MusicSource
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

internal class SourceRoutingHomeRepository(
    private val youtube: HomeRepositoryImpl,
    private val netease: NeteaseRepositoryImpl,
    private val dataStoreManager: DataStoreManager,
) : HomeRepository {
    private suspend fun isNetease(): Boolean = dataStoreManager.selectedSource.first() == MusicSource.NETEASE.name

    override fun getHomeData(
        params: String?,
        viewString: String,
        songString: String,
    ): Flow<Resource<Pair<String?, List<HomeItem>>>> =
        flow {
            if (isNetease()) {
                emit(netease.getHomeData(params, viewString, songString).first())
            } else {
                emitAll(youtube.getHomeData(params, viewString, songString))
            }
        }

    override fun getHomeDataContinue(
        continueParam: String,
        viewString: String,
        songString: String,
    ): Flow<Resource<Pair<String?, List<HomeItem>>>> =
        flow {
            if (isNetease()) {
                emit(netease.getHomeDataContinue(continueParam, viewString, songString).first())
            } else {
                emitAll(youtube.getHomeDataContinue(continueParam, viewString, songString))
            }
        }

    override fun getNewRelease(
        newReleaseString: String,
        musicVideoString: String,
    ): Flow<Resource<List<HomeItem>>> =
        flow {
            if (isNetease()) {
                emit(netease.getNewRelease(newReleaseString, musicVideoString).first())
            } else {
                emitAll(youtube.getNewRelease(newReleaseString, musicVideoString))
            }
        }

    override fun getChartData(countryCode: String): Flow<Resource<Chart>> =
        flow {
            if (isNetease()) {
                emit(netease.getChartData(countryCode).first())
            } else {
                emitAll(youtube.getChartData(countryCode))
            }
        }

    override fun getMoodAndMomentsData(): Flow<Resource<Mood>> =
        flow {
            if (isNetease()) {
                emit(netease.getMoodAndMomentsData().first())
            } else {
                // YT 实现是"缓存先行+网络覆盖"两次发射,必须整体透传
                emitAll(youtube.getMoodAndMomentsData())
            }
        }

    override fun getMoodCategoryArtwork(params: String): Flow<String?> =
        flow {
            if (isNetease()) {
                emit(netease.getMoodCategoryArtwork(params).first())
            } else {
                emitAll(youtube.getMoodCategoryArtwork(params))
            }
        }

    override fun getGenreData(params: String): Flow<Resource<GenreObject>> =
        flow {
            if (isNetease()) {
                emit(netease.getGenreData(params).first())
            } else {
                emitAll(youtube.getGenreData(params))
            }
        }

    override fun getMoodData(params: String): Flow<Resource<MoodsMomentObject>> =
        flow {
            if (isNetease()) {
                emit(netease.getMoodData(params).first())
            } else {
                emitAll(youtube.getMoodData(params))
            }
        }

    /** 账户摘要(主页欢迎区):YT 读 cookie+AccountName 键,网易读网易键 —— view 无感 */
    override fun getAccountInfo(): Flow<Pair<String?, String?>?> =
        flow {
            val info =
                if (isNetease()) {
                    if (dataStoreManager.neteaseCookie.first().isNotEmpty()) {
                        Pair(
                            dataStoreManager.neteaseAccountName.first(),
                            dataStoreManager.neteaseAccountThumbUrl.first(),
                        )
                    } else {
                        null
                    }
                } else if (dataStoreManager.cookie.first().isNotEmpty()) {
                    Pair(
                        dataStoreManager.getString("AccountName").first(),
                        dataStoreManager.getString("AccountThumbUrl").first(),
                    )
                } else {
                    null
                }
            emit(info) // 未登录也要发射 null,空流会让 .first() 崩
        }

    /** 地区榜选择器是否显示(网易榜单不分地区) */
    override suspend fun showRegionChartSelector(): Boolean = !isNetease()
}
