package com.vishesh.pagingexample.network

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.arch.paging.DataSource
import android.arch.paging.ItemKeyedDataSource
import android.arch.paging.PagedList
import android.arch.paging.RxPagedListBuilder
import io.reactivex.Observable
import io.reactivex.ObservableTransformer
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import retrofit2.Call
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.Executor


class RedditViewModel(private val repo: RedditPostRepository) {
    /*private val subredditName = MutableLiveData<String>()

    private val repoResult = map(subredditName, { repo.postsOfSubreddit(it, 30) })
    val posts = switchMap(repoResult) { it.pagedList }
    val statuses = switchMap(repoResult) { it.statusUpdates }*/

    fun bind(): ObservableTransformer<UiIntent, UiState> {
        return ObservableTransformer {
            val connectableObservable = it
                                .ofType(UiIntent.ShowReddit::class.java)
                                .flatMap { repo.postsOfSubreddit(it.subredditName, 30) }
                    .share()

                        val pagedList = connectableObservable
                                .ofType(PagedList::class.java)
                                .map { UiState.ListReceived(it as PagedList<RedditPost>) }

                        val results = connectableObservable
                                .ofType(Result::class.java)
                                .map { UiState.ResultState(it) }

//                        connectableObservable.connect()
                        return@ObservableTransformer Observable.merge(pagedList, results)

            /*it.ofType(UiIntent.ShowReddit::class.java)
                    .flatMap { repo.postsOfSubreddit(it.subredditName, 30) }
                    .map { UiState.ListReceived(it) }*/
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
    class ListReceived(val list: PagedList<RedditPost>) : UiState()
    class ResultState(val result: Result) : UiState()
}

interface RedditPostRepository {
    fun postsOfSubreddit(subReddit: String, pageSize: Int): Observable<PagedList<RedditPost>>
}

/*data class Listing<T>(
        val pagedList: Observable<PagedList<T>>,
        val statusUpdates: Observable<Result>,
        val refresh: () -> Unit,
        val retry: () -> Unit
)*/

data class Listing<T>(
        val pagedList: LiveData<PagedList<T>>,
        val statusUpdates: LiveData<Result>,
        val refresh: () -> Unit,
        val retry: () -> Unit
)

class InMemoryByItemRepo(
        private val redditService: RedditService,
        private val networkExecutor: Executor) : RedditPostRepository {

    override fun postsOfSubreddit(subReddit: String, pageSize: Int): Observable<PagedList<RedditPost>> {

        val sourceFactory = SubRedditDataSourceFactory(redditService, subReddit, networkExecutor)

        val pagedListConfig = PagedList.Config.Builder()
                .setEnablePlaceholders(false)
                .setInitialLoadSizeHint(pageSize * 2)
                .setPageSize(pageSize)
                .build()
        val pagedList = RxPagedListBuilder(sourceFactory, pagedListConfig)
                .setFetchScheduler(Schedulers.io())
//                .setNotifyScheduler(AndroidSchedulers.mainThread())
                .buildObservable()


        /*val statusUpdates = sourceFactory
                .subject
                .blockingFirst()
                .pageSubject
                .toFlowable(BackpressureStrategy.LATEST)
                .toObservable()*/

        return pagedList
    }
}

/*
class InMemoryByItemRepo(
        private val redditService: RedditService,
        private val networkExecutor: Executor) : RedditPostRepository {

    override fun postsOfSubreddit(subReddit: String, pageSize: Int): Listing<RedditPost> {
        val sourceFactory = SubRedditDataSourceFactory(redditService, subReddit, networkExecutor)

        val pagedListConfig = PagedList.Config.Builder()
                .setEnablePlaceholders(false)
                .setInitialLoadSizeHint(pageSize * 2)
                .setPageSize(pageSize)
                .build()
        val pagedList = LivePagedListBuilder(sourceFactory, pagedListConfig)
                .setFetchExecutor(networkExecutor)
                .build()

        val statusUpdates = Transformations.switchMap(sourceFactory.subject, { it.pageSubject })

        return Listing(
                pagedList = pagedList,
                statusUpdates = statusUpdates,
                retry = { sourceFactory.subject.value?.retryFailed() },
                refresh = { sourceFactory.subject.value?.invalidate() }
        )
    }
}
*/

class SubRedditDataSourceFactory(
        private val redditApi: RedditService,
        private val subredditName: String,
        private val retryExecutor: Executor
) : DataSource.Factory<String, RedditPost>() {

    val subject = MutableLiveData<ItemKeyedSubredditDataSource>()

    override fun create(): DataSource<String, RedditPost> {
        val source = ItemKeyedSubredditDataSource(redditApi, subredditName, retryExecutor)
        subject.postValue(source)
        return source
    }
}

/*class SubRedditDataSourceFactory(
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
}*/

/*class ItemKeyedSubredditDataSource(
        private val redditService: RedditService,
        private val subredditName: String,
        private val retryExecutor: Executor
) : ItemKeyedDataSource<String, RedditPost>() {

    private var retry: (() -> Any)? = null

    val pageSubject: PublishSubject<Result> = PublishSubject.create<Result>()

    fun retryFailed() {
        val prevRetry = retry
        retry = null
        prevRetry?.let { retryExecutor.execute { it.invoke() } }
    }

    override fun loadInitial(params: LoadInitialParams<String>, callback: LoadInitialCallback<RedditPost>) {
        redditService
                .getTop(subredditName, params.requestedLoadSize)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe { pageSubject.onNext(Result(NetworkState.LOADING)) }
                .subscribe({
                    val items = it.data.children.map { it.data }
                    retry = null
                    pageSubject.onNext(Result(NetworkState.LOADED))
                    callback.onResult(items)
                }) {
                    retry = { loadInitial(params, callback) }
                    pageSubject.onNext(Result(NetworkState.ERROR))
                }
    }

    override fun loadAfter(params: LoadParams<String>, callback: LoadCallback<RedditPost>) {
        redditService
                .getTopAfter(subredditName, params.key, params.requestedLoadSize)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe { pageSubject.onNext(Result(NetworkState.LOADING)) }
                .subscribe({
                    val items = it.data.children.map { it.data }
                    retry = null
                    callback.onResult(items)
                    pageSubject.onNext(Result(NetworkState.LOADED))
                }) {
                    retry = { loadAfter(params, callback) }
                    pageSubject.onNext(Result(NetworkState.ERROR))
                }
    }

    override fun loadBefore(params: LoadParams<String>, callback: LoadCallback<RedditPost>) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun getKey(item: RedditPost): String = item.name
}*/

/*class ItemKeyedSubredditDataSource(
        private val redditService: RedditService,
        private val subredditName: String,
        private val retryExecutor: Executor
) : ItemKeyedDataSource<String, RedditPost>() {

    private var retry: (() -> Any)? = null

    val pageSubject: PublishSubject<Result> = PublishSubject.create<Result>()

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
            pageSubject.onNext(Result(NetworkState.LOADED))
            callback.onResult(items!!)
        } catch (e: IOException) {
            retry = { loadInitial(params, callback) }
            pageSubject.onNext(Result(NetworkState.ERROR))
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
                        pageSubject.onNext(Result(NetworkState.ERROR))
                    }

                    override fun onResponse(
                            call: Call<RedditService.ListingResponse>?,
                            response: Response<RedditService.ListingResponse>) {
                        if (response.isSuccessful) {
                            val items = response.body()?.data?.children?.map { it.data }
                            retry = null
                            callback.onResult(items!!)
                            pageSubject.onNext(Result(NetworkState.LOADED))
                        } else {
                            retry = { loadAfter(params, callback) }
                            pageSubject.onNext(Result(NetworkState.ERROR))
                        }
                    }
                })


    }

    override fun loadBefore(params: LoadParams<String>, callback: LoadCallback<RedditPost>) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun getKey(item: RedditPost): String = item.name
}*/

class ItemKeyedSubredditDataSource(
        private val redditService: RedditService,
        private val subredditName: String,
        private val retryExecutor: Executor
) : ItemKeyedDataSource<String, RedditPost>() {

    private var retry: (() -> Any)? = null

    val pageSubject = MutableLiveData<Result>()

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
            pageSubject.postValue(Result(NetworkState.LOADED))
            callback.onResult(items!!)
        } catch (e: IOException) {
            retry = { loadInitial(params, callback) }
            pageSubject.postValue(Result(NetworkState.ERROR))
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
                        pageSubject.postValue(Result(NetworkState.ERROR))
                    }

                    override fun onResponse(
                            call: Call<RedditService.ListingResponse>?,
                            response: Response<RedditService.ListingResponse>) {
                        if (response.isSuccessful) {
                            val items = response.body()?.data?.children?.map { it.data }
                            retry = null
                            callback.onResult(items!!)
                            pageSubject.postValue(Result(NetworkState.LOADED))
                        } else {
                            retry = { loadAfter(params, callback) }
                            pageSubject.postValue(Result(NetworkState.ERROR))
                        }
                    }
                })


    }

    override fun loadBefore(params: LoadParams<String>, callback: LoadCallback<RedditPost>) {
    }

    override fun getKey(item: RedditPost): String = item.name
}

class Result(val networkState: NetworkState)

sealed class UiIntent {
    class ShowReddit(val subredditName: String) : UiIntent()
}