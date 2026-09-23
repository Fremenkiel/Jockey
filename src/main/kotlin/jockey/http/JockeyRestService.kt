package jockey.http

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.ide.RestService
import jockey.core.JockeyException
import jockey.core.RunController
import jockey.core.SolutionResolver
import java.net.InetSocketAddress

/**
 * HTTP API on the IDE's built-in server, for example http://127.0.0.1:63342/api/jockey/projects?repo=/path.
 *
 * GET  /api/jockey/solutions
 * GET  /api/jockey/projects?repo=PATH
 * POST /api/jockey/start?repo=PATH&name=NAME[&debug=true][&wait=120]
 * POST /api/jockey/stop?repo=PATH&name=NAME[&wait=30]
 * POST /api/jockey/restart?repo=PATH&name=NAME[&debug=true][&wait=120]
 * GET  /api/jockey/logs?repo=PATH&name=NAME[&tail=200]
 */
class JockeyRestService : RestService() {
    override fun getServiceName() = SERVICE_NAME

    override fun isMethodSupported(method: HttpMethod) = method == HttpMethod.GET || method == HttpMethod.POST

    override fun getMaxRequestsPerMinute() = 600

    // Accept only callers that are not browsers. A web page cannot then drive the IDE through this API.
    override fun isHostTrusted(request: FullHttpRequest, urlDecoder: QueryStringDecoder): Boolean =
        request.headers().get(HttpHeaderNames.ORIGIN) == null && request.headers().get(HttpHeaderNames.REFERER) == null

    override fun execute(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): String? {
        val remote = context.channel().remoteAddress() as? InetSocketAddress
        if (remote?.address?.isLoopbackAddress != true) {
            respond(context, HttpResponseStatus.FORBIDDEN, error("Only local callers are allowed."))
            return null
        }

        val route = urlDecoder.path().removePrefix("/$PREFIX/$SERVICE_NAME").trim('/')
        val method = request.method()
        val params = urlDecoder.parameters().mapValues { it.value.firstOrNull().orEmpty() }

        // Start and restart wait for the app, so never block the Netty thread.
        service<JockeyScope>().scope.launch(Dispatchers.Default) {
            val (status, body) = try {
                HttpResponseStatus.OK to handle(route, method, params)
            } catch (e: JockeyException) {
                HttpResponseStatus.valueOf(e.httpStatus) to error(e.message ?: "Error")
            } catch (e: Exception) {
                LOG.warn("jockey request failed: $route", e)
                HttpResponseStatus.INTERNAL_SERVER_ERROR to error(e.message ?: e.javaClass.simpleName)
            }
            respond(context, status, body)
        }
        return null
    }

    private suspend fun handle(route: String, method: HttpMethod, params: Map<String, String>): String {
        fun requirePost() {
            if (method != HttpMethod.POST) throw JockeyException("Use POST for /$route.", httpStatus = 405)
        }
        fun name() = params["name"]?.takeIf { it.isNotBlank() } ?: throw JockeyException("Pass the name parameter.")
        fun int(key: String, default: Int) = params[key]?.toIntOrNull() ?: default
        fun bool(key: String) = params[key].equals("true", ignoreCase = true) || params[key] == "1"
        val repo = params["repo"]

        return when (route) {
            "", "help" -> HELP
            "solutions" -> JSON.encodeToString(RunController.listSolutions())
            "projects" -> JSON.encodeToString(RunController.list(SolutionResolver.resolve(repo), repo))
            "start" -> {
                requirePost()
                JSON.encodeToString(RunController.start(SolutionResolver.resolve(repo), repo, name(), bool("debug"), int("wait", 120)))
            }
            "stop" -> {
                requirePost()
                JSON.encodeToString(RunController.stop(SolutionResolver.resolve(repo), repo, name(), int("wait", 30)))
            }
            "restart" -> {
                requirePost()
                JSON.encodeToString(RunController.restart(SolutionResolver.resolve(repo), repo, name(), bool("debug"), int("wait", 120)))
            }
            "logs" -> JSON.encodeToString(RunController.logs(SolutionResolver.resolve(repo), repo, name(), int("tail", 200)))
            else -> throw JockeyException("Unknown route /$route. GET /$PREFIX/$SERVICE_NAME lists the routes.", httpStatus = 404)
        }
    }

    private fun respond(context: ChannelHandlerContext, status: HttpResponseStatus, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes))
        response.headers()
            .set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
            .set(HttpHeaderNames.CONTENT_LENGTH, bytes.size)
            .set(HttpHeaderNames.CACHE_CONTROL, "no-cache")
        context.channel().writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
    }

    private fun error(message: String) = buildJsonObject { put("error", message) }.toString()

    private companion object {
        const val SERVICE_NAME = "jockey"
        val LOG = logger<JockeyRestService>()
        val JSON = Json { encodeDefaults = true }
        val HELP = buildJsonObject {
            put("GET /api/jockey/solutions", "Open Rider solutions.")
            put("GET /api/jockey/projects?repo=PATH", "Run configurations with state, URLs and .NET projects under PATH.")
            put("POST /api/jockey/start?repo=PATH&name=NAME[&debug=true][&wait=120]", "Build and start. Waits until the app accepts connections.")
            put("POST /api/jockey/stop?repo=PATH&name=NAME[&wait=30]", "Stop all running instances.")
            put("POST /api/jockey/restart?repo=PATH&name=NAME[&debug=true][&wait=120]", "Stop, rebuild and start.")
            put("GET /api/jockey/logs?repo=PATH&name=NAME[&tail=200]", "Console output of the current or last run.")
        }.toString()
    }
}

/** Coroutine scope that the platform cancels when the plugin unloads. */
@Service(Service.Level.APP)
class JockeyScope(val scope: CoroutineScope)
