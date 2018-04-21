/*
 * This source is part of the
 *      _____  ___   ____
 *  __ / / _ \/ _ | / __/___  _______ _
 * / // / , _/ __ |/ _/_/ _ \/ __/ _ `/
 * \___/_/|_/_/ |_/_/ (_)___/_/  \_, /
 *                              /___/
 * repository.
 *
 * Copyright (C) 2018 Benoit 'BoD' Lubek (BoD@JRAF.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jraf.redditasopposedto

import io.reactivex.Completable
import io.reactivex.schedulers.Schedulers
import org.apache.commons.text.WordUtils
import org.jraf.klibreddit.client.RedditClient
import org.jraf.klibreddit.model.client.ClientConfiguration
import org.jraf.klibreddit.model.client.HttpConfiguration
import org.jraf.klibreddit.model.client.HttpLoggingLevel
import org.jraf.klibreddit.model.client.UserAgent
import org.jraf.klibreddit.model.listings.Comment
import org.jraf.klibreddit.model.listings.FirstPage
import org.jraf.klibreddit.model.listings.Pagination
import org.jraf.klibreddit.model.listings.Post
import org.jraf.klibreddit.model.listings.PostWithComments
import org.jraf.klibreddit.model.listings.Subreddits
import org.jraf.klibreddit.model.oauth.OAuthConfiguration
import java.util.Date
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

private const val PLATFORM = "cli"
private const val APP_ID = "asopposedto"
private const val VERSION = "1.0.0"
private const val AUTHOR_REDDIT_NAME = "as-opposed-to"

private const val COMMENT_TEXT = "As opposed to?"

private const val INITIAL_DELAY_MINUTES = 0L
private const val PERIOD_MINUTES = 25L

private const val DRY = true

val redditClient = RedditClient.newRedditClient(
    ClientConfiguration(
        UserAgent(
            PLATFORM,
            APP_ID,
            VERSION,
            AUTHOR_REDDIT_NAME
        ),
        OAuthConfiguration(
            System.getenv("OAUTH_CLIENT_ID"),
            System.getenv("OAUTH_REDIRECT_URI")
        ),
        HttpConfiguration(
            loggingLevel = HttpLoggingLevel.BASIC
        )
    )
)

fun main(av: Array<String>) {
    // Logging
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "trace")

    redditClient.oAuth.setRefreshToken(System.getenv("OAUTH_REFRESH_TOKEN"))

    // Dewit periodically
    Schedulers.single().schedulePeriodicallyDirect({ dewit() }, INITIAL_DELAY_MINUTES, PERIOD_MINUTES, TimeUnit.MINUTES)

    // Wait forever
    Object().let {
        synchronized(it) {
            it.wait()
        }
    }
}

private fun dewit() {
    redditClient.listings.hot(Subreddits.POPULAR, Pagination(FirstPage))
        .map { it.list.random() }
        .doOnSuccess {
            println()
            println(repeatString("*", 72))
            println()
            println("Chosen post:")
            printPosts(listOf(it!!))
        }
        .flatMap { redditClient.listings.comments(it.id) }
        .map { randomComment(it) }
        .doOnSuccess {
            println("Chosen comment:")
            printComments(listOf(it!!), false)
        }
        .flatMapCompletable {
            if (DRY) {
                Completable.complete()
            } else {
                redditClient.linksAndComments.comment(it, COMMENT_TEXT)
            }
        }
        .doOnComplete { print("Comment posted at ${Date()}\n") }
        .subscribe()
}

private fun randomComment(postWithComments: PostWithComments): Comment? {
    val allComments = mutableListOf<Comment>()
    for (comment in postWithComments.comments) {
        addCommentsWithReplies(allComments, comment)
    }
    return allComments.filterNot { it.isDeleted }.random()
}

private fun <E> Collection<E>.random(): E? {
    return elementAtOrNull(ThreadLocalRandom.current().nextInt(size))
}

private fun addCommentsWithReplies(allComments: MutableList<Comment>, comment: Comment) {
    allComments += comment
    for (reply in comment.replies) {
        // Recurse
        addCommentsWithReplies(allComments, reply)
    }
}

private fun printPosts(posts: List<Post>) {
    val separator = repeatString("─", 72)
    for (post in posts) {
        println("╭$separator")
        println("│https://www.reddit.com${post.permalink}")
        println("│Author: ${post.author}")
        println("│Comments: ${post.numComments}")
        println(("Title: " + post.title).wrapAndIndent(72, ""))
        post.selftext?.let {
            println("│")
            println(it.wrapAndIndent(72, ""))
        }
        println("╰$separator")
        println()
    }
}

private fun printComments(
    comments: List<Comment>,
    withReplies: Boolean = true,
    depth: Int = 0
) {
    val indent = repeatString("    ", depth)
    val separator = repeatString("─", 72)
    for (comment in comments) {
        println("$indent╭$separator")
        println("${indent}│Author: ${comment.author}")
        println("${indent}│Date: ${comment.created}")
        if (!withReplies && comment.replies.isNotEmpty()) {
            println("${indent}│Replies: ${comment.replies.size}")
        }
        if (comment.moreReplyIds.isNotEmpty()) {
            println("${indent}│More replies: ${comment.moreReplyIds.size}")
        }
        println("$indent│")
        println(indent + comment.body.wrapAndIndent(72, indent))
        println("$indent╰$separator")
        println()
        // Recursion
        if (withReplies) printComments(comment.replies, withReplies, depth + 1)
    }
}

private fun repeatString(s: String, times: Int): String {
    var res = ""
    for (i in 0 until times) res += s
    return res
}

private fun String.wrapAndIndent(wrapSize: Int, indent: String): String {
    val wrapped = WordUtils.wrap(this, wrapSize)
    return "│" + wrapped.trimEnd().replace("\n", "\n$indent│")
}