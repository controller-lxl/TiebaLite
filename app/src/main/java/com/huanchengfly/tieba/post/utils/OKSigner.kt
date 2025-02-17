package com.huanchengfly.tieba.post.utils

import android.content.Context
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.models.MSignBean
import com.huanchengfly.tieba.post.api.models.SignResultBean
import com.huanchengfly.tieba.post.api.retrofit.ApiResult
import com.huanchengfly.tieba.post.api.retrofit.doIfFailure
import com.huanchengfly.tieba.post.api.retrofit.doIfSuccess
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorCode
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.models.SignDataBean
import com.huanchengfly.tieba.post.models.database.Account
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import java.lang.ref.WeakReference
import java.util.concurrent.ThreadLocalRandom
import kotlin.properties.Delegates

abstract class IOKSigner(
    context: Context
) {
    private val contextWeakReference: WeakReference<Context> = WeakReference(context)

    val context: Context
        get() = contextWeakReference.get()!!

    abstract suspend fun start(): Boolean

    suspend fun sign(signDataBean: SignDataBean): ApiResult<SignResultBean> {
        return TiebaApi.getInstance()
            .signAsync(signDataBean.forumName, signDataBean.tbs)
            .await()
    }

    fun getSignDelay(): Long {
        return if (context.appPreferences.oksignSlowMode) {
            ThreadLocalRandom.current().nextInt(3500, 8000).toLong()
        } else {
            2000
        }
    }
}

/*
class MultiAccountSigner(
        context: Context
) : IOKSigner(context) {
    private val accounts: MutableList<Account> = mutableListOf()

    override suspend fun start() {
        accounts.clear()
        accounts.addAll(AccountUtil.allAccounts)
    }

    interface ProgressListener {
        fun onStart(
                total: Int
        )

        fun onProgressStart(
                signDataBean: SignDataBean,
                current: Int,
                total: Int
        )

        fun onProgressFinish(
                signResultBean: SignResultBean,
                current: Int,
                total: Int
        )

        fun onFinish(
                success: Boolean,
                signedCount: Int,
                total: Int
        )

        fun onFailure(
                current: Int,
                total: Int,
                errorCode: Int,
                errorMsg: String
        )
    }
}
*/

class SingleAccountSigner(
    context: Context,
    private val account: Account
) : IOKSigner(context) {
    companion object {
        const val TAG = "SingleAccountSigner"
    }

    private val signData: MutableList<SignDataBean> = mutableListOf()
    private var position = 0
    private var successCount = 0
    private var totalCount = 0
    private var mSignCount = 0

    var lastFailure: Throwable? = null

    private var mProgressListener: ProgressListener? = null

    fun setProgressListener(listener: ProgressListener?) {
        mProgressListener = listener
    }

    @OptIn(FlowPreview::class)
    override suspend fun start(): Boolean {
        var result = false
        signData.clear()
        var userName: String by Delegates.notNull()
        var tbs: String by Delegates.notNull()
        AccountUtil.fetchAccountFlow(account)
            .flatMapConcat { account ->
                userName = account.name
                tbs = account.tbs
                TiebaApi.getInstance().getForumListFlow()
            }
            .flatMapConcat { getForumListBean ->
                val useMSign = context.appPreferences.oksignUseOfficialOksign
                val mSignLevel = getForumListBean.level.toInt()
                signData.addAll(getForumListBean.forumInfo
                    .filter { it.isSignIn != "1" }
                    .map {
                        SignDataBean(
                            it.forumName,
                            it.forumId,
                            userName,
                            tbs,
                            it.userLevel.toInt() >= mSignLevel
                        )
                    })
                totalCount = signData.size
                mSignCount = 0
                if (useMSign) {
                    val mSignData = signData.filter { it.canUseMSign }
                    TiebaApi.getInstance().mSign(mSignData.joinToString(",") { it.forumId }, tbs)
                        .map { it.info }
                } else {
                    flow { emit(emptyList()) }
                }
                    .onStart {
                        mProgressListener?.onStart(totalCount)
                    }
                    .catch { emit(emptyList()) }
            }
            .flatMapConcat { mSignInfo ->
                val newSignData = if (mSignInfo.isNotEmpty()) {
                    val mSignInfoMap = mutableMapOf<String, MSignBean.Info>()
                    mSignInfo.forEach {
                        mSignInfoMap[it.forumId] = it
                    }
                    val signedCount = mSignInfo.filter { it.signed == "1" }.size
                    successCount += signedCount
                    signData
                        .filter { !it.canUseMSign || mSignInfoMap[it.forumId]?.signed != "1" }
                } else {
                    signData.toList()
                }
                mSignCount = totalCount - newSignData.size
                newSignData
                    .asFlow()
                    .onEach {
                        position = signData.indexOf(it)
                        mProgressListener?.onProgressStart(it, position + mSignCount, signData.size)
                    }
                    .onEmpty {
                        mProgressListener?.onFinish(
                            successCount == totalCount,
                            successCount,
                            totalCount
                        )
                    }
                    .map { data -> sign(data) }
            }
            /*
            .flatMapConcat { forumRecommend ->
                signData.addAll(forumRecommend.likeForum.filter { it.isSign != "1" }
                    .map { SignDataBean(it.forumName, userName, tbs) })
                totalCount = signData.size
                signData
                    .asFlow()
                    .onEach {
                        position = signData.indexOf(it)
                        mProgressListener?.onProgressStart(it, position, signData.size)
                    }
                    .onEmpty {
                        mProgressListener?.onFinish(true, 0, 0)
                    }
                    .onStart {
                        mProgressListener?.onStart(totalCount)
                    }
                    .map { data -> sign(data) }
            }
            */
            .catch { e -> emit(ApiResult.Failure(e)) }
            .onCompletion {
                mProgressListener?.onFinish(
                    successCount == totalCount,
                    successCount,
                    totalCount
                )
            }
            .collect {
                it.doIfSuccess { res ->
                    result = true
                    successCount += 1
                    mProgressListener?.onProgressFinish(
                        signData[position],
                        res,
                        position,
                        totalCount
                    )
                }
                it.doIfFailure { e ->
                    result = false
                    lastFailure = e
                    mProgressListener?.onFailure(
                        position,
                        totalCount,
                        e.getErrorCode(),
                        e.getErrorMessage()
                    )
                }
                delay(getSignDelay())
            }
        return result
    }

}

interface ProgressListener {
    fun onStart(
        total: Int
    )

    fun onProgressStart(
        signDataBean: SignDataBean,
        current: Int,
        total: Int
    )

    fun onProgressFinish(
        signDataBean: SignDataBean,
        signResultBean: SignResultBean,
        current: Int,
        total: Int
    )

    fun onFinish(
        success: Boolean,
        signedCount: Int,
        total: Int
    )

    fun onFailure(
        current: Int,
        total: Int,
        errorCode: Int,
        errorMsg: String
    )
}