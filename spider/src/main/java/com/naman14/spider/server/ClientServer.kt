package com.naman14.spider.server

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.koushikdutta.async.http.server.AsyncHttpServer
import com.koushikdutta.async.callback.DataCallback
import com.koushikdutta.async.http.WebSocket
import com.koushikdutta.async.callback.CompletedCallback
import com.koushikdutta.async.http.server.HttpServerRequestCallback
import com.naman14.spider.db.RequestEntity
import com.naman14.spider.db.RequestsDao
import com.naman14.spider.db.SpiderDatabase
import com.naman14.spider.sendToAll
import com.naman14.spider.toJSONString
import com.naman14.spider.utils.Utils
import androidx.lifecycle.Observer
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.koushikdutta.async.http.body.AsyncHttpRequestBody

class ClientServer(context: Context) {

    private lateinit var websocketServer: AsyncHttpServer
    private lateinit var httpServer: AsyncHttpServer
    private lateinit var httpCallback: HttpServerRequestCallback
    private lateinit var websocketCallback: AsyncHttpServer.WebSocketRequestCallback
    private var memoryDb: RequestsDao = SpiderDatabase.getMemoryInstance(context)!!.requestsDao()
    private var diskDb: RequestsDao = SpiderDatabase.getDiskInstance(context)!!.requestsDao()

    private val socketList = ArrayList<WebSocket>()

    init {
        startServer(context)
    }

    private fun startServer(context: Context) {

        initRequestHandler(context)

        websocketServer = AsyncHttpServer()
        websocketServer.websocket("/", null, websocketCallback)
        websocketServer.listen(6061)

        httpServer = AsyncHttpServer()
        httpServer.get(".*.", httpCallback)
        httpServer.listen(6060)
    }

    private fun initRequestHandler(context: Context) {

        httpCallback = HttpServerRequestCallback { request, response ->
            var route = request.path.substring(1)
            val command = request.query.getString("command")
            if (command != null && command.isNotEmpty()) {
                when (command) {
                    "updateCall" -> {
                        val body: AsyncHttpRequestBody<String> = request.body as AsyncHttpRequestBody<String>
                        val type = object : TypeToken<RequestEntity>() {}.type
                        val requestEntity: RequestEntity = Gson().fromJson(body.get(), type)
                        diskDb.insertRequest(requestEntity)

                    }
                }
                response.send("Success")
            } else {
                if (route == "") route = "index.html"
                response.send(Utils.detectMimeType(route), Utils.loadContent(route, context.assets))
            }
        }

        websocketCallback = AsyncHttpServer.WebSocketRequestCallback { webSocket, request ->
            socketList.add(webSocket)

            webSocket.closedCallback = CompletedCallback { ex ->
                try {
                    ex?.printStackTrace()
                } finally {
                    socketList.remove(webSocket)
                }
            }

            webSocket.stringCallback = WebSocket.StringCallback { s ->
                try {
                    handleMessage(s, webSocket)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            webSocket.dataCallback = DataCallback { dataEmitter, byteBufferList -> byteBufferList.recycle() }

            val liveData = memoryDb.getAllRequests()
            Handler(Looper.getMainLooper()).post({
                liveData.observeForever(object: Observer<List<RequestEntity>> {
                    override fun onChanged(it: List<RequestEntity>) {
                        sendRequests(it)
                    }
                })
            })

        }
    }

    private fun handleMessage(message: String, socket: WebSocket) {

    }

    fun sendRequests(requests: List<RequestEntity>) {
        socketList.sendToAll(requests.toJSONString())
    }

}