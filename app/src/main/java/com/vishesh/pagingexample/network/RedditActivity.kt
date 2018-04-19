package com.vishesh.pagingexample.network

import android.arch.lifecycle.LiveDataReactiveStreams
import android.arch.lifecycle.Observer
import android.arch.paging.PagedList
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.vishesh.pagingexample.R
import io.reactivex.FlowableSubscriber
import io.reactivex.Observable
import kotlinx.android.synthetic.main.activity_reddit.*
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.util.concurrent.Executors

class RedditActivity : AppCompatActivity() {

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, RedditActivity::class.java)
        }
    }

    private lateinit var viewModel: RedditViewModel
    private lateinit var adapter: PostsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reddit)
        initViewModel()
        initAdapter()
        initRxViewBindings()
//        viewModel.showSubreddit("androiddev")
    }

    private fun initViewModel() {
        val repo = InMemoryByItemRepo(RedditService.create(), Executors.newFixedThreadPool(5))
        viewModel = RedditViewModel(repo)
    }

    private fun initAdapter() {
        val glide = Glide.with(this)
        adapter = PostsAdapter(glide) { viewModel.retry() }
        list.adapter = adapter
        list.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
    }

    private fun initRxViewBindings() {
        val uiIntent = Observable.just(UiIntent.ShowReddit("androiddev"))
        /*viewModel.posts.observe(this, Observer {
            adapter.submitList(it)
        })*/
        /*viewModel.statuses.observe(this, Observer {
            adapter.setNetworkState(it?.networkState)
        })*/
        uiIntent.compose(viewModel.bind())
                .subscribe {
                    when (it) {
                        is UiState.ListReceived -> adapter.submitList(it.list)
                        is UiState.ResultState -> adapter.setNetworkState(it.result.networkState)
                    }
                }
    }

}