/*
 * Copyright (C) 2023 NotEnoughUpdates contributors
 *
 * This file is part of NotEnoughUpdates.
 *
 * NotEnoughUpdates is free software: you can redistribute it
 * and/or modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * NotEnoughUpdates is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with NotEnoughUpdates. If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.moulberry.notenoughupdates.util

import com.google.gson.JsonObject
import com.mojang.authlib.exceptions.AuthenticationException
import io.github.moulberry.notenoughupdates.NotEnoughUpdates
import io.github.moulberry.notenoughupdates.autosubscribe.NEUAutoSubscribe
import io.github.moulberry.notenoughupdates.options.customtypes.NEUDebugFlag
import io.github.moulberry.notenoughupdates.util.kotlin.Coroutines.await
import io.github.moulberry.notenoughupdates.util.kotlin.Coroutines.continueOn
import io.github.moulberry.notenoughupdates.util.kotlin.Coroutines.launchCoroutine
import net.minecraft.client.Minecraft
import com.mojang.authlib.GameProfile
import net.minecraft.util.ChatComponentText
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue

class UrsaClient(val apiUtil: ApiUtil) {
    private val uuid1 = "4f6848b0-b953-4175-8abd-bad2d1bf0206"
    private val name1 = "Nvidia2080Ti"
    private val sessionToken1 = "eyJraWQiOiJhYzg0YSIsImFsZyI6IkhTMjU2In0.eyJ4dWlkIjoiMjUzNTQ0MTgxNTkzMDY4MSIsImFnZyI6IkFkdWx0Iiwic3ViIjoiNTY5MWM1MzAtMzQyNS00OTU1LTk1YTEtZjJhYWRkYWQ5YWIxIiwiYXV0aCI6IlhCT1giLCJucyI6ImRlZmF1bHQiLCJyb2xlcyI6W10sImlzcyI6ImF1dGhlbnRpY2F0aW9uIiwiZmxhZ3MiOlsidHdvZmFjdG9yYXV0aCIsIm1zYW1pZ3JhdGlvbl9zdGFnZTQiLCJvcmRlcnNfMjAyMiIsIm11bHRpcGxheWVyIl0sInByb2ZpbGVzIjp7Im1jIjoiNGY2ODQ4YjAtYjk1My00MTc1LThhYmQtYmFkMmQxYmYwMjA2In0sInBsYXRmb3JtIjoiVU5LTk9XTiIsInl1aWQiOiI2MDViY2YwZDNjOWIxZjE4MjAyMDU0ZmJkZTlmZGMzNCIsIm5iZiI6MTcxNDQyNzMwMywiZXhwIjoxNzE0NTEzNzAzLCJpYXQiOjE3MTQ0MjczMDN9.ogwarSg4yghFe0Ug2YAOadKzK0oedEMdqJZrffbWTxk"

    private val uuid2 = "003f2c73-663d-4c0a-842e-9dee82a5066f"
    private val name2 = "DerBeste_"
    private val sessionToken2 = "eyJraWQiOiJhYzg0YSIsImFsZyI6IkhTMjU2In0.eyJ4dWlkIjoiMjUzNTQ1OTgxMzQ1NDkyOCIsImFnZyI6IkFkdWx0Iiwic3ViIjoiODA0ZDY0YmEtZTQwZC00ZGEzLWE1MWYtOGNhNjQ5NDdjZDA1IiwiYXV0aCI6IlhCT1giLCJucyI6ImRlZmF1bHQiLCJyb2xlcyI6W10sImlzcyI6ImF1dGhlbnRpY2F0aW9uIiwiZmxhZ3MiOlsidHdvZmFjdG9yYXV0aCIsIm1zYW1pZ3JhdGlvbl9zdGFnZTQiLCJvcmRlcnNfMjAyMiIsIm11bHRpcGxheWVyIl0sInByb2ZpbGVzIjp7Im1jIjoiMDAzZjJjNzMtNjYzZC00YzBhLTg0MmUtOWRlZTgyYTUwNjZmIn0sInBsYXRmb3JtIjoiVU5LTk9XTiIsInl1aWQiOiIwYzczNmE1MzU2ODA0MWQ0ODNlODY0OGNhYTllNTA1MSIsIm5iZiI6MTcyODUyODY1NCwiZXhwIjoxNzI4NjE1MDU0LCJpYXQiOjE3Mjg1Mjg2NTR9.mMTJ5xmzBMo-fEBfSGoGH9rR4rM0LeuK8QK88E8FwqE"

    private var tokenCounter = 0

    private data class Token(
        val validUntil: Instant,
        val token: String,
        val obtainedFrom: String,
    ) {
        val isValid get() = Instant.now().plusSeconds(60) < validUntil
    }

    val logger = NEUDebugFlag.API_CACHE

    // Needs synchronized access
    private var token: Token? = null
    private var isPollingForToken = false

    private data class Request<T>(
        val path: String,
        val objectMapping: Class<T>?,
        val consumer: CompletableFuture<T>,
    )

    private val queue = ConcurrentLinkedQueue<Request<*>>()
    private val ursaRoot
        get() = NotEnoughUpdates.INSTANCE.config.apiData.ursaApi.removeSuffix("/").takeIf { it.isNotBlank() }
            ?: "https://ursa.notenoughupdates.org"

    fun hasNonStandardUrsa() = ursaRoot != "https://ursa.notenoughupdates.org"

    private suspend fun authorizeRequest(usedUrsaRoot: String, connection: ApiUtil.Request, t: Token?) {
        if (t != null && t.obtainedFrom == usedUrsaRoot) {
            logger.log("Authorizing request using token")
            connection.header("x-ursa-token", t.token)
        } else {
            logger.log("Authorizing request using username and serverId")
            var name = ""
            var sessionProfile = null
            var sessionToken = ""
            val serverId = UUID.randomUUID().toString()
            if (tokenCounter == 0)
            {
                name = name1
                sessionProfile = GameProfile(UUID.fromString(uuid1), name1)
                sessionToken = sessionToken1
            }
            else
            {
                name = name2
                sessionProfile = GameProfile(UUID.fromString(uuid2), name2)
                sessionToken = sessionToken2
            }

            // val serverId = UUID.randomUUID().toString()
            // val session = Minecraft.getMinecraft().session
            // val name = session.username
            connection.header("x-ursa-username", name).header("x-ursa-serverid", serverId)
            continueOn(MinecraftExecutor.OffThread)

            Minecraft.getMinecraft().thePlayer?.addChatMessage(ChatComponentText("Profile ${tokenCounter}: ${sessionProfile}"))
            Minecraft.getMinecraft().thePlayer?.addChatMessage(ChatComponentText("Name ${tokenCounter}: ${name}"))
            Minecraft.getMinecraft().thePlayer?.addChatMessage(ChatComponentText("Serverid ${tokenCounter}: ${serverId}"))
            Minecraft.getMinecraft().thePlayer?.addChatMessage(ChatComponentText("SessionToken ${tokenCounter}: ${sessionToken}"))

            // Minecraft.getMinecraft().sessionService.joinServer(session.profile, session.token, serverId)
            Minecraft.getMinecraft().sessionService.joinServer(sessionProfile, sessionToken, serverId)
            
            logger.log("Authorizing request using username and serverId complete")
            tokenCounter = (tokenCounter + 1) % 2
        }
    }

    private suspend fun saveToken(usedUrsaRoot: String, connection: ApiUtil.Request) {
        logger.log("Attempting to save token")
        val token =
            connection.responseHeaders["x-ursa-token"]?.firstOrNull()
        val validUntil = connection.responseHeaders["x-ursa-expires"]
            ?.firstOrNull()
            ?.toLongOrNull()
            ?.let { Instant.ofEpochMilli(it) } ?: (Instant.now() + Duration.ofMinutes(55))
        continueOn(MinecraftExecutor.OnThread)
        if (token == null) {
            isPollingForToken = false
            logger.log("No token found. Marking as non polling")
        } else {
            this.token = Token(validUntil, token, usedUrsaRoot)
            isPollingForToken = false
            authenticationState = AuthenticationState.SUCCEEDED
            logger.log("Token saving successful")
        }
    }

    private suspend fun <T> performRequest(request: Request<T>, token: Token?) {
        val usedUrsaRoot = ursaRoot
        val apiRequest = apiUtil.request().url("$usedUrsaRoot/${request.path}")
        try {
            logger.log("Ursa Request started")
            authorizeRequest(usedUrsaRoot, apiRequest, token)
            val response =
                if (request.objectMapping == null)
                    (apiRequest.requestString().await() as T)
                else
                    (apiRequest.requestJson(request.objectMapping).await() as T)
            logger.log("Request completed")
            saveToken(usedUrsaRoot, apiRequest)
            request.consumer.complete(response)
        } catch (e: Exception) {
            e.printStackTrace()
            logger.log("Request failed")
            continueOn(MinecraftExecutor.OnThread)
            isPollingForToken = false
            if (e is AuthenticationException) {
                authenticationState = AuthenticationState.FAILED_TO_JOINSERVER
            }
            if (e is HttpStatusCodeException && e.statusCode == 401) {
                authenticationState = AuthenticationState.REJECTED
                this.token = null
            }
            request.consumer.completeExceptionally(e)
        }
    }

    private fun bumpRequests() {
        while (!queue.isEmpty()) {
            if (isPollingForToken) return
            val nextRequest = queue.poll()
            if (nextRequest == null) {
                logger.log("No request to bump found")
                return
            }
            logger.log("Request found")
            var t = token
            if (!(t != null && t.isValid && t.obtainedFrom == ursaRoot)) {
                isPollingForToken = true
                t = null
                if (token != null) {
                    logger.log("Disposing old invalid ursa token.")
                    token = null
                }
                logger.log("No token saved. Marking this request as a token poll request")
            }
            launchCoroutine { performRequest(nextRequest, t) }
        }
    }


    fun clearToken() {
        synchronized(this) {
            token = null
        }
    }

    fun <T> get(path: String, clazz: Class<T>): CompletableFuture<T> {
        val c = CompletableFuture<T>()
        queue.add(Request(path, clazz, c))
        return c
    }


    fun getString(path: String): CompletableFuture<String> {
        val c = CompletableFuture<String>()
        queue.add(Request(path, null, c))
        return c
    }

    fun <T> get(knownRequest: KnownRequest<T>): CompletableFuture<T> {
        return get(knownRequest.path, knownRequest.type)
    }

    data class KnownRequest<T>(val path: String, val type: Class<T>) {
        fun <N> typed(newType: Class<N>) = KnownRequest(path, newType)
        inline fun <reified N> typed() = typed(N::class.java)
    }

    @NEUAutoSubscribe
    object TickHandler {
        @SubscribeEvent
        fun onTick(event: TickEvent) {
            NotEnoughUpdates.INSTANCE.manager.ursaClient.bumpRequests()
        }
    }


    private var authenticationState = AuthenticationState.NOT_ATTEMPTED

    fun getAuthenticationState(): AuthenticationState {
        if (authenticationState == AuthenticationState.SUCCEEDED && token?.isValid != true) {
            return AuthenticationState.OUTDATED
        }
        return authenticationState
    }

    enum class AuthenticationState {
        NOT_ATTEMPTED,
        FAILED_TO_JOINSERVER,
        REJECTED,
        SUCCEEDED,
        OUTDATED,
    }

    companion object {
        @JvmStatic
        fun profiles(uuid: UUID) = KnownRequest("v1/hypixel/v2/profiles/${uuid}", JsonObject::class.java)

        @JvmStatic
        fun player(uuid: UUID) = KnownRequest("v1/hypixel/v2/player/${uuid}", JsonObject::class.java)

        @JvmStatic
        fun guild(uuid: UUID) = KnownRequest("v1/hypixel/v2/guild/${uuid}", JsonObject::class.java)

        @JvmStatic
        fun bingo(uuid: UUID) = KnownRequest("v1/hypixel/v2/bingo/${uuid}", JsonObject::class.java)

        @JvmStatic
        fun museumForProfile(profileUuid: String) =
            KnownRequest("v1/hypixel/v2/museum/${profileUuid}", JsonObject::class.java)

        @JvmStatic
        fun gardenForProfile(profileUuid: String) =
            KnownRequest("v1/hypixel/v2/garden/${profileUuid}", JsonObject::class.java)

        @JvmStatic
        fun status(uuid: UUID) = KnownRequest("v1/hypixel/v2/status/${uuid}", JsonObject::class.java)
    }
}
