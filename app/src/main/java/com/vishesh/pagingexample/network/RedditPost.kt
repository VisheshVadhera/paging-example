package com.vishesh.pagingexample.network

data class RedditPost(
        val name: String,
        val title: String,
        val score: Int,
        val author: String,
        val subreddit: String,
        val num_comments: Int,
        val created: Long,
        val thumbnail: String?,
        val url: String?) {
    // to be consistent w/ changing backend order, we need to keep a data like this
    var indexInResponse: Int = -1
}