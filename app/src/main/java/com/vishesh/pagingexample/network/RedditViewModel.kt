package com.vishesh.pagingexample.network

import android.arch.paging.DataSource
import android.arch.paging.ItemKeyedDataSource
import android.arch.paging.PagedList
import android.arch.paging.RxPagedListBuilder
import io.reactivex.BackpressureStrategy
import io.reactivex.Observable
import io.reactivex.ObservableTransformer
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import retrofit2.Call
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.Executor


class RedditViewModel(private val repo: RedditPostRepository) {

    fun bind(): ObservableTransformer<UiIntent, UiState> {
        return ObservableTransformer {
            val connectableObservable = it
                    .ofType(UiIntent.ShowReddit::class.java)
                    .flatMap { repo.postsOfSubreddit(it.subredditName, 30) }
                    .share()

            val pagedList = connectableObservable
                    .ofType(Result.PagedListResult::class.java)
                    .map { UiState.List(it.list) }

            val results = connectableObservable
                    .ofType(Result.NetworkStateResult::class.java)
                    .map { UiState.State(it.networkState) }

            return@ObservableTransformer Observable.merge(pagedList, results)
        }
    }

    fun retry() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun refresh() {
        TODO("not implemented")
    }
}

sealed class UiState {
    class List(val pagedList: PagedList<RedditPost>) : UiState()
    class State(val networkState: NetworkState) : UiState()
}

interface RedditPostRepository {
    fun postsOfSubreddit(subReddit: String, pageSize: Int): Observable<Result>
}

class InMemoryByItemRepo(
        private val redditService: RedditService,
        private val networkExecutor: Executor) : RedditPostRepository {

    override fun postsOfSubreddit(subReddit: String, pageSize: Int): Observable<Result> {

        val sourceFactory = SubRedditDataSourceFactory(redditService, subReddit, networkExecutor)

        val pagedListConfig = PagedList.Config.Builder()
                .setEnablePlaceholders(false)
                .setInitialLoadSizeHint(pageSize * 2)
                .setPageSize(pageSize)
                .build()
        val pagedList = RxPagedListBuilder(sourceFactory, pagedListConfig)
                .setFetchScheduler(Schedulers.io())
                .setNotifyScheduler(AndroidSchedulers.mainThread())
                .buildObservable()
                .map<Result> { Result.PagedListResult(it) }


        val statusUpdates = sourceFactory
                .subject
                .flatMap { it.pageSubject }
                .toFlowable(BackpressureStrategy.LATEST)
                .toObservable()

        return Observable.merge(pagedList, statusUpdates)
    }
}

class SubRedditDataSourceFactory(
        private val redditApi: RedditService,
        private val subredditName: String,
        private val retryExecutor: Executor
) : DataSource.Factory<String, RedditPost>() {

    val subject = PublishSubject.create<ItemKeyedSubredditDataSource>()

    override fun create(): DataSource<String, RedditPost> {
        val source = ItemKeyedSubredditDataSource(redditApi, subredditName, retryExecutor)
        subject.onNext(source)
        return source
    }
}


class ItemKeyedSubredditDataSource(
        private val redditService: RedditService,
        private val subredditName: String,
        private val retryExecutor: Executor
) : ItemKeyedDataSource<String, RedditPost>() {

    private var retry: (() -> Any)? = null

    val pageSubject = PublishSubject.create<Result>()

    fun retryFailed() {
        val prevRetry = retry
        retry = null
        prevRetry?.let { retryExecutor.execute { it.invoke() } }
    }

    override fun loadInitial(params: LoadInitialParams<String>, callback: LoadInitialCallback<RedditPost>) {
        val request = redditService.getTop(subredditName, params.requestedLoadSize)

        try {
            val response = request.execute()
            val items = response.body()?.data?.children?.map { it.data }
            retry = null
            pageSubject.onNext(Result.NetworkStateResult(NetworkState.LOADED))
            callback.onResult(items!!)
        } catch (e: IOException) {
            retry = { loadInitial(params, callback) }
            pageSubject.onNext(Result.NetworkStateResult(NetworkState.ERROR))
        }

    }

    override fun loadAfter(params: LoadParams<String>, callback: LoadCallback<RedditPost>) {
        redditService
                .getTopAfter(subredditName, params.key, params.requestedLoadSize)
                .enqueue(object : retrofit2.Callback<RedditService.ListingResponse> {
                    override fun onFailure(
                            call: Call<RedditService.ListingResponse>?,
                            t: Throwable?) {
                        retry = { loadAfter(params, callback) }
                        pageSubject.onNext(Result.NetworkStateResult(NetworkState.ERROR))
                    }

                    override fun onResponse(
                            call: Call<RedditService.ListingResponse>?,
                            response: Response<RedditService.ListingResponse>) {
                        if (response.isSuccessful) {
                            val items = response.body()?.data?.children?.map { it.data }
                            retry = null
                            callback.onResult(items!!)
                            pageSubject.onNext(Result.NetworkStateResult(NetworkState.LOADED))
                        } else {
                            retry = { loadAfter(params, callback) }
                            pageSubject.onNext(Result.NetworkStateResult(NetworkState.ERROR))
                        }
                    }
                })


    }

    override fun loadBefore(params: LoadParams<String>, callback: LoadCallback<RedditPost>) {
    }

    override fun getKey(item: RedditPost): String = item.name
}

sealed class Result {
    class PagedListResult(val list: PagedList<RedditPost>) : Result()
    class NetworkStateResult(val networkState: NetworkState) : Result()
}

sealed class UiIntent {
    class ShowReddit(val subredditName: String) : UiIntent()
}